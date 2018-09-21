package de.difuture.ekut.pht.lib.runtime.docker.spotify

import com.spotify.docker.client.DefaultDockerClient
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.exceptions.ContainerNotFoundException
import com.spotify.docker.client.exceptions.DockerException
import com.spotify.docker.client.exceptions.ImageNotFoundException
import com.spotify.docker.client.messages.ContainerConfig
import com.spotify.docker.client.messages.RegistryAuth
import de.difuture.ekut.pht.lib.data.DockerContainerId
import de.difuture.ekut.pht.lib.data.DockerContainerOutput
import de.difuture.ekut.pht.lib.data.DockerImageId
import de.difuture.ekut.pht.lib.data.DockerNetworkId
import de.difuture.ekut.pht.lib.runtime.docker.CreateDockerContainerFailedException
import de.difuture.ekut.pht.lib.runtime.docker.DockerRuntimeClient
import de.difuture.ekut.pht.lib.runtime.docker.DockerRuntimeClientException
import de.difuture.ekut.pht.lib.runtime.docker.NoSuchDockerContainerException
import de.difuture.ekut.pht.lib.runtime.docker.NoSuchDockerImageException
import de.difuture.ekut.pht.lib.runtime.docker.NoSuchDockerNetworkException
import de.difuture.ekut.pht.lib.runtime.interrupt.InterruptHandler
import de.difuture.ekut.pht.lib.runtime.interrupt.InterruptSignaler
import jdregistry.client.data.DockerRepositoryName
import jdregistry.client.data.DockerTag
import java.lang.IllegalStateException

/**
 * Spotify-client-based implementation of the [DockerClient] interface.
 *
 * This implementation entirely encapsulates completely the base Docker client related properties. For instance,
 * all Spotify related exceptions are converted to exceptions from the library
 *
 * @author Lukas Zimmermann
 * @see IDockerClient
 * @since 0.0.1
 *
 */
class SpotifyDockerClient : DockerRuntimeClient {

    private companion object {
        const val SEP = "/"
        const val EXIT_STATUS = "exited"
    }

    private val baseClient = DefaultDockerClient.fromEnv().build()

    /**
     * Translates the repoTag string to the corresponding unique Image ID
     */
    private fun repoTagToImageId(repoTag: String): DockerImageId {

        val images = baseClient.listImages().filter {

            val repoTags = it.repoTags()
            repoTags != null && repoTag in repoTags
        }
        return when (images.size) {

            0 -> throw IllegalStateException("Implementation Error! No Image with repoTag '$repoTag' was found!")
            1 -> DockerImageId(images.single().id())
            else -> throw IllegalStateException("Implementation Error! Multiple images with repoTag '$repoTag' found")
        }
    }

    override fun close() {

        this.baseClient.close()
    }

    override fun commit(
        containerId: DockerContainerId,
        targetRepo: DockerRepositoryName,
        targetTag: DockerTag,
        targetHost: String?,
        author: String?,
        comment: String?
    ): DockerImageId {

        try {
            val config = ContainerConfig.builder().build()
            // Handle trailing slashes for hostnames
            val hostString = targetHost?.let { if (it.endsWith(SEP)) it else "$it$SEP" } ?: ""
            this.baseClient.commitContainer(
                    containerId.repr,
                    "$hostString${targetRepo.asString()}",
                    targetTag.repr,
                    config,
                    comment,
                    author)

            return this.repoTagToImageId(targetRepo.resolve(targetTag, targetHost))
        } catch (ex: ContainerNotFoundException) {
            throw NoSuchDockerContainerException(ex, containerId)
        } catch (ex: DockerException) {
            throw DockerRuntimeClientException(ex)
        }
    }

    override fun images() =

            try {
                baseClient.listImages().map { DockerImageId(it.id()) }

                // rethrow DockerException, such that no Spotify Exception leaves this class
            } catch (ex: DockerException) {
                throw DockerRuntimeClientException(ex)
            }

    override fun pull(repo: DockerRepositoryName, tag: DockerTag, host: String?): DockerImageId {

        // The Spotify Docker Client only understands the ':' syntax for images and tags
        val repoTag = repo.resolve(tag, host)

        try {
            // First: Pull
            this.baseClient.pull(repoTag)
            // Now return the image ID of this image
            return this.repoTagToImageId(repoTag)
        } catch (ex: ImageNotFoundException) {
            throw NoSuchDockerImageException(ex, repo = repoTag)
        } catch (ex: DockerException) {
            throw DockerRuntimeClientException(ex)
        }
    }

    override fun push(repo: DockerRepositoryName, tag: DockerTag, host: String?) {

        val repoTag = repo.resolve(tag, host)
        try {
            baseClient.push(repoTag)
        } catch (ex: ImageNotFoundException) {
            throw NoSuchDockerImageException(ex, repoTag)
        } catch (ex: DockerException) {
            throw DockerRuntimeClientException(ex)
        }
    }

    override fun rm(containerId: DockerContainerId) {

        try {
            baseClient.removeContainer(containerId.repr)
        } catch (ex: ContainerNotFoundException) {
            throw NoSuchDockerContainerException(ex, containerId)
        } catch (ex: DockerException) {
            throw DockerRuntimeClientException(ex)
        }
    }

    override fun login(username: String, password: String, host: String?): Boolean {

        val builder = RegistryAuth.builder().username(username).password(password)
        val auth = host?.let { builder.serverAddress(it) } ?: builder
        return this.baseClient.auth(auth.build()) == 200
    }

    override fun run(
        imageId: DockerImageId,
        commands: List<String>,
        rm: Boolean,
        env: Map<String, String>?,
        networkId: DockerNetworkId?,
        warnings: MutableList<String>?,
        interruptSignaler: InterruptSignaler<DockerContainerId>?,
        interruptHandler: InterruptHandler<DockerContainerId>?
    ): DockerContainerOutput {

        // Before we even try to create the Container, check whether the Docker Network actually exists
        try {

            val networks = baseClient.listNetworks().map { it.id() }
            if (networkId != null && networkId.repr !in networks) {

                throw NoSuchDockerNetworkException(
                        "Docker Network with ID ${networkId.repr} does not exist!",
                        networkId)
            }

            // Rethrow as Docker Client exception from library
        } catch (ex: DockerException) {
            throw DockerRuntimeClientException(ex)
        }

        //  Map the environment map to the list format required by the Spotify Docker Client
        val envList = env?.map { (key, value) -> "${key.trim()}=${value.trim()}" } ?: emptyList()

        // Configuration for the Container Creation, currently only takes the Image Id
        val config = ContainerConfig.builder()
                .image(imageId.repr)
                .cmd(commands)
                .env(envList)
                .build()

        try {
            // We rethrow ImageNotFound and DockerClient exceptions, but let Interrupted Exceptions pass throw
            // TODO Platform type. Can this be null? This would not be documented by the method
            val creation = baseClient.createContainer(config)

            // Collect warnings to warnings list if present and if we are interested in warnings
            val creationWarnings = creation.warnings()
            if (creationWarnings != null && warnings != null) {

                warnings.addAll(creationWarnings)
            }

            // Fetch the Container ID
            val containerId = creation.id()
                    ?: throw CreateDockerContainerFailedException("Spotify Docker Client did not make the Docker Container ID available!")

            // We also need to container ID as proper object
            val containerIdObj = DockerContainerId(containerId)

            // Attach the container to a network, if this is requested
            if (networkId != null) {

                baseClient.connectToNetwork(containerId, networkId.repr)
            }

            // Now start the container
            baseClient.startContainer(containerId)

            // The Interrupt needs to be handled after the container has been started
            if (interruptSignaler != null && interruptHandler != null) {

                // Find the container that we have just started (we cannot use the baseClient waitContainer method,
                // as this does not allow the handling of timeouts
                // I use collection operations here, because I do not understand how the parameter of listContainers works
                val container = baseClient.listContainers().first { it.id() == containerId }

                while (container.status() != EXIT_STATUS) {

                    if (interruptSignaler.wasInterrupted(containerIdObj)) {

                        interruptHandler.handleInterrupt(containerIdObj)
                    }
                }
            }
            // Now fetch the container exit
            val exit = baseClient.waitContainer(containerId)

            val stdout = baseClient.logs(containerId, DockerClient.LogsParam.stdout()).readFully()
            val stderr = baseClient.logs(containerId, DockerClient.LogsParam.stderr()).readFully()

            // Remove the container if this was requested
            if (rm) {

                baseClient.removeContainer(containerId)
            }

            return DockerContainerOutput(
                    containerIdObj,
                    exit.statusCode().toInt(),
                    stdout,
                    stderr)
            // Rethrow as NoSuchImageException
        } catch (ex: ImageNotFoundException) {
            throw NoSuchDockerImageException(ex, imageId)
        } catch (ex: ContainerNotFoundException) {
            throw DockerRuntimeClientException(ex)
        } catch (ex: DockerException) {
            throw DockerRuntimeClientException(ex)
        }
    }
}

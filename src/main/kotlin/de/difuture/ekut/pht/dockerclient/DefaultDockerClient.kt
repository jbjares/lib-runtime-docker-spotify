package de.difuture.ekut.pht.dockerclient

import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.exceptions.ContainerNotFoundException
import com.spotify.docker.client.exceptions.DockerException
import com.spotify.docker.client.exceptions.ImageNotFoundException
import com.spotify.docker.client.messages.Container
import com.spotify.docker.client.messages.ContainerConfig
import de.difuture.ekut.pht.lib.data.DockerContainerId
import de.difuture.ekut.pht.lib.data.DockerImageId
import de.difuture.ekut.pht.lib.data.DockerNetworkId
import de.difuture.ekut.pht.lib.runtime.NoSuchDockerNetworkException
import de.difuture.ekut.pht.lib.runtime.NoSuchDockerContainerException
import de.difuture.ekut.pht.lib.runtime.DockerClientException
import de.difuture.ekut.pht.lib.runtime.NoSuchDockerImageException
import de.difuture.ekut.pht.lib.runtime.InterruptHandler
import de.difuture.ekut.pht.lib.runtime.CreateDockerContainerFailedException
import de.difuture.ekut.pht.lib.runtime.docker.DockerContainerOutput
import de.difuture.ekut.pht.lib.runtime.docker.DockerRuntimeClient
import jdregistry.client.data.DockerRepositoryName
import jdregistry.client.data.DockerTag

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
class DefaultDockerClient(private val baseClient: DockerClient) : DockerRuntimeClient {

    private fun repoTagToImageId(repoTag: String): DockerImageId {

        // Now Figure out the Image ID of the recently pulled image
        val allImages = baseClient.listImages()
        println(repoTag)
        val images = baseClient.listImages().filter {

            val repoTags = it.repoTags()
            repoTags != null && repoTag in repoTags
        }
        println(allImages)
        // Bad things have happened if this is not a Singleton
        return DockerImageId(images.single().id())
    }

    override fun close() {

        baseClient.close()
    }

    override fun commit(
        containerId: DockerContainerId,
        targetRepo: DockerRepositoryName,
        targetTag: DockerTag,
        author: String?,
        comment: String?
    ): DockerImageId {

        try {
            val config = ContainerConfig.builder().build()
            this.baseClient.commitContainer(
                    containerId.repr, targetRepo.asString(), targetTag.repr, config, comment, author)

            return this.repoTagToImageId(targetRepo.resolve(targetTag))
        } catch (ex: ContainerNotFoundException) {

            throw NoSuchDockerContainerException(ex)
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
        }
    }

    override fun images(): List<DockerImageId> =

        try {
            baseClient.listImages().map { DockerImageId(it.id()) }

        // rethrow DockerException, such that no Spotify Exception leaves this class
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
        }

    override fun pull(host: String, port: Int?, repo: DockerRepositoryName, tag: DockerTag): DockerImageId {

        try {
            // The Spotify Docker Client only understands the ':' syntax for images and tags
            val repoTag = repo.resolve(host, port, tag)

            // First: Pull
            baseClient.pull(repoTag)

            // Now return the image ID of this image
            return this.repoTagToImageId(repoTag)
        } catch (ex: ImageNotFoundException) {

            throw NoSuchDockerImageException(ex)
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
        }
    }

    override fun push(host: String, port: Int?, repo: DockerRepositoryName, tag: DockerTag) {

        try {
            baseClient.push(repo.resolve(host, port, tag))
        } catch (ex: ImageNotFoundException) {

            throw NoSuchDockerImageException(ex)
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
        }
    }

    override fun rm(containerId: DockerContainerId) {

        try {
            baseClient.removeContainer(containerId.repr)
        } catch (ex: ContainerNotFoundException) {

            throw NoSuchDockerImageException(ex)
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
        }
    }

    override fun run(
        imageId: DockerImageId,
        commands: List<String>,
        rm: Boolean,
        env: Map<String, String>?,
        networkId: DockerNetworkId?,
        warnings: MutableList<String>?,
        interruptHandler: InterruptHandler<DockerContainerId>?
    ): DockerContainerOutput {

        // Before we even try to create the Container, check whether the Docker Network actually exists
        try {
            if (networkId != null && networkId.repr !in baseClient.listNetworks().map { it.id() }) {

                throw NoSuchDockerNetworkException("Docker Network with ID ${networkId.repr} does not exist!")
            }

        // Rethrow as Docker Client exception from library
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
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
            val containerId = creation.id()?.let { it }
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
            if (interruptHandler != null) {

                // Find the container that we have just started (we cannot use the baseClient waitContainer method,
                // as this does not allow the handling of timeouts
                // I use collection operations here, because I do not understand how the parameter of listContainers works
                val container: Container = baseClient.listContainers().first { it.id() == containerId }

                while (container.status() != EXIT_STATUS) {

                    if (interruptHandler.wasInterrupted(containerIdObj)) {

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
                    exit.statusCode(),
                    stdout,
                    stderr)
        // Rethrow as NoSuchImageException
        } catch (ex: ImageNotFoundException) {

            throw NoSuchDockerImageException(ex)
        } catch (ex: ContainerNotFoundException) {

            throw NoSuchDockerContainerException("Apparently the created container was deleted before it could be started or be removed by this method")
        } catch (ex: DockerException) {

            throw DockerClientException(ex)
        }
    }

    companion object {

        private const val EXIT_STATUS = "exited"
    }
}

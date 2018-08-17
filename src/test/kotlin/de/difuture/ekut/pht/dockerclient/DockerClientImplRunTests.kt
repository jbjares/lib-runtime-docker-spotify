package de.difuture.ekut.pht.dockerclient

import com.spotify.docker.client.DefaultDockerClient
import de.difuture.ekut.pht.lib.runtime.IDockerClient
import org.junit.Before
import org.junit.Test


/**
 * Class that is particularly meant to test the run method of [DockerClientImpl]
 *
 * @author Lukas Zimmermann
 * @see DockerClientImpl
 * @since 0.0.1
 *
 */
class DockerClientImplRunTests {

    private lateinit var client : IDockerClient

    @Before
    fun before() {

        this.client = DockerClientImpl(DefaultDockerClient.fromEnv().build())
    }

    @Test
    fun run_alpine() {

        val image = this.client.pull(
                ALPINE_IMAGE,
                LATEST_TAG
        )
        this.client.run(image, emptyList(), true)
    }
}

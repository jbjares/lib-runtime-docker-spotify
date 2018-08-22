package de.difuture.ekut.pht.dockerclient

import com.spotify.docker.client.DefaultDockerClient
import de.difuture.ekut.pht.lib.runtime.docker.IDockerClient
import org.junit.Before
import org.junit.Test


/**
 * Class that is particularly meant to test the run method of [DefaultDockerClient]
 *
 * @author Lukas Zimmermann
 * @see DefaultDockerClient
 * @since 0.0.1
 *
 */
class DefaultDockerClientRunTests {

    private lateinit var client : IDockerClient

    @Before
    fun before() {

        this.client = DefaultDockerClient(DefaultDockerClient.fromEnv().build())
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

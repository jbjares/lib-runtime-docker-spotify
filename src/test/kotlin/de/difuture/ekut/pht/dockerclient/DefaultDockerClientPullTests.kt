package de.difuture.ekut.pht.dockerclient

import com.spotify.docker.client.DefaultDockerClient
import org.junit.Test
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.isIn
import de.difuture.ekut.pht.lib.runtime.docker.DockerRuntimeClient
import org.junit.Before

class DefaultDockerClientPullTests {

    private lateinit var client: DockerRuntimeClient

    @Before
    fun before() {

        this.client = DefaultDockerClient(DefaultDockerClient.fromEnv().build())
    }

    // Tests pull several image and ensure that the image id can be listed

    @Test fun pull_alpine() {

        val imageId = this.client.pull(
                "docker.io",
                null,
                ALPINE_IMAGE,
                LATEST_TAG
        )
        assert.that(imageId, isIn(client.images()))
    }
}

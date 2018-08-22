package de.difuture.ekut.pht.dockerclient

import com.spotify.docker.client.DefaultDockerClient
import de.difuture.ekut.pht.lib.runtime.IDockerClient
import org.junit.Test
import com.natpryce.hamkrest.assertion.assert
import com.natpryce.hamkrest.isIn
import org.junit.Before

class DockerClientImplPullTests {

    private lateinit var client : IDockerClient

    @Before
    fun before() {

        this.client = DefaultDockerClient(DefaultDockerClient.fromEnv().build())
    }


    // Tests pull several image and ensure that the image id can be listed

    @Test fun pull_alpine() {

        val imageId = this.client.pull(
                ALPINE_IMAGE,
                LATEST_TAG
        )
        assert.that(imageId, isIn(client.images()))
    }

}

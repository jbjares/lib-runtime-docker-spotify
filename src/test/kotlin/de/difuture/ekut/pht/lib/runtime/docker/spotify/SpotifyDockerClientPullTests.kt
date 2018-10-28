package de.difuture.ekut.pht.lib.runtime.docker.spotify

import de.difuture.ekut.pht.lib.runtime.docker.DockerRuntimeClient
import jdregistry.client.data.Tag as DockerTag
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class SpotifyDockerClientPullTests {

    private lateinit var client: DockerRuntimeClient

    @Before
    fun before() {

        this.client = SpotifyDockerClient()
    }

    // Tests pull several image and ensure that the image id can be listed

    @Test
    fun pull_alpine() {

        val imageId = this.client.pull(ALPINE_IMAGE, DockerTag.LATEST)

        Assert.assertTrue(imageId in client.images())
    }
}

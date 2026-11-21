package com.padvideo.repository

import com.padvideo.data.repository.SmbLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbLocationTest {
    @Test
    fun `SMB paths round trip with spaces Unicode and reserved filename characters`() {
        val root = SmbLocation.parse("smb://NAS.local:1445/电视 剧")
        val video = root.child("剧集 2 #100%.mkv")

        assertEquals("nas.local", root.host)
        assertEquals(1445, root.port)
        assertEquals("电视 剧", root.share)
        assertEquals("剧集 2 #100%.mkv", video.path)
        assertEquals(video, SmbLocation.parse(video.uri))
        assertEquals(root, video.parent())
    }

    @Test
    fun `default port is normalized and share is the root`() {
        val root = SmbLocation.parse("smb://10.0.0.2:445/Movies/")
        val nested = SmbLocation.parse("smb://10.0.0.2/Movies/Series/Episode%202.mp4")

        assertEquals("smb://10.0.0.2/Movies", root.uri)
        assertEquals("Series\\Episode 2.mp4", nested.path)
        assertEquals(root, nested.parent().parent())
    }

    @Test
    fun `reject incomplete ambiguous and credential bearing SMB addresses`() {
        listOf(
            "https://nas/Movies", "smb://nas", "smb://user:password@nas/Movies",
            "smb://nas/Movies?token=value", "smb://nas/Movies/video.mp4#fragment",
            "smb://nas:65536/Movies", "smb://nas/Movies/%2e%2e", "smb://nas/Movies/a%2fb",
            "smb://nas/Movies/a%5cb", "smb://nas/Movies/a//b"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SmbLocation.parse(value) }
        }
    }
}

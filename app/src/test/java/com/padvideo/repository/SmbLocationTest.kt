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
    fun `Windows UNC paths preserve Unicode spaces and literal percent and hash characters`() {
        val location = SmbLocation.parse("""\\WIN-PC\家庭 影片\Series #1\100%.mp4""")

        assertEquals("win-pc", location.host)
        assertEquals("家庭 影片", location.share)
        assertEquals("Series #1\\100%.mp4", location.path)
        assertEquals(location, SmbLocation.parse(location.uri))
    }

    @Test
    fun `server-only addresses open the server and allow navigating back from a share`() {
        val server = SmbLocation.parse("192.168.1.20")
        assertEquals(server, SmbLocation.parse("smb://192.168.1.20/"))
        assertEquals(server, SmbLocation.parse("""\\192.168.1.20"""))
        assertEquals(true, server.isServer)
        assertEquals("smb://192.168.1.20", server.uri)
        assertEquals(server, server.child("Movies").parent())
        assertEquals("smb://192.168.1.20/Movies", server.child("Movies").uri)
    }

    @Test
    fun `reject incomplete ambiguous and credential bearing SMB addresses`() {
        listOf(
            "https://nas/Movies", "smb://", "smb://user:password@nas/Movies",
            "smb://nas/Movies?token=value", "smb://nas/Movies/video.mp4#fragment",
            "smb://nas:65536/Movies", "smb://nas/Movies/%2e%2e", "smb://nas/Movies/a%2fb",
            "smb://nas/Movies/a%5cb", "smb://nas/Movies/a//b"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SmbLocation.parse(value) }
        }
    }
}

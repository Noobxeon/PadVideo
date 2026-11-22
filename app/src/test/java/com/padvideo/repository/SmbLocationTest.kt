package com.padvideo.repository

import com.padvideo.data.repository.SmbLocation
import com.padvideo.data.repository.SmbRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `parses Windows UNC paths bare IPs and server root for share enumeration`() {
        val unc = SmbLocation.parse("\\\\192.168.1.50\\Movies\\Sci-Fi")
        assertEquals("192.168.1.50", unc.host)
        assertEquals(445, unc.port)
        assertEquals("Movies", unc.share)
        assertEquals(listOf("Sci-Fi"), unc.segments)
        assertEquals("Sci-Fi", unc.path)

        val bareIp = SmbLocation.parse("192.168.1.50")
        assertEquals("192.168.1.50", bareIp.host)
        assertTrue(bareIp.isServer)
        assertNull(bareIp.share)
        assertEquals("Movies", bareIp.child("Movies").share)

        val smbHostOnly = SmbLocation.parse("smb://nas.local")
        assertTrue(smbHostOnly.isServer)
        assertEquals("nas.local", smbHostOnly.host)
    }

    @Test
    fun `default port is normalized and share parent climbs to server root`() {
        val root = SmbLocation.parse("smb://10.0.0.2:445/Movies/")
        val nested = SmbLocation.parse("smb://10.0.0.2/Movies/Series/Episode%202.mp4")

        assertEquals("smb://10.0.0.2/Movies", root.uri)
        assertEquals("Series\\Episode 2.mp4", nested.path)
        assertEquals(root, nested.parent().parent())
        assertTrue(root.parent().isServer)
    }

    @Test
    fun `credentials parse Windows machine-qualified accounts and guest mode`() {
        val winUser = SmbRepository.Credentials("DESKTOP-PC\\Alice", "secret", "")
        assertFalse(winUser.isGuest)
        assertEquals("Alice", winUser.username)
        assertEquals("DESKTOP-PC", winUser.resolvedDomain)

        val guest = SmbRepository.Credentials("", "", "")
        assertTrue(guest.isGuest)
    }

    @Test
    fun `reject invalid ambiguous and credential bearing SMB addresses`() {
        listOf(
            "", "https://nas/Movies", "smb://user:password@nas/Movies",
            "smb://nas/Movies?token=value", "smb://nas/Movies/video.mp4#fragment",
            "smb://nas:65536/Movies", "smb://nas/Movies/%2e%2e", "smb://nas/Movies/a%2fb",
            "smb://nas/Movies/a%5cb"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SmbLocation.parse(value) }
        }
    }
}

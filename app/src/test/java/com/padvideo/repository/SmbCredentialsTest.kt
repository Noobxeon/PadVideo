package com.padvideo.repository

import com.padvideo.data.repository.SmbRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbCredentialsTest {
    @Test
    fun `Windows computer prefix becomes the NTLM domain`() {
        val auth = SmbRepository.Credentials("""WIN-PC\alice""", " has spaces ", "").context

        assertEquals("WIN-PC", auth.domain)
        assertEquals("alice", auth.username)
        assertEquals(" has spaces ", auth.password.concatToString())
    }

    @Test
    fun `qualified Microsoft accounts and separate domain fields are preserved`() {
        val microsoft = SmbRepository.Credentials("""MicrosoftAccount\alice@example.com""", "password", "").context
        val local = SmbRepository.Credentials("alice", "password", "WIN-PC").context

        assertEquals("MicrosoftAccount", microsoft.domain)
        assertEquals("alice@example.com", microsoft.username)
        assertEquals("WIN-PC", local.domain)
    }

    @Test
    fun `conflicting domains or a password without a user are reported before connection`() {
        assertThrows(IllegalArgumentException::class.java) {
            SmbRepository.Credentials("""PC\alice""", "password", "OTHER")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmbRepository.Credentials("", "password", "")
        }
    }
}

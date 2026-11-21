package com.padvideo.repository

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.padvideo.data.repository.SmbConnectionFailure
import com.padvideo.data.repository.SmbLocation
import com.padvideo.data.repository.SmbRepository
import com.padvideo.player.SmbDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class SmbPlaybackTest {
    @Test
    fun `browse authenticated share and read a video starting at a seek offset`() = runTest {
        assumeTrue("Start the local SMB fixture first", System.getenv("PADVIDEO_TEST_SMB") == "1")
        val root = SmbLocation.parse("""\\127.0.0.1:1445\VIDEOS""")
        val repo = SmbRepository()
        val folder = repo.list(root, SmbRepository.Credentials("""WORKGROUP\padvideo""", "fixture-password", "")).single()
        assertEquals("剧集", folder.name)
        assertEquals(true, folder.isFolder)

        val files = repo.list(folder.location)
        assertEquals(listOf("Episode 2 #.mp4", "Episode 10.mp4"), files.map { it.name })

        val source = SmbDataSource(repo)
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(files.first().asVideo().uriString))
            .setPosition(4)
            .setLength(3)
            .build()
        try {
            assertEquals(3L, source.open(spec))
            val buffer = ByteArray(3)
            assertEquals(3, source.read(buffer, 0, buffer.size))
            assertEquals("456", buffer.toString(Charsets.UTF_8))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        } finally {
            source.close()
        }
    }

    @Test
    fun `rejected password is reported as an authentication error without showing secrets`() = runTest {
        assumeTrue("Start the local SMB fixture first", System.getenv("PADVIDEO_TEST_SMB") == "1")
        val root = SmbLocation.parse("smb://127.0.0.1:1445/VIDEOS")
        val failure = runCatching {
            SmbRepository().list(root, SmbRepository.Credentials("padvideo", "wrong-password", ""))
        }.exceptionOrNull()

        assertTrue(failure is SmbConnectionFailure)
        assertTrue(failure?.message.orEmpty().contains("请核对账号和密码"))
        assertFalse(failure?.message.orEmpty().contains("wrong-password"))
    }

}

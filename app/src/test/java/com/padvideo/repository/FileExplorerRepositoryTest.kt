package com.padvideo.repository

import com.padvideo.data.model.SortField
import com.padvideo.data.repository.FileExplorerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileExplorerRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `listDirectory groups folders first applies natural sort and attaches sidecar subtitles`() {
        val root = tempFolder.newFolder("TabletStorage")
        val moviesDir = File(root, "Movies").apply { mkdirs() }
        File(moviesDir, "Episode 10.mp4").writeBytes(ByteArray(2048))
        File(moviesDir, "Episode 2.mp4").writeBytes(ByteArray(1024))
        File(moviesDir, "Interstellar.2014.mkv").writeBytes(ByteArray(4096))
        File(moviesDir, "Interstellar.2014.zh.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\n你好")
        File(moviesDir, "Interstellar.2014.en.ass").writeText("[Events]")
        File(moviesDir, ".hidden_clip.mp4").writeBytes(ByteArray(512))
        File(moviesDir, "notes.txt").writeText("ignore non-media by default")
        File(moviesDir, "Extras").mkdirs()

        val repo = FileExplorerRepository(context = null)

        val entries = repo.listDirectory(
            directory = moviesDir,
            showHidden = false,
            sortField = SortField.NAME,
            sortAscending = true
        )

        // Extras folder first, then Episode 2, Episode 10, Interstellar.en.ass, Interstellar.mkv, Interstellar.zh.srt
        assertTrue(entries.first().isDirectory)
        assertEquals("Extras", entries.first().name)
        assertFalse(entries.any { it.name.startsWith(".") })
        assertFalse(entries.any { it.name == "notes.txt" })

        val videoNames = entries.filter { it.isVideoFile }.map { it.name }
        assertEquals(
            listOf("Episode 2.mp4", "Episode 10.mp4", "Interstellar.2014.mkv"),
            videoNames
        )

        val interstellarEntry = entries.first { it.name == "Interstellar.2014.mkv" }
        assertEquals(2, interstellarEntry.sidecarSubtitles.size)

        // Breadcrumbs verification
        val crumbs = repo.buildBreadcrumbs(moviesDir, baseRoot = root)
        assertEquals(listOf("内部存储", "Movies"), crumbs.map { it.label })

        // Sibling playlist natural order verification
        val playlist = repo.findSiblingEpisodePlaylist(interstellarEntry.videoItem!!)
        assertEquals(3, playlist.size)
        assertEquals("Episode 2.mp4", playlist[0].title)
        assertEquals("Episode 10.mp4", playlist[1].title)
    }
}

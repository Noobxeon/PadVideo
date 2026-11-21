package com.padvideo.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoFilenameAndHashTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parse cleans movie release tags and preserves numeric movie titles`() {
        val matrix = VideoFilenameParser.parse("The.Matrix.1999.2160p.UHD.BluRay.x265.10bit.HDR.mkv")
        assertEquals("The Matrix", matrix.cleanTitle)
        assertEquals(1999, matrix.year)
        assertNull(matrix.season)
        assertNull(matrix.episode)

        val movie1917 = VideoFilenameParser.parse("1917.2019.1080p.BluRay.x264.mp4")
        assertEquals("1917", movie1917.cleanTitle)
        assertEquals(2019, movie1917.year)
    }

    @Test
    fun `parse extracts TV season and episode numbers and anime episodes`() {
        val tv = VideoFilenameParser.parse("Severance.S02E04.1080p.WEB-DL.DDP5.1.H.264.mkv")
        assertEquals("Severance", tv.cleanTitle)
        assertEquals(2, tv.season)
        assertEquals(4, tv.episode)
        assertTrue(tv.isTvEpisode)
        assertEquals("Severance S02E04", tv.toDisplayQuery())

        val anime = VideoFilenameParser.parse("[SubsPlease] 葬送的芙莉莲 - 08 [1080p][HEVC].mkv")
        assertEquals("葬送的芙莉莲", anime.cleanTitle)
        assertEquals(1, anime.season)
        assertEquals(8, anime.episode)
    }

    @Test
    fun `computeOpenSubtitlesHash and computeShooterHash produce valid deterministic hashes`() {
        val smallFile = tempFolder.newFile("tiny.mp4").apply {
            writeBytes(ByteArray(1024) { 1 })
        }
        assertNull(VideoFilenameParser.computeOpenSubtitlesHash(smallFile))
        assertNull(VideoFilenameParser.computeShooterHash(smallFile))

        val videoFile = tempFolder.newFile("sample_movie.mkv").apply {
            val content = ByteArray(140_000) { idx -> (idx % 251).toByte() }
            writeBytes(content)
        }

        val osHash1 = VideoFilenameParser.computeOpenSubtitlesHash(videoFile)
        val osHash2 = VideoFilenameParser.computeOpenSubtitlesHash(videoFile)
        assertNotNull(osHash1)
        assertEquals(16, osHash1!!.length)
        assertEquals(osHash1, osHash2)

        val shooterHash = VideoFilenameParser.computeShooterHash(videoFile)
        assertNotNull(shooterHash)
        val segments = shooterHash!!.split(';')
        assertEquals(4, segments.size)
        assertTrue(segments.all { it.length == 32 })
    }
}

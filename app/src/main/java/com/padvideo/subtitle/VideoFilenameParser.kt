package com.padvideo.subtitle

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

data class CleanedVideoQuery(
    val cleanTitle: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
) {
    val isTvEpisode: Boolean
        get() = season != null || episode != null

    fun toDisplayQuery(): String {
        return buildString {
            append(cleanTitle)
            if (season != null && episode != null) {
                append(String.format(Locale.US, " S%02dE%02d", season, episode))
            } else if (year != null) {
                append(" ").append(year)
            }
        }.trim()
    }
}

object VideoFilenameParser {

    private val BRACKET_TAG_REGEX = Regex("""\[[^\]]*]|\([^)]*(?:1080|2160|720|x264|x265|BluRay|WEB)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val SEASON_EPISODE_REGEX = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})\b|\b(\d{1,2})x(\d{1,2})\b""")
    private val ANIME_EPISODE_REGEX = Regex("""(?:\s-\s|\bEP?\.?)(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val RELEASE_JUNK_REGEX = Regex(
        """(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr|hdr10|dv|dolby|bluray|bdrip|brrip|web-dl|webdl|webrip|hdtv|dvdrip|remux|x264|x265|h264|h265|hevc|avc|av1|10bit|8bit|aac|ac3|eac3|dts|truehd|atmos|5\.1|7\.1|proper|repack|extended|directors\.cut|unrated|yts|rarbg)\b.*$"""
    )

    fun parse(rawFileNameOrTitle: String): CleanedVideoQuery {
        val withoutExt = rawFileNameOrTitle
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""\.[a-zA-Z0-9]{2,4}$"""), "")

        var working = withoutExt
            .replace(BRACKET_TAG_REGEX, " ")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()

        var season: Int? = null
        var episode: Int? = null
        var cutPosition = working.length

        val seMatch = SEASON_EPISODE_REGEX.find(working)
        if (seMatch != null) {
            if (seMatch.groupValues[1].isNotEmpty()) {
                season = seMatch.groupValues[1].toIntOrNull()
                episode = seMatch.groupValues[2].toIntOrNull()
            } else {
                season = seMatch.groupValues[3].toIntOrNull()
                episode = seMatch.groupValues[4].toIntOrNull()
            }
            cutPosition = minOf(cutPosition, seMatch.range.first)
        }

        var year: Int? = null
        val yearMatches = YEAR_REGEX.findAll(working).toList()
        if (yearMatches.isNotEmpty()) {
            // Pick the last year token unless it IS the whole title (e.g. "1917" or "2012")
            val candidate = yearMatches.last()
            if (candidate.range.first > 1) {
                year = candidate.value.toIntOrNull()
                cutPosition = minOf(cutPosition, candidate.range.first)
            }
        }

        var titlePart = working.substring(0, cutPosition).trim()
        titlePart = titlePart.replace(RELEASE_JUNK_REGEX, "").trim()

        if (season == null && episode == null) {
            val epMatch = ANIME_EPISODE_REGEX.find(titlePart)
            if (epMatch != null && epMatch.range.first > 1) {
                episode = epMatch.groupValues[1].toIntOrNull()
                season = 1
                titlePart = titlePart.substring(0, epMatch.range.first).trim()
            }
        }

        val finalTitle = titlePart
            .trim(' ', '-', '—', '[', ']', '(', ')')
            .replace(Regex("""\s{2,}"""), " ")
            .ifBlank { withoutExt.replace('.', ' ').trim() }

        return CleanedVideoQuery(
            cleanTitle = finalTitle,
            year = year,
            season = season,
            episode = episode
        )
    }

    /**
     * Computes the official OpenSubtitles 64-bit hash:
     * fileSize + 64-bit little-endian words of first 64KB + last 64KB.
     */
    fun computeOpenSubtitlesHash(file: File): String? {
        val chunkSize = 65536
        if (!file.exists() || !file.isFile || file.length() < chunkSize) return null
        return runCatching {
            val length = file.length()
            var hash = length
            RandomAccessFile(file, "r").use { raf ->
                val headBuf = ByteArray(chunkSize)
                raf.seek(0L)
                raf.readFully(headBuf)
                hash += sumLittleEndianLongs(headBuf)

                val tailBuf = ByteArray(chunkSize)
                raf.seek((length - chunkSize).coerceAtLeast(0L))
                raf.readFully(tailBuf)
                hash += sumLittleEndianLongs(tailBuf)
            }
            java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0').lowercase(Locale.US)
        }.getOrNull()
    }

    private fun sumLittleEndianLongs(bytes: ByteArray): Long {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0L
        while (bb.remaining() >= 8) {
            sum += bb.long
        }
        return sum
    }

    /**
     * Computes Shooter.cn 4-block MD5 file hash:
     * 4KB MD5 digests at offsets [4096, len*2/3, len/3, len-8192] joined by ';'
     */
    fun computeShooterHash(file: File): String? {
        val minLength = 8192L
        val blockSize = 4096
        if (!file.exists() || !file.isFile || file.length() < minLength) return null
        return runCatching {
            val len = file.length()
            val offsets = longArrayOf(
                4096L,
                len * 2L / 3L,
                len / 3L,
                len - 8192L
            )
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(blockSize)
                offsets.joinToString(";") { offset ->
                    raf.seek(offset.coerceIn(0L, len - blockSize))
                    raf.readFully(buf)
                    val md5 = MessageDigest.getInstance("MD5").digest(buf)
                    md5.joinToString("") { b -> String.format(Locale.US, "%02x", b) }
                }
            }
        }.getOrNull()
    }
}

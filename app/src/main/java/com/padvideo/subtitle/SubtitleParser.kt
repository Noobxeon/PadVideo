package com.padvideo.subtitle

import com.padvideo.data.model.SubtitleCue
import com.padvideo.data.model.SubtitleFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object SubtitleParser {

    private val SRT_ARROW_REGEX = Regex(
        """^\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[,.](\d{1,3}).*$"""
    )
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val ASS_OVERRIDE_REGEX = Regex("""\{[^}]*\}""")
    private val MULTI_SPACE_REGEX = Regex("""[ \t]{2,}""")

    fun detectFormat(fileName: String, content: String): SubtitleFormat {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val trimmed = content.trimStart()
        return when {
            ext == "ass" || ext == "ssa" || trimmed.startsWith("[Script Info]", ignoreCase = true) ||
                trimmed.contains("\n[Events]", ignoreCase = true) -> SubtitleFormat.ASS
            ext == "vtt" || trimmed.startsWith("WEBVTT", ignoreCase = true) -> SubtitleFormat.VTT
            ext == "srt" || trimmed.contains("-->") -> SubtitleFormat.SRT
            else -> SubtitleFormat.UNKNOWN
        }
    }

    fun parseBytes(bytes: ByteArray, fileNameHint: String = "subtitle.srt"): List<SubtitleCue> {
        val unpacked = unpackArchiveIfNeeded(bytes, fileNameHint)
        val decoded = EncodingDetector.decodeBytes(unpacked.first)
        return parseText(decoded, unpacked.second)
    }

    fun parseFile(file: File): List<SubtitleCue> {
        if (!file.exists() || !file.isFile) return emptyList()
        return parseBytes(file.readBytes(), file.name)
    }

    fun parseText(rawContent: String, fileNameHint: String = "subtitle.srt"): List<SubtitleCue> {
        if (rawContent.isBlank()) return emptyList()
        val normalized = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return when (detectFormat(fileNameHint, normalized)) {
            SubtitleFormat.ASS -> parseAss(normalized)
            SubtitleFormat.VTT -> parseSrtOrVtt(normalized, isVtt = true)
            SubtitleFormat.SRT, SubtitleFormat.UNKNOWN -> parseSrtOrVtt(normalized, isVtt = false)
        }
    }

    private fun parseSrtOrVtt(content: String, isVtt: Boolean): List<SubtitleCue> {
        val lines = content.split('\n')
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        val total = lines.size

        // Skip WEBVTT header if present
        if (isVtt && i < total && lines[i].trim().startsWith("WEBVTT", ignoreCase = true)) {
            i++
            while (i < total && lines[i].isNotBlank()) i++
        }

        while (i < total) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            if (isVtt && (line.startsWith("NOTE", ignoreCase = true) || line.startsWith("STYLE", ignoreCase = true))) {
                i++
                while (i < total && lines[i].isNotBlank()) i++
                continue
            }

            var timeLine = line
            if (!timeLine.contains("-->") && i + 1 < total && lines[i + 1].contains("-->")) {
                i++
                timeLine = lines[i].trim()
            }

            val match = SRT_ARROW_REGEX.matchEntire(timeLine)
            if (match != null) {
                val startMs = parseFlexibleTime(
                    hoursPart = match.groupValues[1],
                    minutesPart = match.groupValues[2],
                    secondsPart = match.groupValues[3],
                    millisPart = match.groupValues[4]
                )
                val endMs = parseFlexibleTime(
                    hoursPart = match.groupValues[5],
                    minutesPart = match.groupValues[6],
                    secondsPart = match.groupValues[7],
                    millisPart = match.groupValues[8]
                )
                i++
                val textLines = mutableListOf<String>()
                while (i < total && lines[i].isNotBlank()) {
                    val cleaned = cleanSubtitleText(lines[i])
                    if (cleaned.isNotEmpty()) {
                        textLines.add(cleaned)
                    }
                    i++
                }
                if (textLines.isNotEmpty() && endMs > startMs) {
                    cues.add(
                        SubtitleCue(
                            index = cues.size + 1,
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            originalText = textLines.joinToString("\n")
                        )
                    )
                }
            } else {
                i++
            }
        }

        return cues.sortedBy { it.startTimeMs }.mapIndexed { idx, cue -> cue.copy(index = idx + 1) }
    }

    private fun parseAss(content: String): List<SubtitleCue> {
        val lines = content.split('\n')
        var inEvents = false
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var formatFieldCount = 10
        val rawCues = mutableListOf<SubtitleCue>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                val fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                formatFieldCount = fields.size.coerceAtLeast(3)
                fields.indexOf("start").takeIf { it >= 0 }?.let { startIdx = it }
                fields.indexOf("end").takeIf { it >= 0 }?.let { endIdx = it }
                fields.indexOf("text").takeIf { it >= 0 }?.let { textIdx = it }
            } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter(':')
                val parts = payload.split(',', limit = formatFieldCount)
                if (parts.size > maxOf(startIdx, endIdx, textIdx)) {
                    val startMs = parseAssTime(parts[startIdx].trim())
                    val endMs = parseAssTime(parts[endIdx].trim())
                    val rawText = parts[textIdx]
                    // Ignore vector drawing commands {\p1}..{\p0}
                    if (rawText.contains("\\p1") || rawText.contains("\\p2")) continue
                    val cleaned = cleanAssText(rawText)
                    if (cleaned.isNotBlank() && endMs > startMs) {
                        rawCues.add(
                            SubtitleCue(
                                index = rawCues.size + 1,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                originalText = cleaned
                            )
                        )
                    }
                }
            }
        }

        // Merge identical timestamp cues (common in bilingual ASS files where CN & EN are separate Dialogue lines)
        val grouped = rawCues
            .sortedBy { it.startTimeMs }
            .groupBy { it.startTimeMs to it.endTimeMs }
            .entries
            .mapIndexed { idx, (range, items) ->
                val combinedText = items.map { it.originalText.trim() }
                    .distinct()
                    .joinToString("\n")
                SubtitleCue(
                    index = idx + 1,
                    startTimeMs = range.first,
                    endTimeMs = range.second,
                    originalText = combinedText
                )
            }

        return grouped
    }

    private fun parseFlexibleTime(
        hoursPart: String,
        minutesPart: String,
        secondsPart: String,
        millisPart: String
    ): Long {
        val hours = hoursPart.trimEnd(':').toLongOrNull() ?: 0L
        val minutes = minutesPart.toLongOrNull() ?: 0L
        val seconds = secondsPart.toLongOrNull() ?: 0L
        val millisNormalized = when (millisPart.length) {
            1 -> (millisPart.toLongOrNull() ?: 0L) * 100L
            2 -> (millisPart.toLongOrNull() ?: 0L) * 10L
            else -> millisPart.take(3).toLongOrNull() ?: 0L
        }
        return ((hours * 3600L + minutes * 60L + seconds) * 1000L) + millisNormalized
    }

    private fun parseAssTime(value: String): Long {
        // Format: H:MM:SS.cc (centiseconds)
        val parts = value.split(':')
        if (parts.size != 3) return 0L
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val secParts = parts[2].split('.', ',')
        val seconds = secParts[0].toLongOrNull() ?: 0L
        val centisOrMillis = if (secParts.size > 1) {
            val frac = secParts[1]
            when (frac.length) {
                1 -> (frac.toLongOrNull() ?: 0L) * 100L
                2 -> (frac.toLongOrNull() ?: 0L) * 10L
                else -> frac.take(3).toLongOrNull() ?: 0L
            }
        } else {
            0L
        }
        return ((hours * 3600L + minutes * 60L + seconds) * 1000L) + centisOrMillis
    }

    fun cleanSubtitleText(raw: String): String {
        return raw
            .replace(ASS_OVERRIDE_REGEX, "")
            .replace(HTML_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    private fun cleanAssText(raw: String): String {
        return raw
            .replace(ASS_OVERRIDE_REGEX, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .lines()
            .map { cleanSubtitleText(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun findActiveCues(
        cues: List<SubtitleCue>,
        positionMs: Long,
        syncOffsetMs: Long = 0L
    ): List<SubtitleCue> {
        if (cues.isEmpty()) return emptyList()
        // Positive syncOffsetMs delays subtitles (cue appears later -> compare against positionMs - syncOffsetMs)
        val targetMs = positionMs - syncOffsetMs

        // Binary search to find first cue near targetMs
        var low = 0
        var high = cues.lastIndex
        var pivot = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            if (cue.endTimeMs < targetMs) {
                low = mid + 1
            } else if (cue.startTimeMs > targetMs) {
                high = mid - 1
            } else {
                pivot = mid
                break
            }
        }
        val checkStart = (if (pivot > 0) pivot - 5 else (low - 5)).coerceAtLeast(0)
        val checkEnd = (checkStart + 14).coerceAtMost(cues.lastIndex)
        val active = mutableListOf<SubtitleCue>()
        for (idx in checkStart..checkEnd) {
            val c = cues[idx]
            if (targetMs in c.startTimeMs..c.endTimeMs) {
                active.add(c)
            }
        }
        return active
    }

    fun findCueIndexAtOrAfter(cues: List<SubtitleCue>, positionMs: Long, syncOffsetMs: Long = 0L): Int {
        if (cues.isEmpty()) return -1
        val targetMs = positionMs - syncOffsetMs
        var low = 0
        var high = cues.lastIndex
        var answer = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].endTimeMs >= targetMs) {
                answer = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return answer
    }

    fun exportToSrt(cues: List<SubtitleCue>, includeTranslation: Boolean = true): String {
        val sb = StringBuilder()
        cues.forEachIndexed { idx, cue ->
            sb.append(idx + 1).append('\n')
            sb.append(formatSrtTimestamp(cue.startTimeMs))
                .append(" --> ")
                .append(formatSrtTimestamp(cue.endTimeMs))
                .append('\n')
            val trans = cue.translatedText?.trim()
            if (includeTranslation && !trans.isNullOrEmpty() && trans != cue.originalText.trim()) {
                sb.append(trans).append('\n')
            }
            sb.append(cue.originalText.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun formatSrtTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = (clamped % 3_600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1000L
        val millis = clamped % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun unpackArchiveIfNeeded(bytes: ByteArray, fileNameHint: String): Pair<ByteArray, String> {
        if (bytes.size < 4) return bytes to fileNameHint

        // Check ZIP magic header: PK\x03\x04
        if (bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        ) {
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var bestBytes: ByteArray? = null
                    var bestName = fileNameHint
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.substringAfterLast('/')
                            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                            if (ext in setOf("srt", "ass", "ssa", "vtt", "sub")) {
                                val data = zis.readBytes()
                                // Prefer ASS or SRT with non-empty content
                                if (bestBytes == null || ext == "ass" || ext == "srt") {
                                    bestBytes = data
                                    bestName = name
                                    if (ext == "srt" || ext == "ass") break
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                    if (bestBytes != null) {
                        return bestBytes!! to bestName
                    }
                }
            }
        }

        // Check GZIP magic header: 0x1F 0x8B
        if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gis ->
                    val strippedName = fileNameHint.removeSuffix(".gz").ifBlank { "subtitle.srt" }
                    return gis.readBytes() to strippedName
                }
            }
        }

        return bytes to fileNameHint
    }
}

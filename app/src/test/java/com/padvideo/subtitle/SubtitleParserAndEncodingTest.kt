package com.padvideo.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitleParserAndEncodingTest {

    @Test
    fun `decodeBytes handles GBK Chinese subtitles without garbled characters`() {
        val sampleSrt = """
            1
            00:00:01,000 --> 00:00:03,500
            你好，欢迎使用平板双语影院！

            2
            00:00:04,000 --> 00:00:06,800
            支持自动识别 GB18030 与 UTF-8 编码。
        """.trimIndent()

        val gbkBytes = sampleSrt.toByteArray(Charset.forName("GB18030"))
        val cues = SubtitleParser.parseBytes(gbkBytes, "movie.chs.srt")

        assertEquals(2, cues.size)
        assertEquals("你好，欢迎使用平板双语影院！", cues[0].originalText)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(3500L, cues[0].endTimeMs)
        assertEquals("支持自动识别 GB18030 与 UTF-8 编码。", cues[1].originalText)
    }

    @Test
    fun `parseText parses WebVTT and strips inline HTML and override tags`() {
        val vtt = """
            WEBVTT
            Kind: captions

            NOTE This is a comment block

            00:02.500 --> 00:05.250 align:start position:0%
            <c.yellow><i>Wake up</i></c>, Neo...

            01:02:10.100 --> 01:02:14.900
            {\an8}Follow the <b>white rabbit</b>.
        """.trimIndent()

        val cues = SubtitleParser.parseText(vtt, "matrix.vtt")
        assertEquals(2, cues.size)
        assertEquals(2500L, cues[0].startTimeMs)
        assertEquals(5250L, cues[0].endTimeMs)
        assertEquals("Wake up, Neo...", cues[0].originalText)
        assertEquals(3_730_100L, cues[1].startTimeMs)
        assertEquals("Follow the white rabbit.", cues[1].originalText)
    }

    @Test
    fun `parseText parses ASS dialogue lines merges bilingual timestamps and skips vector drawings`() {
        val ass = """
            [Script Info]
            Title: Bilingual Fansub Sample
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:08.40,Default,,0,0,0,,{\fad(200,200)}星空真是浩瀚无垠\N让人心生敬畏
            Dialogue: 0,0:00:05.10,0:00:08.40,Eng,,0,0,0,,{\fs14}The starry sky is truly vast.
            Dialogue: 0,0:00:09.00,0:00:10.00,Sign,,0,0,0,,{\p1}m 0 0 l 100 0 100 100 0 100{\p0}
        """.trimIndent()

        val cues = SubtitleParser.parseText(ass, "interstellar.ass")
        assertEquals(1, cues.size)
        assertEquals(5100L, cues[0].startTimeMs)
        assertEquals(8400L, cues[0].endTimeMs)
        assertTrue(cues[0].originalText.contains("星空真是浩瀚无垠"))
        assertTrue(cues[0].originalText.contains("The starry sky is truly vast."))
    }

    @Test
    fun `parseBytes automatically extracts SRT from ZIP and GZIP archives`() {
        val srtText = """
            1
            00:00:10,000 --> 00:00:12,500
            Extracted directly from ZIP in memory!
        """.trimIndent()

        val zipBytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("readme.txt"))
                zos.write("info".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("subs/Inception.2010.en.srt"))
                zos.write(srtText.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            bos.toByteArray()
        }

        val zipCues = SubtitleParser.parseBytes(zipBytes, "download.zip")
        assertEquals(1, zipCues.size)
        assertEquals("Extracted directly from ZIP in memory!", zipCues[0].originalText)

        val gzBytes = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { gos ->
                gos.write(srtText.toByteArray(Charsets.UTF_8))
            }
            bos.toByteArray()
        }
        val gzCues = SubtitleParser.parseBytes(gzBytes, "download.srt.gz")
        assertEquals(1, gzCues.size)
        assertEquals(10_000L, gzCues[0].startTimeMs)
    }

    @Test
    fun `findActiveCues respects positive and negative syncOffsetMs and exports bilingual SRT`() {
        val srt = """
            1
            00:00:10,000 --> 00:00:12,000
            Hello there.

            2
            00:00:15,000 --> 00:00:18,000
            General Kenobi!
        """.trimIndent()

        val cues = SubtitleParser.parseText(srt, "sw.srt").map {
            if (it.index == 1) it.copy(translatedText = "你好啊。") else it.copy(translatedText = "克诺比将军！")
        }

        // At 11.0s with 0ms offset -> cue 1 is active
        assertEquals(1, SubtitleParser.findActiveCues(cues, positionMs = 11_000L, syncOffsetMs = 0L).first().index)

        // Delay subtitle by +2000ms -> cue 1 now appears during 12.0s..14.0s
        assertTrue(SubtitleParser.findActiveCues(cues, positionMs = 11_000L, syncOffsetMs = 2_000L).isEmpty())
        assertEquals(1, SubtitleParser.findActiveCues(cues, positionMs = 13_000L, syncOffsetMs = 2_000L).first().index)

        val exported = SubtitleParser.exportToSrt(cues, includeTranslation = true)
        assertTrue(exported.contains("你好啊。\nHello there."))
        assertTrue(exported.contains("克诺比将军！\nGeneral Kenobi!"))
    }
}

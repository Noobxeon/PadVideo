package com.padvideo.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object EncodingDetector {

    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. Check explicit BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }

        // 2. Check heuristic UTF-16 without BOM (frequent null bytes at odd/even positions)
        if (bytes.size >= 16) {
            var evenZero = 0
            var oddZero = 0
            val sampleLen = minOf(bytes.size, 256)
            for (i in 0 until sampleLen) {
                if (bytes[i] == 0.toByte()) {
                    if (i % 2 == 0) evenZero++ else oddZero++
                }
            }
            if (oddZero > sampleLen / 5 && evenZero == 0) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (evenZero > sampleLen / 5 && oddZero == 0) {
                return String(bytes, Charsets.UTF_16BE)
            }
        }

        // 3. Strict UTF-8 validation
        if (isValidUtf8(bytes)) {
            return String(bytes, Charsets.UTF_8)
        }

        // 4. Fallback between GB18030 (Simplified/Traditional Chinese GBK superset) and Big5 / Windows-1252
        val gbCharset = runCatching { Charset.forName("GB18030") }
            .recoverCatching { Charset.forName("GBK") }
            .getOrDefault(Charsets.UTF_8)

        if (canDecodeCleanly(bytes, gbCharset)) {
            return String(bytes, gbCharset)
        }

        val big5Charset = runCatching { Charset.forName("Big5") }.getOrNull()
        if (big5Charset != null && canDecodeCleanly(bytes, big5Charset)) {
            return String(bytes, big5Charset)
        }

        val win1252 = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        return String(bytes, win1252)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun canDecodeCleanly(bytes: ByteArray, charset: Charset): Boolean {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            decoded.none { it == '\uFFFD' }
        } catch (_: Exception) {
            false
        }
    }
}

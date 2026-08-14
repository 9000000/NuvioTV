package com.nuvio.tv.core.player

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.min

object SubtitleCharsetDetector {

    private fun safeCharset(name: String, fallback: Charset = StandardCharsets.UTF_8): Charset {
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            fallback
        }
    }

    private val CHARSET_WIN1254: Charset = safeCharset("windows-1254")
    private val CHARSET_WIN1255: Charset = safeCharset("windows-1255")
    private val CHARSET_WIN1256: Charset = safeCharset("windows-1256")
    private val CHARSET_WIN1251: Charset = safeCharset("windows-1251")
    private val CHARSET_WIN1250: Charset = safeCharset("windows-1250")
    private val CHARSET_WIN1253: Charset = safeCharset("windows-1253")
    private val CHARSET_WIN1252: Charset = safeCharset("windows-1252", StandardCharsets.ISO_8859_1)
    private val CHARSET_GB18030: Charset = safeCharset("GB18030")
    private val CHARSET_BIG5: Charset = safeCharset("Big5")
    private val CHARSET_SHIFT_JIS: Charset = safeCharset("Shift_JIS")
    private val CHARSET_EUC_KR: Charset = safeCharset("EUC-KR")

    private const val MAX_SAMPLE_BYTES = 4096

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        if (length <= 0) return ""

        if (length >= 3 && (bytes[offset].toInt() and 0xFF) == 0xEF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xBB &&
            (bytes[offset + 2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, offset + 3, length - 3, StandardCharsets.UTF_8)
        }
        if (length >= 2 && (bytes[offset].toInt() and 0xFF) == 0xFF &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFE
        ) {
            return String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16LE)
        }
        if (length >= 2 && (bytes[offset].toInt() and 0xFF) == 0xFE &&
            (bytes[offset + 1].toInt() and 0xFF) == 0xFF
        ) {
            return String(bytes, offset + 2, length - 2, StandardCharsets.UTF_16BE)
        }

        if (isFastValidUtf8(bytes, offset, length)) {
            return String(bytes, offset, length, StandardCharsets.UTF_8)
        }

        val charset = detectUniversalCharset(bytes, offset, length)
        return String(bytes, offset, length, charset)
    }

    fun normalizeToUtf8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (isFastValidUtf8(bytes, offset, length)) {
            return if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
        }
        val charset = detectUniversalCharset(bytes, offset, length)
        return String(bytes, offset, length, charset).toByteArray(StandardCharsets.UTF_8)
    }

    private fun isFastValidUtf8(bytes: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b1 = bytes[i++].toInt() and 0xFF
            if (b1 <= 0x7F) continue
            if (b1 in 0xC2..0xDF) {
                if (i >= end || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else if (b1 in 0xE0..0xEF) {
                if (i + 1 >= end || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else if (b1 in 0xF0..0xF4) {
                if (i + 2 >= end || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80 || (bytes[i++].toInt() and 0xC0) != 0x80) return false
            } else {
                return false
            }
        }
        return true
    }

    private fun detectUniversalCharset(bytes: ByteArray, offset: Int, length: Int): Charset {
        val sampleLength = min(length, MAX_SAMPLE_BYTES)
        val end = offset + sampleLength

        var asciiLetters = 0
        var nonAscii = 0
        var consecutiveNonAscii = 0
        var maxConsecutiveNonAscii = 0

        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            if ((b in 'a'.code..'z'.code) || (b in 'A'.code..'Z'.code)) {
                asciiLetters++
                consecutiveNonAscii = 0
            } else if (b >= 0x80) {
                nonAscii++
                consecutiveNonAscii++
                if (consecutiveNonAscii > maxConsecutiveNonAscii) {
                    maxConsecutiveNonAscii = consecutiveNonAscii
                }
            } else {
                consecutiveNonAscii = 0
            }
        }

        if (nonAscii == 0) return StandardCharsets.UTF_8

        if (countBlockMatches(bytes, offset, sampleLength, CHARSET_SHIFT_JIS, Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA) >= 3) {
            return CHARSET_SHIFT_JIS
        }
        val hangulScore = countBlockMatches(bytes, offset, sampleLength, CHARSET_EUC_KR, Character.UnicodeBlock.HANGUL_SYLLABLES)
        val gbHanziScore = countBlockMatches(bytes, offset, sampleLength, CHARSET_GB18030, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)

        if (hangulScore >= 4 && hangulScore >= gbHanziScore) return CHARSET_EUC_KR

        if (gbHanziScore >= 4 && maxConsecutiveNonAscii >= 6) {
            var big5TrailCount = 0
            var i = offset
            while (i < end - 1) {
                val b1 = bytes[i].toInt() and 0xFF
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b1 in 0xA1..0xF9 && b2 in 0x40..0x7E) {
                    big5TrailCount++
                    i++
                }
                i++
            }
            if (big5TrailCount >= 2 && isCjkClean(bytes, offset, sampleLength, CHARSET_BIG5)) {
                return CHARSET_BIG5
            }
            return CHARSET_GB18030
        }

        if (asciiLetters >= nonAscii || maxConsecutiveNonAscii <= 2) {
            var turkishScore = 0
            var ceScore = 0
            for (i in offset until end) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0xF0 || b == 0xFE || b == 0xFD || b == 0xD0 || b == 0xDE || b == 0xDD) turkishScore++
                if (b == 0xB9 || b == 0xB3 || b == 0x9C || b == 0x9F || b == 0xBF || b == 0xF8 || b == 0x9A || b == 0x9E) ceScore++
            }
            if (turkishScore > 0) return CHARSET_WIN1254
            if (ceScore > 0) return CHARSET_WIN1250
            return CHARSET_WIN1252
        }

        var hebrewLetters = 0
        var arabicAlCount = 0
        var russianVowels = 0
        var greekVowels = 0
        var bytes0xC0to0xDF = 0

        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xE0..0xFA) hebrewLetters++
            if (b in 0xC0..0xDF) bytes0xC0to0xDF++
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF) russianVowels++
            if (b == 0xE1 || b == 0xEF || b == 0xE5 || b == 0xE7) greekVowels++
            if (i < end - 1 && b == 0xC7 && (bytes[i + 1].toInt() and 0xFF) == 0xE1) arabicAlCount++
        }

        if (hebrewLetters == nonAscii && hebrewLetters >= 4 && bytes0xC0to0xDF == 0) {
            return CHARSET_WIN1255
        }
        if (arabicAlCount > 0) {
            return CHARSET_WIN1256
        }
        if (russianVowels > greekVowels && russianVowels >= 3) {
            return CHARSET_WIN1251
        }
        if (greekVowels > russianVowels && greekVowels >= 3) {
            return CHARSET_WIN1253
        }

        return CHARSET_WIN1254
    }

    private fun isCjkClean(bytes: ByteArray, offset: Int, length: Int, cs: Charset): Boolean {
        return try {
            val decoder: CharsetDecoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, offset, length))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun countBlockMatches(bytes: ByteArray, offset: Int, length: Int, cs: Charset, vararg blocks: Character.UnicodeBlock): Int {
        return try {
            val decoder: CharsetDecoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val cb: CharBuffer = decoder.decode(ByteBuffer.wrap(bytes, offset, length))
            var matched = 0
            for (i in 0 until cb.length) {
                val c = cb.get(i)
                val b = Character.UnicodeBlock.of(c)
                for (target in blocks) {
                    if (b == target) {
                        matched++
                        break
                    }
                }
            }
            matched
        } catch (_: Exception) {
            0
        }
    }
}

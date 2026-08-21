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
    private val CHARSET_WIN874: Charset = safeCharset("windows-874", safeCharset("TIS-620", StandardCharsets.ISO_8859_1))
    private val CHARSET_WIN1258: Charset = safeCharset("windows-1258")
    private val CHARSET_KOI8_R: Charset = safeCharset("KOI8-R")
    private val CHARSET_GB18030: Charset = safeCharset("GB18030")
    private val CHARSET_BIG5: Charset = safeCharset("Big5")
    private val CHARSET_SHIFT_JIS: Charset = safeCharset("Shift_JIS")
    private val CHARSET_EUC_KR: Charset = safeCharset("EUC-KR")

    private const val MAX_SAMPLE_BYTES = 4096

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        languageHint: String? = null
    ): String {
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

        val hintedCharset = resolveCharsetFromLanguageHint(bytes, offset, length, languageHint)
        val charset = hintedCharset ?: detectUniversalCharset(bytes, offset, length)
        return String(bytes, offset, length, charset)
    }

    fun normalizeToUtf8(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
        languageHint: String? = null
    ): ByteArray {
        if (length <= 0) return ByteArray(0)
        if (isFastValidUtf8(bytes, offset, length)) {
            return if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
        }
        val hintedCharset = resolveCharsetFromLanguageHint(bytes, offset, length, languageHint)
        val charset = hintedCharset ?: detectUniversalCharset(bytes, offset, length)
        return String(bytes, offset, length, charset).toByteArray(StandardCharsets.UTF_8)
    }

    private fun resolveCharsetFromLanguageHint(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        languageHint: String?
    ): Charset? {
        if (languageHint.isNullOrBlank()) return null
        val normalized = languageHint.trim().lowercase()
        val lang = normalized.substringBefore('-').substringBefore('_')

        return when {
            lang == "heb" || lang == "he" || lang == "iw" || lang.startsWith("hebrew") -> CHARSET_WIN1255
            lang == "ara" || lang == "ar" || lang.startsWith("arabic") -> CHARSET_WIN1256
            lang == "ell" || lang == "el" || lang == "gre" || lang.startsWith("greek") -> CHARSET_WIN1253
            lang == "tur" || lang == "tr" || lang.startsWith("turkish") -> CHARSET_WIN1254
            lang == "rus" || lang == "ru" || lang == "ukr" || lang == "uk" || lang == "bel" || lang == "be" ||
                lang == "bul" || lang == "bg" || lang == "mkd" || lang == "mk" || lang == "srp" || lang == "sr" ||
                lang.startsWith("russian") || lang.startsWith("ukrainian") || lang.startsWith("bulgarian") ||
                lang.startsWith("serbian") || lang.startsWith("cyrillic") -> {
                if (hasKoi8Vowels(bytes, offset, length)) CHARSET_KOI8_R else CHARSET_WIN1251
            }
            lang == "tha" || lang == "th" || lang.startsWith("thai") -> CHARSET_WIN874
            lang == "vie" || lang == "vi" || lang.startsWith("vietnamese") -> CHARSET_WIN1258
            lang == "pol" || lang == "pl" || lang == "ces" || lang == "cs" || lang == "cze" || lang == "hun" ||
                lang == "hu" || lang == "slv" || lang == "sl" || lang == "hrv" || lang == "hr" || lang == "ron" ||
                lang == "ro" || lang == "rum" || lang == "slk" || lang == "sk" || lang.startsWith("polish") ||
                lang.startsWith("czech") || lang.startsWith("hungarian") || lang.startsWith("romanian") ||
                lang.startsWith("croatian") || lang.startsWith("slovak") || lang.startsWith("slovenian") -> CHARSET_WIN1250
            lang == "zho" || lang == "zh" || lang == "chi" || lang.startsWith("chinese") -> {
                if (isCjkClean(bytes, offset, length, CHARSET_BIG5)) CHARSET_BIG5 else CHARSET_GB18030
            }
            lang == "jpn" || lang == "ja" || lang.startsWith("japanese") -> CHARSET_SHIFT_JIS
            lang == "kor" || lang == "ko" || lang.startsWith("korean") -> CHARSET_EUC_KR
            else -> null
        }
    }

    private fun hasKoi8Vowels(bytes: ByteArray, offset: Int, length: Int): Boolean {
        val sampleLength = min(length, MAX_SAMPLE_BYTES)
        val end = offset + sampleLength
        var russianVowels = 0
        var koi8Vowels = 0
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF || b == 0xFB) russianVowels++
            if (b == 0xCF || b == 0xC1 || b == 0xC5 || b == 0xC9 || b == 0xD5 || b == 0xDF) koi8Vowels++
        }
        return koi8Vowels > russianVowels && koi8Vowels >= 3
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
        var startOffset = offset
        val totalEnd = offset + length
        while (startOffset < totalEnd && (bytes[startOffset].toInt() and 0xFF) < 0x80) {
            startOffset++
        }
        if (startOffset >= totalEnd) return StandardCharsets.UTF_8

        val sampleOffset = if (startOffset > offset) (startOffset - min(100, startOffset - offset)) else offset
        val sampleLength = min(totalEnd - sampleOffset, MAX_SAMPLE_BYTES)
        val end = sampleOffset + sampleLength

        var nonAscii = 0
        var consecutiveNonAscii = 0
        var maxConsecutiveNonAscii = 0

        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) {
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

        // CJK detection
        if (countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_SHIFT_JIS, Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA) >= 3) {
            return CHARSET_SHIFT_JIS
        }
        val hangulScore = countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_EUC_KR, Character.UnicodeBlock.HANGUL_SYLLABLES)
        val gbHanziScore = countBlockMatches(bytes, sampleOffset, sampleLength, CHARSET_GB18030, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)

        if (hangulScore >= 4 && hangulScore >= gbHanziScore) return CHARSET_EUC_KR

        if (gbHanziScore >= 4 && maxConsecutiveNonAscii >= 6) {
            var big5TrailCount = 0
            var i = sampleOffset
            while (i < end - 1) {
                val b1 = bytes[i].toInt() and 0xFF
                val b2 = bytes[i + 1].toInt() and 0xFF
                if (b1 in 0xA1..0xF9 && b2 in 0x40..0x7E) {
                    big5TrailCount++
                    i++
                }
                i++
            }
            if (big5TrailCount >= 2 && isCjkClean(bytes, sampleOffset, sampleLength, CHARSET_BIG5)) {
                return CHARSET_BIG5
            }
            return CHARSET_GB18030
        }

        // Statistical evaluation for non-Latin single-byte alphabets
        var thaiConsonants = 0
        var hebrewLetters = 0
        var arabicAlCount = 0
        var russianVowels = 0
        var koi8Vowels = 0
        var greekVowels = 0

        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0xA1..0xBF) thaiConsonants++
            if (b in 0xE0..0xFA) hebrewLetters++
            if (b == 0xEE || b == 0xE0 || b == 0xE5 || b == 0xE8 || b == 0xFF || b == 0xFB) russianVowels++
            if (b == 0xCF || b == 0xC1 || b == 0xC5 || b == 0xC9 || b == 0xD5 || b == 0xDF) koi8Vowels++
            if (b == 0xE1 || b == 0xEF || b == 0xE5 || b == 0xE7 || b == 0xFD || b == 0xFE) greekVowels++
            if (i < end - 1 && b == 0xC7 && (bytes[i + 1].toInt() and 0xFF) == 0xE1) arabicAlCount++
        }

        // Hebrew check: consonants (0xE0..0xFA) make up >= 50% of non-ASCII bytes
        if (hebrewLetters >= 4 && (hebrewLetters * 2 >= nonAscii)) {
            return CHARSET_WIN1255
        }

        // Thai check
        if (thaiConsonants * 4 >= nonAscii && thaiConsonants >= 4) {
            return CHARSET_WIN874
        }

        // Arabic check
        if (arabicAlCount > 0) {
            return CHARSET_WIN1256
        }

        // Cyrillic check
        if (koi8Vowels > russianVowels && koi8Vowels >= 3) {
            return CHARSET_KOI8_R
        }
        if (russianVowels > greekVowels && russianVowels >= 4 && (russianVowels * 2 >= nonAscii)) {
            return CHARSET_WIN1251
        }

        // Greek check
        if (greekVowels > russianVowels && greekVowels >= 4 && (greekVowels * 2 >= nonAscii)) {
            return CHARSET_WIN1253
        }

        // Turkish / Central European / Vietnamese / Western European
        var turkishScore = 0
        var ceScore = 0
        var vietnameseScore = 0
        for (i in sampleOffset until end) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xCC || b == 0xD2 || b == 0xF2 || b == 0xF5) vietnameseScore++
            if (b == 0xF0 || b == 0xFE || b == 0xFD || b == 0xD0 || b == 0xDE || b == 0xDD) turkishScore++
            if (b == 0xB9 || b == 0xB3 || b == 0x9C || b == 0x9F || b == 0x9A || b == 0x9E || b == 0x8C || b == 0x8F || b == 0x8A || b == 0x8E || b == 0x8D || b == 0x9D || b == 0xCF || b == 0xEF || b == 0xBE || b == 0xBA || b == 0xEC) ceScore++
        }
        if (turkishScore > ceScore && turkishScore > 0) return CHARSET_WIN1254
        if (ceScore > 0 && ceScore >= vietnameseScore) return CHARSET_WIN1250
        if (vietnameseScore >= 2) return CHARSET_WIN1258

        return CHARSET_WIN1252
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

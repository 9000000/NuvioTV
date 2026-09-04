package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import java.io.EOFException
import java.io.IOException

/**
 * Wraps an [ExtractorsFactory] to intercept and wrap stock Mp4Extractor instances with [NuvioMp4Extractor].
 */
@UnstableApi
class NuvioExtractorsFactory(
    private val delegate: ExtractorsFactory
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        if (extractor is NuvioMp4Extractor) return extractor
        val target = extractor.underlyingImplementation
        val targetName = target.javaClass.name
        val selfName = extractor.javaClass.name
        val isMp4 = targetName == "androidx.media3.extractor.mp4.Mp4Extractor" ||
                targetName.endsWith(".Mp4Extractor") ||
                selfName == "androidx.media3.extractor.mp4.Mp4Extractor" ||
                selfName.endsWith(".Mp4Extractor")
        return if (isMp4) {
            NuvioMp4Extractor(extractor)
        } else {
            extractor
        }
    }
}

@UnstableApi
fun ExtractorsFactory.withNuvioMp4Extractor(): ExtractorsFactory {
    return if (this is NuvioExtractorsFactory) this else NuvioExtractorsFactory(this)
}

/**
 * Custom wrapper around Media3's stock Mp4Extractor that optimizes non-faststart (moov at tail) MP4 playback.
 *
 * For non-faststart MP4 files:
 * 1. Captures the `moov` atom bytes into memory on the first tail read.
 * 2. As soon as `moov` is parsed (when seekMap/endTracks are emitted or moov payload is parsed),
 *    immediately triggers [ParallelRangeDataSource.releaseTailChunks] so that the ~64MB of pinned tail
 *    chunks are evicted and freed from RAM immediately without needing any fixed delay.
 * 3. On subsequent re-bufferings or seek(0) events where ExoPlayer re-requests the `moov` atom header,
 *    replays the cached `moov` bytes from in-memory [ByteArrayExtractorInput] directly, completely preventing
 *    network re-fetches to the tail of the file.
 */
@UnstableApi
class NuvioMp4Extractor(
    private val delegate: Extractor,
    internal var onMoovParsedCallback: (() -> Unit)? = null
) : Extractor {

    private var moovOffset: Long = -1L
    private var moovSize: Int = 0
    private var moovData: ByteArray? = null
    private var moovParsed: Boolean = false
    private var isFeedingCachedMoov: Boolean = false
    private var cachedMoovInput: ByteArrayExtractorInput? = null

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int) = output.track(id, type)

            override fun endTracks() {
                output.endTracks()
                notifyMoovParsed()
            }

            override fun seekMap(seekMap: SeekMap) {
                output.seekMap(seekMap)
                notifyMoovParsed()
            }
        })
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // If we are currently feeding moov from the in-memory cache
        if (isFeedingCachedMoov) {
            val memInput = cachedMoovInput
            if (memInput != null) {
                val res = delegate.read(memInput, seekPosition)
                if (res == Extractor.RESULT_SEEK) {
                    isFeedingCachedMoov = false
                    cachedMoovInput = null
                    notifyMoovParsed()
                    return Extractor.RESULT_SEEK
                }
                if (memInput.position >= moovOffset + moovSize) {
                    isFeedingCachedMoov = false
                    cachedMoovInput = null
                    notifyMoovParsed()
                }
                return res
            }
        }

        // Check if moov atom is located at the current input position
        if (moovData == null && input.length > 0L) {
            val currentPos = input.position
            val header = ByteArray(16)
            if (input.peekFully(header, 0, 8, true)) {
                input.resetPeekPosition()
                val atomSize32 = ((header[0].toInt() and 0xFF) shl 24) or
                        ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                val atomType = ((header[4].toInt() and 0xFF) shl 24) or
                        ((header[5].toInt() and 0xFF) shl 16) or
                        ((header[6].toInt() and 0xFF) shl 8) or
                        (header[7].toInt() and 0xFF)

                if (atomType == ATOM_TYPE_MOOV) {
                    var atomSize: Long = atomSize32.toLong() and 0xFFFFFFFFL
                    if (atomSize == 1L) {
                        if (input.peekFully(header, 0, 16, true)) {
                            input.resetPeekPosition()
                            atomSize = parseLong(header, 8)
                        }
                    }

                    if (atomSize in 8L..MAX_MOOV_CACHE_SIZE) {
                        moovOffset = currentPos
                        moovSize = atomSize.toInt()
                        Log.d(TAG, "Found moov atom at offset $moovOffset, size=$moovSize bytes. Caching in RAM.")
                        val data = ByteArray(moovSize)
                        input.readFully(data, 0, moovSize)
                        moovData = data

                        val memInput = ByteArrayExtractorInput(data, moovOffset, input.length)
                        cachedMoovInput = memInput
                        isFeedingCachedMoov = true

                        val memResult = delegate.read(memInput, seekPosition)
                        if (memResult == Extractor.RESULT_SEEK) {
                            isFeedingCachedMoov = false
                            cachedMoovInput = null
                            notifyMoovParsed()
                            return Extractor.RESULT_SEEK
                        }
                        return Extractor.RESULT_CONTINUE
                    } else if (atomSize > MAX_MOOV_CACHE_SIZE) {
                        // Exceptionally large moov: record offset and size so chunks can still be evicted upon parse completion
                        moovOffset = currentPos
                        moovSize = minOf(atomSize, Int.MAX_VALUE.toLong()).toInt()
                        Log.w(TAG, "Found unusually large moov atom ($atomSize bytes) at $moovOffset; passing through directly")
                    }
                }
            }
        }

        val result = delegate.read(input, seekPosition)

        // If the delegate requested a seek back to moovOffset and we have cached moov
        if (result == Extractor.RESULT_SEEK && moovData != null && seekPosition.position == moovOffset) {
            Log.d(TAG, "Delegate requested seek to moovOffset ($moovOffset); replaying from RAM cache.")
            val memInput = ByteArrayExtractorInput(moovData!!, moovOffset, input.length)
            cachedMoovInput = memInput
            isFeedingCachedMoov = true
            val memResult = delegate.read(memInput, seekPosition)
            if (memResult == Extractor.RESULT_SEEK) {
                isFeedingCachedMoov = false
                cachedMoovInput = null
                notifyMoovParsed()
                return Extractor.RESULT_SEEK
            }
            return Extractor.RESULT_CONTINUE
        }

        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        isFeedingCachedMoov = false
        cachedMoovInput = null
        delegate.seek(position, timeUs)
    }

    override fun release() {
        isFeedingCachedMoov = false
        cachedMoovInput = null
        moovData = null
        delegate.release()
    }

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private fun notifyMoovParsed() {
        if (!moovParsed) {
            moovParsed = true
            Log.d(TAG, "Moov parsed (offset=$moovOffset, size=$moovSize). Triggering immediate release of tail/moov chunks.")
            onMoovParsedCallback?.invoke() ?: ParallelRangeDataSource.releaseTailChunks(moovOffset, moovSize.toLong())
        }
    }

    private companion object {
        private const val TAG = "NuvioMp4Extractor"
        private const val ATOM_TYPE_MOOV = 0x6d6f6f76 // 'm' 'o' 'o' 'v'
        private const val MAX_MOOV_CACHE_SIZE = 96L * 1024L * 1024L // 96 MB guard

        private fun parseLong(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
            }
            return value
        }
    }
}

/**
 * In-memory [ExtractorInput] serving cached MP4 atom bytes at a specific file offset.
 */
@UnstableApi
internal class ByteArrayExtractorInput(
    private val data: ByteArray,
    private val baseOffset: Long,
    private val streamLength: Long
) : ExtractorInput {

    private var readPosition: Long = baseOffset
    private var peekPosition: Long = baseOffset

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos >= data.size) return C.RESULT_END_OF_INPUT
        val bytesToRead = minOf(length, data.size - relPos)
        System.arraycopy(data, relPos, buffer, offset, bytesToRead)
        readPosition += bytesToRead
        peekPosition = maxOf(peekPosition, readPosition)
        return bytesToRead
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos + length > data.size) {
            if (allowEndOfInput && relPos >= data.size) return false
            throw EOFException("Cannot read fully $length bytes from memory buffer (available: ${data.size - relPos})")
        }
        System.arraycopy(data, relPos, target, offset, length)
        readPosition += length
        peekPosition = maxOf(peekPosition, readPosition)
        return true
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        val relPos = (readPosition - baseOffset).toInt()
        val bytesToSkip = minOf(length, maxOf(0, data.size - relPos))
        readPosition += bytesToSkip
        peekPosition = maxOf(peekPosition, readPosition)
        return bytesToSkip
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (readPosition - baseOffset).toInt()
        if (relPos + length > data.size) {
            if (allowEndOfInput && relPos >= data.size) return false
            throw EOFException("Cannot skip fully $length bytes from memory buffer (available: ${data.size - relPos})")
        }
        readPosition += length
        peekPosition = maxOf(peekPosition, readPosition)
        return true
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        val relPos = (peekPosition - baseOffset).toInt()
        if (relPos >= data.size) return C.RESULT_END_OF_INPUT
        val bytesToPeek = minOf(length, data.size - relPos)
        System.arraycopy(data, relPos, target, offset, bytesToPeek)
        peekPosition += bytesToPeek
        return bytesToPeek
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (peekPosition - baseOffset).toInt()
        if (relPos + length > data.size) {
            if (allowEndOfInput && relPos >= data.size) return false
            throw EOFException("Cannot peek fully $length bytes from memory buffer (available: ${data.size - relPos})")
        }
        System.arraycopy(data, relPos, target, offset, length)
        peekPosition += length
        return true
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        val relPos = (peekPosition - baseOffset).toInt()
        if (relPos + length > data.size) {
            if (allowEndOfInput && relPos >= data.size) return false
            throw EOFException("Cannot advance peek position by $length bytes")
        }
        peekPosition += length
        return true
    }

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() {
        peekPosition = readPosition
    }

    override fun getPeekPosition(): Long = peekPosition

    override fun getPosition(): Long = readPosition

    override fun getLength(): Long = streamLength

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        readPosition = position
        peekPosition = position
        throw e
    }
}

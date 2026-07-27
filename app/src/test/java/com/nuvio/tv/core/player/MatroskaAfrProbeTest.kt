package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MatroskaAfrProbeTest {

    @Test
    fun `readVint decodes one-byte size with length mask removed`() {
        // 0x82 => length 1, value 2
        val bytes = byteArrayOf(0x82.toByte())
        val vint = MatroskaAfrProbe.readVint(bytes, 0, removeLengthMask = true)
        assertNotNull(vint)
        assertEquals(2L, vint!!.value)
        assertEquals(1, vint.length)
    }

    @Test
    fun `isUnknownSize detects one-byte and eight-byte unknown patterns`() {
        assertTrue(MatroskaAfrProbe.isUnknownSize(0x7F, 1))
        assertTrue(MatroskaAfrProbe.isUnknownSize(0x00FFFFFFFFFFFFFFL, 8))
        assertFalse(MatroskaAfrProbe.isUnknownSize(32, 1))
    }

    @Test
    fun `safe prefix truncates torn Cluster but keeps complete Tracks`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracksPayload = ByteArray(32) { 0x11 }
        val tracks = MatroskaAfrProbe.buildElement(MatroskaAfrProbe.ID_TRACKS, tracksPayload)
        val clusterFull = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(200) { 0x22 }
        )
        // Tear the Cluster in half so the head ends mid-element.
        val tornCluster = clusterFull.copyOf(clusterFull.size / 2)
        val segmentPayload = tracks + tornCluster
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(segmentPayload)
        val full = ebml + segment

        val layout = MatroskaAfrProbe.analyzeHeadBytes(full)
        assertNotNull(layout)
        assertTrue(layout!!.tracksCompleteInPrefix)
        assertNotNull(layout.tracksAbsoluteOffset)
        // Safe prefix must end after Tracks, before the torn Cluster.
        val expectedPrefix = ebml.size + MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_SEGMENT).size +
            MatroskaAfrProbe.encodeUnknownSizeVint8().size + tracks.size
        assertEquals(expectedPrefix.toLong(), layout.safePrefixLength)
        assertTrue(layout.safePrefixLength < full.size)
    }

    @Test
    fun `SeekHead resolves Tracks absolute offset beyond head`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )

        // SeekPosition is relative to Segment payload start.
        // We'll place Tracks at segment-relative offset 5_000_000.
        val tracksRel = 5_000_000L
        val seekId = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_ID,
            MatroskaAfrProbe.elementIdBytes(MatroskaAfrProbe.ID_TRACKS)
        )
        val seekPos = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_POSITION,
            // 4-byte big-endian position
            byteArrayOf(
                ((tracksRel shr 24) and 0xFF).toByte(),
                ((tracksRel shr 16) and 0xFF).toByte(),
                ((tracksRel shr 8) and 0xFF).toByte(),
                (tracksRel and 0xFF).toByte()
            )
        )
        val seek = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK,
            seekId + seekPos
        )
        val seekHead = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_SEEK_HEAD,
            seek
        )
        // Fake incomplete cluster after SeekHead so head has no Tracks.
        val torn = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(64) { 0x33 }
        ).copyOf(20)
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(seekHead + torn)
        val full = ebml + segment

        val layout = MatroskaAfrProbe.analyzeHeadBytes(full)
        assertNotNull(layout)
        assertFalse(layout!!.tracksCompleteInPrefix)
        val segmentDataOffset = layout.segmentDataOffset
        assertEquals(segmentDataOffset + tracksRel, layout.tracksAbsoluteOffset)
        assertTrue(layout.safePrefixLength > 0L)
        assertTrue(layout.safePrefixLength < full.size)
    }

    @Test
    fun `truncateToSafePrefix shortens torn file on disk`() {
        val ebml = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_EBML,
            byteArrayOf(0x42.toByte(), 0x86.toByte(), 0x81.toByte(), 0x01)
        )
        val tracks = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_TRACKS,
            ByteArray(16) { 1 }
        )
        val tornCluster = MatroskaAfrProbe.buildElement(
            MatroskaAfrProbe.ID_CLUSTER,
            ByteArray(100) { 2 }
        ).copyOf(40)
        val segment = MatroskaAfrProbe.buildSegmentUnknownSize(tracks + tornCluster)
        val full = ebml + segment

        val file = File.createTempFile("mkv_afr_trunc_", ".tmp")
        try {
            file.writeBytes(full)
            val layout = MatroskaAfrProbe.analyzeHead(file)
            assertNotNull(layout)
            val prefix = MatroskaAfrProbe.truncateToSafePrefix(file, layout!!)
            assertNotNull(prefix)
            assertEquals(prefix, file.length())
            assertTrue(file.length() < full.size)
            assertTrue(layout.tracksCompleteInPrefix)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `analyzeHead rejects non-EBML buffers`() {
        val mp4Like = byteArrayOf(0, 0, 0, 32, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0)
        assertNull(MatroskaAfrProbe.analyzeHeadBytes(mp4Like))
    }
}

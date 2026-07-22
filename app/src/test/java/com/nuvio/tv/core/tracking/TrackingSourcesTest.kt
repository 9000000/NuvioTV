package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingSourcesTest {
    @Test
    fun `legacy source names retain their stored meaning`() {
        assertEquals(WatchProgressSource.TRAKT, WatchProgressSource.fromStorage("TRAKT"))
        assertEquals(WatchProgressSource.NUVIO_SYNC, WatchProgressSource.fromStorage("NUVIO_SYNC"))
        assertEquals(LibrarySourceMode.TRAKT, LibrarySourceMode.valueOf("TRAKT"))
    }

    @Test
    fun `remote watch source falls back to Nuvio Sync when disconnected`() {
        assertEquals(
            WatchProgressSource.NUVIO_SYNC,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { false }
        )
        assertEquals(
            WatchProgressSource.SIMKL,
            effectiveWatchProgressSource(WatchProgressSource.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `remote library source falls back to local when disconnected`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { false }
        )
        assertEquals(
            LibrarySourceMode.SIMKL,
            effectiveLibrarySourceMode(LibrarySourceMode.SIMKL) { it == TrackingProviderId.SIMKL }
        )
    }

    @Test
    fun `local sources do not map to remote providers`() {
        assertNull(WatchProgressSource.NUVIO_SYNC.providerId)
        assertNull(LibrarySourceMode.LOCAL.providerId)
    }
}

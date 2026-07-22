package com.nuvio.tv.core.tracking

import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode

val WatchProgressSource.providerId: TrackingProviderId?
    get() = when (this) {
        WatchProgressSource.TRAKT -> TrackingProviderId.TRAKT
        WatchProgressSource.SIMKL -> TrackingProviderId.SIMKL
        WatchProgressSource.NUVIO_SYNC -> null
    }

val LibrarySourceMode.providerId: TrackingProviderId?
    get() = when (this) {
        LibrarySourceMode.LOCAL -> null
        LibrarySourceMode.TRAKT -> TrackingProviderId.TRAKT
        LibrarySourceMode.SIMKL -> TrackingProviderId.SIMKL
    }

fun effectiveWatchProgressSource(
    requestedSource: WatchProgressSource,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean
): WatchProgressSource {
    val providerId = requestedSource.providerId ?: return WatchProgressSource.NUVIO_SYNC
    return requestedSource.takeIf { isProviderAuthenticated(providerId) } ?: WatchProgressSource.NUVIO_SYNC
}

fun effectiveLibrarySourceMode(
    requestedSource: LibrarySourceMode,
    isProviderAuthenticated: (TrackingProviderId) -> Boolean
): LibrarySourceMode {
    val providerId = requestedSource.providerId ?: return LibrarySourceMode.LOCAL
    return requestedSource.takeIf { isProviderAuthenticated(providerId) } ?: LibrarySourceMode.LOCAL
}

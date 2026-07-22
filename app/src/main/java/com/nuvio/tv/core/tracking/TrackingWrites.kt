package com.nuvio.tv.core.tracking

enum class TrackingScrobbleAction(val wireValue: String) {
    START("start"),
    PAUSE("pause"),
    STOP("stop")
}

enum class TrackingSeekScrobblePolicy {
    NONE,
    STOP_AND_RESTART
}

data class TrackingScrobbleEvent(
    val media: TrackingMediaReference,
    val progressPercent: Double
)

interface TrackingScrobbler {
    val providerId: TrackingProviderId
    val seekScrobblePolicy: TrackingSeekScrobblePolicy
        get() = TrackingSeekScrobblePolicy.NONE

    suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent)
}

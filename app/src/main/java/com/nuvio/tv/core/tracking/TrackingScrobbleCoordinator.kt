package com.nuvio.tv.core.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

suspend fun dispatchTrackingScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = supervisorScope {
    scrobblers.map { scrobbler ->
        async {
            try {
                scrobbler.scrobble(action, event)
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                TrackingScrobbleFailure(scrobbler.providerId, error)
            }
        }
    }.awaitAll().filterNotNull()
}

suspend fun dispatchTrackingSeekScrobble(
    scrobblers: Collection<TrackingScrobbler>,
    action: TrackingScrobbleAction,
    event: TrackingScrobbleEvent
): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(
    scrobblers = scrobblers.filter { it.seekScrobblePolicy == TrackingSeekScrobblePolicy.STOP_AND_RESTART },
    action = action,
    event = event
)

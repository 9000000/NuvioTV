package com.nuvio.tv.core.tracking

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class TrackingScrobbleFailure(
    val providerId: TrackingProviderId,
    val cause: Throwable
)

@Singleton
class TrackingScrobbleCoordinator @Inject constructor(
    private val providerRegistry: TrackingProviderRegistry
) {
    suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatch(
        scrobblers = providerRegistry.connectedScrobblers(),
        action = action,
        event = event
    )

    suspend fun scrobbleSeek(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchSeek(
        scrobblers = providerRegistry.connectedScrobblers(),
        action = action,
        event = event
    )

    private suspend fun dispatch(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, false) }

    private suspend fun dispatchSeek(
        scrobblers: Collection<TrackingScrobbler>,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ): List<TrackingScrobbleFailure> = dispatchTrackingSeekScrobble(scrobblers, action, event)
        .also { failures -> failures.log(action, true) }

    private fun List<TrackingScrobbleFailure>.log(
        action: TrackingScrobbleAction,
        seek: Boolean
    ) {
        forEach { failure ->
            Log.w(
                "TrackingScrobble",
                "${failure.providerId.storageId} ${if (seek) "seek " else ""}scrobble ${action.wireValue} failed",
                failure.cause
            )
        }
    }
}

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

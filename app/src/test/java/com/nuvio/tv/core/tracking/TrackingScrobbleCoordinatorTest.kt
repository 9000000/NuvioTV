package com.nuvio.tv.core.tracking

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingScrobbleCoordinatorTest {
    @Test
    fun `fanout isolates a provider failure`() = runTest {
        val successful = FakeScrobbler(TrackingProviderId.TRAKT)
        val failing = FakeScrobbler(TrackingProviderId.SIMKL, IllegalStateException("offline"))
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 42.5
        )

        val failures = dispatchTrackingScrobble(
            scrobblers = listOf(successful, failing),
            action = TrackingScrobbleAction.PAUSE,
            event = event
        )

        assertEquals(1, successful.callCount)
        assertEquals(1, failing.callCount)
        assertEquals(listOf(TrackingProviderId.SIMKL), failures.map { it.providerId })
    }

    @Test
    fun `seek fanout targets only providers that restart sessions`() = runTest {
        val trakt = FakeScrobbler(
            providerId = TrackingProviderId.TRAKT,
            seekPolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART
        )
        val simkl = FakeScrobbler(TrackingProviderId.SIMKL)
        val event = TrackingScrobbleEvent(
            media = TrackingMediaReference(
                kind = TrackingMediaKind.MOVIE,
                ids = TrackingExternalIds(imdb = "tt0111161")
            ),
            progressPercent = 55.0
        )

        dispatchTrackingSeekScrobble(
            scrobblers = listOf(trakt, simkl),
            action = TrackingScrobbleAction.STOP,
            event = event
        )

        assertEquals(1, trakt.callCount)
        assertEquals(0, simkl.callCount)
    }

    private class FakeScrobbler(
        override val providerId: TrackingProviderId,
        private val failure: Throwable? = null,
        private val seekPolicy: TrackingSeekScrobblePolicy = TrackingSeekScrobblePolicy.NONE
    ) : TrackingScrobbler {
        var callCount = 0
        override val seekScrobblePolicy = seekPolicy

        override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
            callCount += 1
            failure?.let { throw it }
        }
    }
}

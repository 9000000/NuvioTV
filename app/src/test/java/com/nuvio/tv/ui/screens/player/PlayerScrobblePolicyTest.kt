package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScrobblePolicyTest {
    @Test
    fun `ready non-playing state emits pause`() {
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            trackingActionForNonPlayingState(Player.STATE_READY)
        )
    }

    @Test
    fun `ended and idle states emit stop`() {
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_ENDED)
        )
        assertEquals(
            TrackingScrobbleAction.STOP,
            trackingActionForNonPlayingState(Player.STATE_IDLE)
        )
    }

    @Test
    fun `buffering state emits no terminal scrobble`() {
        assertNull(trackingActionForNonPlayingState(Player.STATE_BUFFERING))
    }

    @Test
    fun `active pause remains eligible above watched threshold`() {
        assertTrue(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 90f))
    }

    @Test
    fun `pause requires active scrobble and meaningful progress`() {
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = false, progressPercent = 45f))
        assertFalse(shouldSendPauseScrobble(hasActiveScrobble = true, progressPercent = 0.5f))
    }
}

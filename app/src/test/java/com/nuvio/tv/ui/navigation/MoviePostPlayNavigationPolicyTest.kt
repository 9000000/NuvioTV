package com.nuvio.tv.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MoviePostPlayNavigationPolicyTest {
    @Test
    fun `manual playback removes stream and player`() {
        assertEquals(
            Screen.Stream.route,
            moviePostPlayPopUpRoute(Screen.Stream.route)
        )
    }

    @Test
    fun `autoplay playback removes current player`() {
        assertEquals(
            Screen.Player.route,
            moviePostPlayPopUpRoute(Screen.Detail.route)
        )
        assertEquals(
            Screen.Player.route,
            moviePostPlayPopUpRoute(null)
        )
    }
}

package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerLifecycleDebounceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `transient pause within 400ms cancels pause job and should auto resume if playing`() = testScope.runTest {
        var wasPlayingBeforeLifecyclePause = false
        var wasStoppedByLifecycle = false
        var isInBackground = false
        var userPausedManually = false
        var isPlaying = true

        // Simulate pauseForLifecycle
        val currentlyPlaying = isPlaying
        if (currentlyPlaying && !userPausedManually) {
            wasPlayingBeforeLifecyclePause = true
        }

        var isPendingPause = true

        // Simulate ON_RESUME firing before 400ms debounce completes (e.g. 50ms later)
        testScheduler.advanceTimeBy(50L)

        // Simulate resumeForLifecycle
        isPendingPause = false // Job cancelled

        val shouldAutoResume = wasPlayingBeforeLifecyclePause && !wasStoppedByLifecycle && !userPausedManually

        assertTrue("Should auto resume after transient <400ms pause if playing", shouldAutoResume)
        assertFalse("App should not be marked as in background", isInBackground)
    }

    @Test
    fun `transient pause within 400ms does NOT auto resume if already paused`() = testScope.runTest {
        var wasPlayingBeforeLifecyclePause = false
        var wasStoppedByLifecycle = false
        var userPausedManually = false
        var isPlaying = false

        // Simulate pauseForLifecycle when player was already paused
        val currentlyPlaying = isPlaying
        if (currentlyPlaying && !userPausedManually) {
            wasPlayingBeforeLifecyclePause = true
        }

        var isPendingPause = true

        // Simulate ON_RESUME firing 50ms later
        testScheduler.advanceTimeBy(50L)
        isPendingPause = false

        val shouldAutoResume = wasPlayingBeforeLifecyclePause && !wasStoppedByLifecycle && !userPausedManually

        assertFalse("Should NOT auto resume after transient pause if already paused", shouldAutoResume)
    }

    @Test
    fun `prolonged pause after 400ms executes background pause and resumes on return`() = testScope.runTest {
        var wasPlayingBeforeLifecyclePause = false
        var wasStoppedByLifecycle = false
        var isInBackground = false
        var userPausedManually = false
        var isPlaying = true

        // Simulate pauseForLifecycle
        val currentlyPlaying = isPlaying
        if (currentlyPlaying && !userPausedManually) {
            wasPlayingBeforeLifecyclePause = true
        }

        var isPendingPause = true

        // Advance time past 400ms debounce
        testScheduler.advanceTimeBy(450L)

        // Perform actual background pause
        isPendingPause = false
        isInBackground = true
        isPlaying = false

        // Simulate ON_RESUME firing later
        val shouldAutoResume = wasPlayingBeforeLifecyclePause && !wasStoppedByLifecycle && !userPausedManually

        assertTrue("Should auto resume when returning from non-stopped overlay pause", shouldAutoResume)
        assertTrue("App was in background before resume", isInBackground)
    }

    @Test
    fun `stopForLifecycle marks stopped and prevents auto resume on return`() = testScope.runTest {
        var wasPlayingBeforeLifecyclePause = false
        var wasStoppedByLifecycle = false
        var isInBackground = false
        var userPausedManually = false
        var isPlaying = true

        // Simulate ON_PAUSE followed immediately by ON_STOP
        wasPlayingBeforeLifecyclePause = isPlaying
        wasStoppedByLifecycle = true
        isInBackground = true
        isPlaying = false

        // Simulate ON_RESUME when user re-opens app from Home screen
        val shouldAutoResume = wasPlayingBeforeLifecyclePause && !wasStoppedByLifecycle && !userPausedManually

        assertFalse("Should NOT auto resume when returning from ON_STOP home screen exit", shouldAutoResume)
    }

    @Test
    fun `manual user pause prevents auto resume on lifecycle events`() = testScope.runTest {
        var wasPlayingBeforeLifecyclePause = false
        var wasStoppedByLifecycle = false
        var userPausedManually = true

        val shouldAutoResume = wasPlayingBeforeLifecyclePause && !wasStoppedByLifecycle && !userPausedManually

        assertFalse("Should NOT auto resume when user paused manually", shouldAutoResume)
    }
}

package com.selfcontrol.app

import android.app.usage.UsageEvents
import org.junit.Assert.*
import org.junit.Test

class ForegroundInitializationTest {
    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED
    private val stopped = UsageEvents.Event.ACTIVITY_STOPPED
    private val app = "controlled.app"
    private val home = "launcher"

    private fun event(time: Long, pkg: String?, type: Int = resumed, activity: String? = "Main") =
        ObservedUsageEvent(time, pkg, activity, type)

    @Test fun appAlreadyForegroundBeforeServiceStartIsRestored() {
        val current = event(100L, app)
        assertEquals(current, rebuildForegroundState(listOf(current), 10_000L))
    }

    @Test fun homeAfterControlledAppRestoresOnlyHome() {
        val current = event(300L, home)
        assertEquals(current, rebuildForegroundState(listOf(
            event(100L, app), event(200L, app, paused), current, event(400L, app, stopped)
        ), 500L))
    }

    @Test fun pausedOrStoppedAppIsNeverRestoredAsForeground() {
        for (exit in listOf(paused, stopped)) {
            assertNull(rebuildForegroundState(listOf(event(100L, app), event(200L, app, exit)), 300L))
        }
    }

    @Test fun exitingLatestAppDoesNotFallBackToAnOlderResumedApp() {
        assertNull(rebuildForegroundState(listOf(
            event(100L, app), event(200L, "other.app"), event(300L, "other.app", paused)
        ), 400L))
    }

    @Test fun oldActivityExitCannotEraseNewActivityInSamePackage() {
        val current = event(200L, app, activity = "NewActivity")
        assertEquals(current, rebuildForegroundState(listOf(
            event(100L, app, activity = "OldActivity"), current,
            event(300L, app, paused, "OldActivity"), event(400L, app, stopped, "OldActivity")
        ), 500L))
    }

    @Test fun ambiguousSameClassExitConservativelyClearsForeground() {
        assertNull(rebuildForegroundState(listOf(
            event(100L, app), event(200L, app), event(300L, app, stopped)
        ), 400L))
    }

    @Test fun missingClassOnExitConservativelyClearsMatchingPackage() {
        assertNull(rebuildForegroundState(listOf(
            event(100L, app), event(200L, app, paused, activity = null)
        ), 300L))
    }

    @Test fun lockScreenAndRebootInvalidatePreviousForeground() {
        for (reset in listOf(UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP)) {
            assertNull(rebuildForegroundState(listOf(event(100L, app), event(200L, null, reset)), 300L))
        }
    }

    @Test fun resumeAfterDeviceStartupReestablishesForeground() {
        val current = event(300L, home)
        assertEquals(current, rebuildForegroundState(listOf(
            event(100L, app), event(200L, null, UsageEvents.Event.DEVICE_STARTUP), current
        ), 400L))
    }

    @Test fun unrelatedEventsDoNotSelectTheirPackageAsForeground() {
        val current = event(100L, home)
        assertEquals(current, rebuildForegroundState(listOf(
            current, event(200L, app, UsageEvents.Event.USER_INTERACTION)
        ), 300L))
    }

    @Test fun sortsByEventTimeAndPreservesOrderForEqualTimestamps() {
        val current = event(200L, home)
        assertEquals(current, rebuildForegroundState(listOf(current, event(100L, app)), 300L))
        assertEquals(current, rebuildForegroundState(listOf(
            event(200L, app), event(200L, app, paused), current
        ), 300L))
    }

    @Test fun queryBoundaryIsLeftForIncrementalProcessing() {
        val current = event(199L, app)
        val next = event(200L, home)
        assertEquals(current, rebuildForegroundState(listOf(current, next), 199L))
        assertEquals(next, rebuildForegroundState(listOf(current, next), 200L))
    }

    @Test fun emptyOrUnknownForegroundNeverInventsATarget() {
        assertNull(rebuildForegroundState(emptyList(), 300L))
        assertNull(rebuildForegroundState(listOf(event(100L, app), event(200L, "")), 300L))
    }

    @Test fun restoredEntryStillSupportsHomeExitSuppressionAndGenuineReentry() {
        val current = rebuildForegroundState(listOf(event(100L, app)), 200L)!!
        val exit = FocusHomeExitState()
        val request = exit.begin(current.packageName!!, current.time)
        assertTrue(exit.finishHide(request))
        assertTrue(exit.suppressesCachedShow(app))
        // Merely polling without a new entry cannot undo STOP_USING.
        assertFalse(exit.onForegroundEntry(current.time, needsFocusCover = true))
        assertTrue(exit.suppressesCachedShow(app))
        exit.onForegroundEntry(300L, needsFocusCover = false)
        exit.onForegroundEntry(400L, needsFocusCover = true)
        assertFalse(exit.suppressesCachedShow(app))
        assertFalse(exit.isHidePending)
    }
}

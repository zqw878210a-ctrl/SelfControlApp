package com.selfcontrol.app

import org.junit.Assert.*
import org.junit.Test

class FocusHomeExitStateTest {
    private val state = FocusHomeExitState()
    private val target = "controlled.app"
    private val entryTime = 1_000L

    @Test fun delayedHideDoesNotAuthorizeReshowFromUnchangedForegroundCache() {
        val request = state.begin(target, entryTime)
        assertTrue(state.isHidePending)
        assertTrue(state.finishHide(request))
        assertFalse(state.isHidePending)
        // Empty batches/unrelated UsageEvents never call onForegroundEntry.
        // Repeated polls and async access decisions must continue suppressing A.
        repeat(5) { assertTrue(state.suppressesCachedShow(target)) }
        assertFalse(state.suppressesCachedShow("other.controlled.app"))
    }

    @Test fun launcherArrivalThenTrueReentryAllowsImmediateFocusCover() {
        val request = state.begin(target, entryTime)
        state.finishHide(request)
        assertTrue(state.suppressesCachedShow(target))
        assertFalse(state.onForegroundEntry(entryTime + 1L, needsFocusCover = false))
        assertFalse(state.suppressesCachedShow(target))
        state.onForegroundEntry(entryTime + 2L, needsFocusCover = true)
        assertFalse(state.suppressesCachedShow(target))
        assertFalse(state.isHidePending)
    }

    @Test fun sameAppNewResumedClearsExitEvenWithoutLauncherEvent() {
        val request = state.begin(target, entryTime)
        state.finishHide(request)
        state.onForegroundEntry(entryTime + 1L, needsFocusCover = true)
        assertFalse(state.suppressesCachedShow(target))
        assertFalse(state.isHidePending)
    }

    @Test fun repeatedOrOlderForegroundEventDoesNotReleaseOldCache() {
        val request = state.begin(target, entryTime)
        for (time in listOf(entryTime - 1L, entryTime)) {
            assertFalse(state.onForegroundEntry(time, needsFocusCover = true))
            assertTrue(state.suppressesCachedShow(target))
            assertTrue(state.isHidePending)
        }
        state.finishHide(request)
        assertFalse(state.onForegroundEntry(entryTime, needsFocusCover = true))
        assertTrue(state.suppressesCachedShow(target))
    }

    @Test fun trueReentryBeforeDelayRebindsCoverAndInvalidatesOldHide() {
        val oldRequest = state.begin(target, entryTime)
        assertTrue(state.onForegroundEntry(entryTime + 1L, needsFocusCover = true))
        assertFalse(state.isHidePending)
        assertFalse(state.suppressesCachedShow(target))
        assertFalse(state.finishHide(oldRequest))
    }

    @Test fun launcherBeforeDelayKeepsOriginalHideTiming() {
        val request = state.begin(target, entryTime)
        assertFalse(state.onForegroundEntry(entryTime + 1L, needsFocusCover = false))
        assertTrue(state.isHidePending)
        assertFalse(state.suppressesCachedShow(target))
        assertTrue(state.finishHide(request))
        assertFalse(state.isHidePending)
    }

    @Test fun launcherThenFastReentryStillInvalidatesPendingHide() {
        val request = state.begin(target, entryTime)
        state.onForegroundEntry(entryTime + 1L, needsFocusCover = false)
        assertTrue(state.onForegroundEntry(entryTime + 2L, needsFocusCover = true))
        assertFalse(state.finishHide(request))
        assertFalse(state.suppressesCachedShow(target))
    }

    @Test fun anotherControlledAppIsNotBlockedByPreviousExit() {
        val request = state.begin(target, entryTime)
        val otherTarget = "other.controlled.app"
        assertFalse(state.suppressesCachedShow(otherTarget))
        // needsFocusCover comes from the new event package's enabled config.
        assertTrue(state.onForegroundEntry(entryTime + 1L, needsFocusCover = true))
        assertFalse(state.isHidePending)
        assertFalse(state.suppressesCachedShow(otherTarget))
        assertFalse(state.finishHide(request))
    }

    @Test fun oldCallbackCannotConsumeANewerStopRequestEvenForSamePackage() {
        val oldRequest = state.begin(target, entryTime)
        state.onForegroundEntry(entryTime + 1L, needsFocusCover = true)
        val newRequest = state.begin(target, entryTime + 1L)
        assertFalse(state.finishHide(oldRequest))
        assertTrue(state.isHidePending)
        assertTrue(state.suppressesCachedShow(target))
        assertTrue(state.finishHide(newRequest))
        assertFalse(state.finishHide(newRequest))
        assertTrue(state.suppressesCachedShow(target))
    }

    @Test fun serviceResetInvalidatesOldCallbacksAndExitState() {
        val request = state.begin(target, entryTime)
        state.reset()
        assertFalse(state.finishHide(request))
        assertFalse(state.suppressesCachedShow(target))
        assertFalse(state.isHidePending)
    }
}

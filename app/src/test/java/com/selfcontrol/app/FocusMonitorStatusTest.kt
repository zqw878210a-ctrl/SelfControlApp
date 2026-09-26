package com.selfcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMonitorStatusTest {
    private val start = 20_000L
    private val healthy = MonitorHealthSnapshot(true, start, true, true, true, start,
        MonitorOverlayState.NOT_REQUIRED)

    private fun status(
        health: MonitorHealthSnapshot = healthy,
        running: Boolean = true,
        usage: Boolean = true,
        overlay: Boolean = true,
        now: Long = start
    ) = focusMonitorStatus(usage, overlay, running,
        health.unavailableReason(running, now) == null, health, now, start)

    @Test fun freshHealthyMonitorIsNormal() {
        assertEquals(FocusMonitorStatus.NORMAL, status())
    }

    @Test fun missingPermissionsTakePrecedenceOverCachedHealthyOrStoppedService() {
        assertEquals(FocusMonitorStatus.NEEDS_SETTINGS, status(usage = false))
        assertEquals(FocusMonitorStatus.NEEDS_SETTINGS, status(overlay = false))
        assertEquals(FocusMonitorStatus.NEEDS_SETTINGS, status(running = false, usage = false, overlay = false))
    }

    @Test fun absentServiceIsUnavailableRatherThanChecking() {
        assertEquals(FocusMonitorStatus.UNAVAILABLE, status(running = false))
        assertEquals(FocusMonitorStatus.UNAVAILABLE, status(MonitorHealthSnapshot(), running = false))
    }

    @Test fun startupCheckingCannotContinueIndefinitely() {
        val starting = MonitorHealthSnapshot()
        assertEquals(FocusMonitorStatus.CHECKING, status(starting))
        assertEquals(FocusMonitorStatus.CHECKING, status(starting, now = start + MONITOR_HEARTBEAT_TIMEOUT_MS - 1L))
        assertEquals(FocusMonitorStatus.UNAVAILABLE, status(starting, now = start + MONITOR_HEARTBEAT_TIMEOUT_MS))
    }

    @Test fun failedOrStaleChecksAndOverlayFailuresAreUnavailable() {
        for (failed in listOf(healthy.copy(pollSucceeded = false), healthy.copy(nextPollPosted = false),
            healthy.copy(foregroundReadSucceeded = false), healthy.copy(overlay = MonitorOverlayState.FAILED),
            healthy.copy(overlay = MonitorOverlayState.ATTACHING))) {
            assertEquals(FocusMonitorStatus.UNAVAILABLE, status(failed))
        }
        assertEquals(FocusMonitorStatus.UNAVAILABLE, status(now = start + MONITOR_HEARTBEAT_TIMEOUT_MS))
    }

    @Test fun normalReturnsOnlyWithHealthyEvidenceAfterRecoveryOrPermissionReturn() {
        assertEquals(FocusMonitorStatus.NEEDS_SETTINGS, status(usage = false))
        assertEquals(FocusMonitorStatus.UNAVAILABLE, status(healthy.copy(foregroundReadSucceeded = false)))
        assertEquals(FocusMonitorStatus.NORMAL, status())
    }
}

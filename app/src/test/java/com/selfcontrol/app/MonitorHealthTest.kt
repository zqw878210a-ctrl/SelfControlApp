package com.selfcontrol.app

import org.junit.Assert.*
import org.junit.Test

class MonitorHealthTest {
    private val now = 20_000L
    private val healthy = MonitorHealthSnapshot(
        initialized = true, lastPollElapsed = now, pollSucceeded = true, nextPollPosted = true,
        foregroundReadSucceeded = true, lastForegroundReadElapsed = now,
        overlay = MonitorOverlayState.NOT_REQUIRED
    )

    @Test fun serviceExistenceDoesNotProveInitializationOrACompletedPoll() {
        assertEquals("SERVICE_MISSING", healthy.unavailableReason(false, now))
        assertEquals("INITIALIZING", MonitorHealthSnapshot().unavailableReason(true, now))
        assertEquals("POLL_NOT_STARTED", healthy.copy(lastPollElapsed = null).unavailableReason(true, now))
    }

    @Test fun previouslyHealthyServiceBecomesUnavailableAtHeartbeatDeadline() {
        assertNull(healthy.unavailableReason(true, now + MONITOR_HEARTBEAT_TIMEOUT_MS - 1L))
        assertEquals("HEARTBEAT_STALE", healthy.unavailableReason(true, now + MONITOR_HEARTBEAT_TIMEOUT_MS))
        assertEquals("HEARTBEAT_STALE", healthy.unavailableReason(true, now - 1L))
    }

    @Test fun failedPollOrBrokenRescheduleIsUnavailableDespiteFreshForegroundRead() {
        assertEquals("POLL_FAILED", healthy.copy(pollSucceeded = false).unavailableReason(true, now))
        assertEquals("POLL_FAILED", healthy.copy(nextPollPosted = false).unavailableReason(true, now))
    }

    @Test fun foregroundFailureCannotReuseAnEarlierSuccessfulQuery() {
        assertEquals("FOREGROUND_UNAVAILABLE",
            healthy.copy(foregroundReadSucceeded = false).unavailableReason(true, now))
        assertEquals("FOREGROUND_UNAVAILABLE",
            healthy.copy(lastForegroundReadElapsed = null).unavailableReason(true, now))
        assertEquals("FOREGROUND_UNAVAILABLE", healthy.copy(
            lastForegroundReadElapsed = now - MONITOR_HEARTBEAT_TIMEOUT_MS).unavailableReason(true, now))
    }

    @Test fun noNewAppEventAndNoRequiredCoverAreNotFailures() {
        // A completed empty UsageEvents query counts as fresh evidence; no app event timestamp is required.
        assertNull(healthy.unavailableReason(true, now))
    }

    @Test fun permissionOrWindowFailureAndPendingAttachAreNotReportedAsHealthy() {
        for (state in listOf(MonitorOverlayState.UNVERIFIED, MonitorOverlayState.PERMISSION_MISSING,
            MonitorOverlayState.ATTACHING, MonitorOverlayState.FAILED)) {
            assertEquals("OVERLAY_$state", healthy.copy(overlay = state).unavailableReason(true, now))
        }
        assertNull(healthy.copy(overlay = MonitorOverlayState.ATTACHED).unavailableReason(true, now))
    }

    @Test fun existingButUnhealthyServiceCanRecoverWithoutAStartStorm() {
        val retry = MonitorStartRetryState()
        val failed = healthy.copy(overlay = MonitorOverlayState.FAILED)
        fun effective(snapshot: MonitorHealthSnapshot) = snapshot.unavailableReason(true, now) == null
        assertFalse(retry.claimStart(effective(healthy), now))
        assertTrue(retry.claimStart(effective(failed), now))
        assertFalse(retry.claimStart(effective(failed), now + MONITOR_START_RETRY_MS - 1L))
        assertTrue(retry.claimStart(effective(failed), now + MONITOR_START_RETRY_MS))
        assertFalse(retry.claimStart(effective(healthy), now + MONITOR_START_RETRY_MS * 2))
    }

    @Test fun refreshedEvidenceRestoresAvailabilityAfterHeartbeatLoss() {
        val later = now + MONITOR_HEARTBEAT_TIMEOUT_MS
        assertNotNull(healthy.unavailableReason(true, later))
        assertNull(healthy.copy(lastPollElapsed = later, lastForegroundReadElapsed = later)
            .unavailableReason(true, later))
    }
}

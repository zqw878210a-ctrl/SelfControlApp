package com.selfcontrol.app

import android.app.Service
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the existing Android stubs; OS lifecycle/restart delivery needs device verification. */
class MonitorServiceRestartTest {
    @Test fun failedOrUndeliveredStartCanRetryAfterTimeout() {
        val retry = MonitorStartRetryState()
        assertTrue(retry.claimStart(false, 100L))
        assertFalse(retry.claimStart(false, 100L + MONITOR_START_RETRY_MS - 1L))
        assertTrue(retry.claimStart(false, 100L + MONITOR_START_RETRY_MS))
    }

    @Test fun activityAndRecoveryCallbackCannotDispatchDuplicateStarts() {
        val retry = MonitorStartRetryState()
        assertTrue(retry.claimStart(false, 100L))
        repeat(10) { assertFalse(retry.claimStart(false, 100L)) }
        assertTrue(retry.claimStart(false, 100L + MONITOR_START_RETRY_MS))
        assertFalse(retry.claimStart(false, 100L + MONITOR_START_RETRY_MS))
    }

    @Test fun runningServiceIsNotRestartedButStoppedServiceCanRecover() {
        val retry = MonitorStartRetryState()
        assertTrue(retry.claimStart(false, 100L))
        assertFalse(retry.claimStart(true, 10_000L))
        assertTrue(retry.claimStart(false, 10_001L))
    }

    @Test fun repeatedInitializationFailuresRemainRateLimitedAndRecoverable() {
        val retry = MonitorStartRetryState()
        repeat(4) { attempt ->
            val now = attempt * MONITOR_START_RETRY_MS
            assertTrue(retry.claimStart(false, now))
            assertFalse(retry.claimStart(true, now + 1L))
            assertFalse(retry.claimStart(false, now + 2L))
        }
    }

    @Test fun nullIntentRestartRequestsContinuedMonitoring() {
        assertEquals(Service.START_STICKY, MonitorService().onStartCommand(null, 0, 1))
    }

    @Test fun ordinaryStartRequestsContinuedMonitoring() {
        assertEquals(Service.START_STICKY, MonitorService().onStartCommand(Intent(), 0, 1))
    }

    @Test fun repeatedStartAndNullIntentKeepStickyPolicy() {
        val service = MonitorService()
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 2))
        assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 3))
    }
}

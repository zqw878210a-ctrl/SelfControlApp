package com.selfcontrol.app.quota

import com.selfcontrol.app.data.ControlledAppConfig
import org.junit.Assert.*
import org.junit.Test

class QuotaConfigRefreshTest {
    private val config = ControlledAppConfig("controlled.app", "App", true, true, 30)
    private val now = 1_800_000_000_000L

    @Test fun raisingAndLoweringQuotaDiscardOldDecisionBeforeRecalculation() {
        for ((oldLimit, newLimit) in listOf(30 to 60, 60 to 30)) {
            val store = QuotaRuntimeStateStore()
            val usage = 40 * 60_000L
            val old = config.copy(dailyQuotaMinutes = oldLimit)
            val new = old.copy(dailyQuotaMinutes = newLimit)
            val oldState = calculateDailyQuotaState(usage, oldLimit)
            store.update(old.packageName, oldState, usage, oldLimit, now)

            assertEquals(setOf(old.packageName), store.invalidateChangedConfigs(
                mapOf(old.packageName to old), mapOf(new.packageName to new)))
            assertNull(store.getFreshSnapshot(old.packageName, now))

            val newState = calculateDailyQuotaState(usage, newLimit)
            store.update(new.packageName, newState, usage, newLimit, now)
            assertNotEquals(oldState, store.getFreshSnapshot(new.packageName, now)!!.state)
            assertEquals(newLimit, store.getFreshSnapshot(new.packageName, now)!!.dailyQuotaMinutes)
        }
    }

    @Test fun modeEligibilityQuotaRemovalAndDeletionInvalidateOnlyAffectedApp() {
        val other = config.copy(packageName = "other.app")
        for (replacement in listOf(config.copy(quotaEnforcementMode = "STRICT"),
            config.copy(enabled = false), config.copy(intentGateEnabled = false),
            config.copy(dailyQuotaMinutes = null), null)) {
            val store = QuotaRuntimeStateStore()
            store.update(config.packageName, DailyQuotaState.EXHAUSTED, 40 * 60_000L, 30, now)
            val retained = store.update(other.packageName, DailyQuotaState.WITHIN_QUOTA, 0, 30, now)
            val previous = mapOf(config.packageName to config, other.packageName to other)
            val current = if (replacement == null) mapOf(other.packageName to other)
                else previous + (config.packageName to replacement)

            assertEquals(setOf(config.packageName), store.invalidateChangedConfigs(previous, current))
            assertNull(store.getFreshSnapshot(config.packageName, now))
            assertSame(retained, store.getFreshSnapshot(other.packageName, now))
        }
    }

    @Test fun repeatedRefreshAndDisplayNameChangePreserveValidSnapshot() {
        val store = QuotaRuntimeStateStore()
        val snapshot = store.update(config.packageName, DailyQuotaState.EXHAUSTED, 40 * 60_000L, 30, now)
        val previous = mapOf(config.packageName to config)
        assertTrue(store.invalidateChangedConfigs(previous, previous).isEmpty())
        assertTrue(store.invalidateChangedConfigs(previous,
            mapOf(config.packageName to config.copy(displayName = "Renamed"))).isEmpty())
        assertSame(snapshot, store.getFreshSnapshot(config.packageName, now))
    }
}

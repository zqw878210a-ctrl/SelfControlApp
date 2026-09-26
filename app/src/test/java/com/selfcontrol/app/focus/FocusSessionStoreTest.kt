package com.selfcontrol.app.focus

import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FocusSessionStoreTest {
    private val preferences = TestPreferences()
    private val store = FocusSessionStore(preferences)
    private val start = 1_000_000L
    private val end = start + 60_000L

    @Test fun monitorProbeReadsLegacyFocusWithoutMigratingOrSettlingIt() {
        seedLegacyState()
        val before = preferences.all
        assertTrue(store.hasActiveSessionForMonitoring(end - 1L))
        assertFalse(store.hasActiveSessionForMonitoring(end))
        assertEquals(before, preferences.all)
    }

    @Test fun monitorProbeRejectsMissingAndInvalidFocus() {
        assertFalse(store.hasActiveSessionForMonitoring(start))
        preferences.edit().putLong("started_at_millis", start).putLong("ends_at_millis", start).commit()
        assertFalse(store.hasActiveSessionForMonitoring(start))
    }

    @Test(expected = IllegalStateException::class)
    fun monitorProbeDoesNotTreatReadFailureAsAnEndedFocus() {
        preferences.failRead = true
        store.hasActiveSessionForMonitoring(start)
    }

    @Test fun confirmedEarlyEndPersistsAllFieldsAndClearsActiveState() {
        assertEquals(FocusSessionStartStatus.STARTED, store.startFocusSession(1, start).status)
        val result = store.endFocusSessionEarly(start + 10_000L)

        assertEquals(FocusSessionEndStatus.ENDED, result.status)
        val entry = result.historyEntry!!
        assertTrue(entry.sessionId.isNotBlank())
        assertEquals(start, entry.startedAtMillis)
        assertEquals(end, entry.endsAtMillis)
        assertEquals(start + 10_000L, entry.endedAtMillis)
        assertEquals(FocusSessionOutcome.EARLY_ENDED, entry.outcome)
        val restored = FocusSessionStore(preferences.restart())
        assertNull(restored.getActiveSession(start + 10_000L))
        assertEquals(listOf(entry), restored.getSessionHistory(start + 10_000L))
        assertActiveKeysAbsent(preferences)
    }

    @Test fun lateReadCompletesAtPlannedEndRatherThanReadTime() {
        store.startFocusSession(1, start)
        assertNull(store.getActiveSession(end + 100_000L))
        val entry = store.getSessionHistory(end + 100_000L).single()
        assertEquals(FocusSessionOutcome.COMPLETED, entry.outcome)
        assertEquals(end, entry.endedAtMillis)
        assertEquals(listOf(entry), FocusSessionStore(preferences.restart()).getSessionHistory(end))
        assertActiveKeysAbsent(preferences)
    }

    @Test fun earlyEndAtOrAfterDeadlineIsCompleted() {
        for (now in listOf(end, end + 1L)) {
            val fresh = FocusSessionStore(TestPreferences())
            fresh.startFocusSession(1, start)
            val result = fresh.endFocusSessionEarly(now)
            assertEquals(FocusSessionEndStatus.ENDED, result.status)
            assertEquals(FocusSessionOutcome.COMPLETED, result.historyEntry!!.outcome)
            assertEquals(end, result.historyEntry.endedAtMillis)
        }
    }

    @Test fun remainingTimeReadAlsoSettlesAtDeadline() {
        store.startFocusSession(1, start)
        assertEquals(1L, store.getRemainingMillis(end - 1L))
        assertTrue(store.getSessionHistory(end - 1L).isEmpty())
        assertEquals(0L, store.getRemainingMillis(end))
        assertEquals(FocusSessionOutcome.COMPLETED, store.getSessionHistory(end).single().outcome)
    }

    @Test fun repeatedSettlementAcrossInstancesHasOneFinalResult() {
        store.startFocusSession(1, start)
        val entry = store.endFocusSessionEarly(end - 1L).historyEntry!!
        val other = FocusSessionStore(preferences)
        repeat(3) {
            assertEquals(FocusSessionEndStatus.NO_ACTIVE_SESSION, other.endFocusSessionEarly(end).status)
            assertNull(store.getActiveSession(end))
            assertEquals(listOf(entry), other.getSessionHistory(end))
        }
        assertEquals(FocusSessionOutcome.EARLY_ENDED, entry.outcome)
    }

    @Test fun expiryWinningBeforeEarlyEndKeepsCompletedResult() {
        store.startFocusSession(1, start)
        store.getActiveSession(end)
        assertEquals(FocusSessionEndStatus.NO_ACTIVE_SESSION, store.endFocusSessionEarly(end - 1L).status)
        assertEquals(FocusSessionOutcome.COMPLETED, store.getSessionHistory(end).single().outcome)
    }

    @Test fun concurrentDeadlineReadAndConfirmedEndSettleOnce() {
        store.startFocusSession(1, start)
        val other = FocusSessionStore(preferences)
        concurrently(
            { store.getActiveSession(end) },
            { other.endFocusSessionEarly(end) },
            { store.getSessionHistory(end) }
        )
        val entry = store.getSessionHistory(end).single()
        assertEquals(FocusSessionOutcome.COMPLETED, entry.outcome)
        assertEquals(end, entry.endedAtMillis)
        assertNull(other.getActiveSession(end))
        assertEquals(listOf(entry), FocusSessionStore(preferences.restart()).getSessionHistory(end))
    }

    @Test fun concurrentEarlyEndRequestsSettleOnce() {
        store.startFocusSession(1, start)
        val other = FocusSessionStore(preferences)
        val results = concurrently(
            { store.endFocusSessionEarly(end - 1L) },
            { other.endFocusSessionEarly(end - 1L) }
        ).map { it as FocusSessionEndResult }
        assertEquals(1, results.count { it.status == FocusSessionEndStatus.ENDED })
        assertEquals(1, results.count { it.status == FocusSessionEndStatus.NO_ACTIVE_SESSION })
        assertEquals(FocusSessionOutcome.EARLY_ENDED, store.getSessionHistory(end).single().outcome)
    }

    @Test fun activeRestartRetainsIdentityAndOriginalTimestamps() {
        val original = store.startFocusSession(1, start).snapshot
        val id = preferences.getString("session_id", null)
        val restartedPreferences = preferences.restart()
        val restarted = FocusSessionStore(restartedPreferences)
        assertEquals(original, restarted.getActiveSession(start + 1L))
        assertEquals(id, restartedPreferences.getString("session_id", null))
        assertEquals(id, restarted.endFocusSessionEarly(start + 2L).historyEntry!!.sessionId)
    }

    @Test fun legacyActiveStateGetsStableIdWithoutChangingDeadline() {
        seedLegacyState()
        val snapshot = store.getActiveSession(start + 1L)
        assertEquals(FocusSessionSnapshot(start, end), snapshot)
        val id = preferences.getString("session_id", null)
        assertNotNull(id)
        val restarted = FocusSessionStore(preferences.restart())
        val duplicate = restarted.startFocusSession(1440, start + 2L)
        assertEquals(FocusSessionStartStatus.ALREADY_ACTIVE, duplicate.status)
        assertEquals(snapshot, duplicate.snapshot)
        assertEquals(id, restarted.endFocusSessionEarly(start + 3L).historyEntry!!.sessionId)
    }

    @Test fun legacyExpiredStateCompletesOnHistoryReadAfterRestart() {
        seedLegacyState()
        val restoredPreferences = preferences.restart()
        val restored = FocusSessionStore(restoredPreferences)
        val entry = restored.getSessionHistory(end + 500L).single()
        assertEquals(start, entry.startedAtMillis)
        assertEquals(end, entry.endsAtMillis)
        assertEquals(end, entry.endedAtMillis)
        assertEquals(FocusSessionOutcome.COMPLETED, entry.outcome)
        assertEquals(listOf(entry), FocusSessionStore(restoredPreferences.restart()).getSessionHistory(end))
    }

    @Test fun repeatedStartDuringActivityNeverOverwritesDeadlineOrId() {
        val original = store.startFocusSession(1, start).snapshot
        val before = preferences.all
        val result = FocusSessionStore(preferences).startFocusSession(1440, end - 1L)
        assertEquals(FocusSessionStartStatus.ALREADY_ACTIVE, result.status)
        assertEquals(original, result.snapshot)
        assertEquals(before, preferences.all)
    }

    @Test fun concurrentStartsCreateOnlyOneGlobalSession() {
        val other = FocusSessionStore(preferences)
        val results = concurrently(
            { store.startFocusSession(1, start) },
            { other.startFocusSession(1440, start) }
        ).map { it as FocusSessionStartResult }
        assertEquals(1, results.count { it.status == FocusSessionStartStatus.STARTED })
        assertEquals(1, results.count { it.status == FocusSessionStartStatus.ALREADY_ACTIVE })
        assertEquals(results[0].snapshot, results[1].snapshot)
    }

    @Test fun startingAtExpiryCompletesPreviousSessionAndPreservesHistory() {
        store.startFocusSession(1, start)
        val firstId = preferences.getString("session_id", null)
        assertEquals(FocusSessionStartStatus.STARTED, store.startFocusSession(1, end).status)
        store.endFocusSessionEarly(end + 1L)
        val history = store.getSessionHistory(end + 1L)
        assertEquals(2, history.size)
        assertEquals(firstId, history[0].sessionId)
        assertNotEquals(history[0].sessionId, history[1].sessionId)
        assertEquals(FocusSessionOutcome.COMPLETED, history[0].outcome)
        assertEquals(FocusSessionOutcome.EARLY_ENDED, history[1].outcome)
    }

    @Test fun distinctSessionsWithIdenticalTimestampsStillHaveUniqueIds() {
        store.startFocusSession(1, start)
        store.endFocusSessionEarly(start + 1L)
        store.startFocusSession(1, start)
        store.endFocusSessionEarly(start + 1L)
        val history = store.getSessionHistory(start + 1L)
        assertEquals(2, history.size)
        assertEquals(2, history.map { it.sessionId }.toSet().size)
    }

    @Test fun clearRemainsResetOnlyAndPreservesExistingHistory() {
        store.startFocusSession(1, start)
        val entry = store.endFocusSessionEarly(start + 1L).historyEntry!!
        store.startFocusSession(1, end)
        assertTrue(store.clearFocusSession())
        assertTrue(store.clearFocusSession())
        assertNull(store.getActiveSession(end + 1L))
        assertEquals(listOf(entry), store.getSessionHistory(end + 120_000L))
        assertActiveKeysAbsent(preferences)
    }

    @Test fun clearOfExpiredLegacyStateDoesNotInventAnOutcome() {
        seedLegacyState()
        assertTrue(store.clearFocusSession())
        assertTrue(store.getSessionHistory(end + 1L).isEmpty())
    }

    @Test fun durationBoundsAndInvalidStartReadOnlyBehaviorArePreserved() {
        seedLegacyState()
        val before = preferences.all
        for (duration in listOf(Int.MIN_VALUE, 0, 1441, Int.MAX_VALUE)) {
            assertEquals(FocusSessionStartStatus.INVALID_DURATION, store.startFocusSession(duration, end).status)
            assertEquals(before, preferences.all)
        }
        val longest = FocusSessionStore(TestPreferences()).startFocusSession(1440, start)
        assertEquals(FocusSessionStartStatus.STARTED, longest.status)
        assertEquals(start + 1440L * 60_000L, longest.snapshot!!.endsAtMillis)
    }

    @Test fun invalidLegacyStateIsClearedWithoutHistory() {
        preferences.edit().putLong("started_at_millis", start).commit()
        assertNull(store.getActiveSession(start))
        assertActiveKeysAbsent(preferences)
        assertTrue(store.getSessionHistory(start).isEmpty())
    }

    @Test fun previouslyRecordedIdentityCannotGetASecondOutcome() {
        store.startFocusSession(1, start)
        val first = store.endFocusSessionEarly(end - 1L).historyEntry!!
        preferences.edit()
            .putLong("started_at_millis", start)
            .putLong("ends_at_millis", end)
            .putString("session_id", first.sessionId)
            .commit()
        assertNull(store.getActiveSession(end))
        assertEquals(listOf(first), store.getSessionHistory(end))
    }

    @Test fun failedSettlementKeepsBothMemoryAndDiskInternallyConsistent() {
        store.startFocusSession(1, start)
        preferences.failNextCommit = true
        assertEquals(FocusSessionEndStatus.STORAGE_ERROR, store.endFocusSessionEarly(end - 1L).status)
        // Android can publish the whole edit in memory even if the disk write fails.
        assertActiveKeysAbsent(preferences)
        assertEquals(FocusSessionOutcome.EARLY_ENDED, store.getSessionHistory(end).single().outcome)
        val restarted = FocusSessionStore(preferences.restart())
        assertNotNull(restarted.getActiveSession(end - 1L))
        assertTrue(restarted.getSessionHistory(end - 1L).isEmpty())
        assertEquals(FocusSessionEndStatus.ENDED, restarted.endFocusSessionEarly(end - 1L).status)
        assertEquals(1, restarted.getSessionHistory(end).size)
    }

    @Test fun failedExpiryCommitPreventsTheSameStartCallFromOverwritingSession() {
        store.startFocusSession(1, start)
        preferences.failNextCommit = true
        assertEquals(FocusSessionStartStatus.STORAGE_ERROR, store.startFocusSession(10, end).status)
        assertActiveKeysAbsent(preferences)
        assertEquals(FocusSessionSnapshot(start, end), FocusSessionStore(preferences.restart()).getActiveSession(end - 1L))
        // A later successful commit persists the pending history with the new session.
        assertEquals(FocusSessionStartStatus.STARTED, store.startFocusSession(10, end).status)
        val restored = FocusSessionStore(preferences.restart())
        assertEquals(end, restored.getSessionHistory(end).single().endedAtMillis)
        assertEquals(end + 600_000L, restored.getActiveSession(end)!!.endsAtMillis)
    }

    @Test fun failedLegacyIdentityUpgradeCannotAllowAnOverlappingStart() {
        seedLegacyState()
        preferences.failNextCommit = true
        assertEquals(FocusSessionStartStatus.STORAGE_ERROR, store.startFocusSession(10, start + 1L).status)
        assertEquals(FocusSessionSnapshot(start, end), store.getActiveSession(start + 1L))
        assertEquals(FocusSessionStartStatus.ALREADY_ACTIVE, store.startFocusSession(10, start + 1L).status)
    }

    @Test fun failedInvalidStateCleanupCannotAllowTheSameStartCallToOverwriteIt() {
        preferences.edit().putLong("started_at_millis", start).commit()
        preferences.failNextCommit = true
        assertEquals(FocusSessionStartStatus.STORAGE_ERROR, store.startFocusSession(1, start).status)
        assertEquals(start, preferences.restart().getLong("started_at_millis", 0L))
    }

    private fun seedLegacyState() {
        preferences.edit().putLong("started_at_millis", start).putLong("ends_at_millis", end).commit()
    }

    private fun assertActiveKeysAbsent(prefs: SharedPreferences) {
        for (key in listOf("started_at_millis", "ends_at_millis", "session_id")) {
            assertFalse("Unexpected active key: $key", prefs.contains(key))
        }
    }

    private fun concurrently(vararg actions: () -> Any?): List<Any?> {
        val executor = Executors.newFixedThreadPool(actions.size)
        val ready = CountDownLatch(actions.size)
        val release = CountDownLatch(1)
        try {
            val futures = actions.map { action ->
                executor.submit(Callable {
                    ready.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    action()
                })
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            return futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

/** Models atomic SharedPreferences edits, including memory updates on failed disk writes. */
private class TestPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
    private var memory = initial.toMap()
    private var disk = initial.toMap()
    var failNextCommit = false
    var failRead = false

    @Synchronized fun restart() = TestPreferences(disk)
    @Synchronized override fun getAll(): MutableMap<String, *> {
        check(!failRead) { "Simulated storage read failure" }
        return memory.toMutableMap()
    }
    @Synchronized override fun contains(key: String?) = memory.containsKey(key)
    @Synchronized override fun getString(key: String?, defValue: String?) = memory[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (memory[key] as Set<String>?)?.toMutableSet() ?: defValues
    @Synchronized override fun getInt(key: String?, defValue: Int) = memory[key] as Int? ?: defValue
    @Synchronized override fun getLong(key: String?, defValue: Long) = memory[key] as Long? ?: defValue
    @Synchronized override fun getFloat(key: String?, defValue: Float) = memory[key] as Float? ?: defValue
    @Synchronized override fun getBoolean(key: String?, defValue: Boolean) = memory[key] as Boolean? ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearRequested = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean = synchronized(this@TestPreferences) {
            val next = if (clearRequested) mutableMapOf() else memory.toMutableMap()
            changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            memory = next.toMap()
            if (failNextCommit) {
                failNextCommit = false
                false
            } else {
                disk = memory.toMap()
                true
            }
        }
        override fun apply() { commit() }
    }
}

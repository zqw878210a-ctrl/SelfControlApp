package com.selfcontrol.app.focus

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.UUID

data class FocusSessionSnapshot(
    val startedAtMillis: Long,
    val endsAtMillis: Long
)

enum class FocusSessionStartStatus {
    STARTED,
    ALREADY_ACTIVE,
    INVALID_DURATION,
    STORAGE_ERROR
}

data class FocusSessionStartResult(
    val status: FocusSessionStartStatus,
    val snapshot: FocusSessionSnapshot? = null
)

enum class FocusSessionOutcome {
    COMPLETED,
    EARLY_ENDED
}

data class FocusSessionHistoryEntry(
    val sessionId: String,
    val startedAtMillis: Long,
    val endsAtMillis: Long,
    val endedAtMillis: Long,
    val outcome: FocusSessionOutcome
)

enum class FocusSessionEndStatus {
    ENDED,
    NO_ACTIVE_SESSION,
    STORAGE_ERROR
}

data class FocusSessionEndResult(
    val status: FocusSessionEndStatus,
    val historyEntry: FocusSessionHistoryEntry? = null
)

class FocusSessionStore private constructor(preferencesProvider: () -> SharedPreferences) {
    constructor(context: Context) : this({
        context.applicationContext.getSharedPreferences("focus_session_state", Context.MODE_PRIVATE)
    })

    internal constructor(preferences: SharedPreferences) : this({ preferences })

    private val preferences by lazy(preferencesProvider)

    fun startFocusSession(
        durationMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): FocusSessionStartResult = synchronized(sharedLock) {
        // Invalid durations are read-only, including when stale data is present.
        if (durationMinutes !in 1..1440) {
            return@synchronized FocusSessionStartResult(FocusSessionStartStatus.INVALID_DURATION)
        }
        try {
            val active = readActiveSession(nowMillis)
            if (active != null) {
                return@synchronized FocusSessionStartResult(FocusSessionStartStatus.ALREADY_ACTIVE, active)
            }
            val durationMillis = durationMinutes * 60_000L
            check(nowMillis > 0L && nowMillis <= Long.MAX_VALUE - durationMillis) {
                "Focus session timestamps are not representable"
            }
            val snapshot = FocusSessionSnapshot(nowMillis, nowMillis + durationMillis)
            val stored = preferences.edit()
                .putLong(STARTED_AT, snapshot.startedAtMillis)
                .putLong(ENDS_AT, snapshot.endsAtMillis)
                .putString(SESSION_ID, UUID.randomUUID().toString())
                .commit()
            if (stored) {
                FocusSessionStartResult(FocusSessionStartStatus.STARTED, snapshot)
            } else {
                // commit may update the memory cache even on failure; do not report success
                // or remove that cache and risk allowing another overlapping start.
                Log.w(TAG, "FOCUS_SESSION_WRITE_FAILED reason=commit_failed")
                FocusSessionStartResult(FocusSessionStartStatus.STORAGE_ERROR)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "FOCUS_SESSION_START_FAILED", error)
            FocusSessionStartResult(FocusSessionStartStatus.STORAGE_ERROR)
        }
    }

    fun getActiveSession(
        nowMillis: Long = System.currentTimeMillis()
    ): FocusSessionSnapshot? = synchronized(sharedLock) {
        try {
            readActiveSession(nowMillis)
        } catch (error: RuntimeException) {
            Log.w(TAG, "FOCUS_SESSION_READ_FAILED", error)
            null
        }
    }

    fun getRemainingMillis(
        nowMillis: Long = System.currentTimeMillis()
    ): Long = synchronized(sharedLock) {
        val session = getActiveSession(nowMillis) ?: return@synchronized 0L
        maxOf(session.endsAtMillis - nowMillis, 0L)
    }

    /** Watchdog read: do not settle/migrate Focus, and do not mistake storage failure for no session. */
    internal fun hasActiveSessionForMonitoring(
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = synchronized(sharedLock) {
        val values = preferences.all
        val startedAt = values[STARTED_AT] as? Long ?: return@synchronized false
        val endsAt = values[ENDS_AT] as? Long ?: return@synchronized false
        startedAt > 0L && endsAt > startedAt && nowMillis < endsAt
    }

    /** Call only after the user confirms ending the current Focus session. */
    fun endFocusSessionEarly(
        nowMillis: Long = System.currentTimeMillis()
    ): FocusSessionEndResult = synchronized(sharedLock) {
        try {
            val session = readStoredSession()
                ?: return@synchronized FocusSessionEndResult(FocusSessionEndStatus.NO_ACTIVE_SESSION)
            FocusSessionEndResult(FocusSessionEndStatus.ENDED, settleSession(session, nowMillis))
        } catch (error: RuntimeException) {
            Log.w(TAG, "FOCUS_SESSION_END_FAILED", error)
            FocusSessionEndResult(FocusSessionEndStatus.STORAGE_ERROR)
        }
    }

    /**
     * Oldest first. Settles an expired session on access, including after process restart.
     * Storage failures propagate so they cannot be mistaken for an empty history.
     */
    fun getSessionHistory(
        nowMillis: Long = System.currentTimeMillis()
    ): List<FocusSessionHistoryEntry> = synchronized(sharedLock) {
        readActiveSession(nowMillis)
        preferences.all.entries
            .filter { it.key.startsWith(HISTORY_PREFIX) }
            .map { decodeHistory(it.key.removePrefix(HISTORY_PREFIX), it.value) }
            .sortedWith(compareBy({ it.startedAtMillis }, { it.endedAtMillis }, { it.sessionId }))
    }

    // Legacy reset operation: deliberately records no outcome, even for expired state.
    // Overlay STOP_USING must not be wired to the explicit early-end API.
    fun clearFocusSession(): Boolean = synchronized(sharedLock) {
        try {
            val stored = removeActiveSession(preferences.edit()).commit()
            if (!stored) Log.w(TAG, "FOCUS_SESSION_CLEAR_FAILED reason=commit_failed")
            stored
        } catch (error: RuntimeException) {
            Log.w(TAG, "FOCUS_SESSION_CLEAR_FAILED", error)
            false
        }
    }

    // Caller holds the shared lock. Let read failures propagate to startFocusSession
    // so a failed read cannot be mistaken for permission to overwrite a session.
    private fun readActiveSession(nowMillis: Long): FocusSessionSnapshot? {
        val session = readStoredSession() ?: return null
        if (nowMillis >= session.snapshot.endsAtMillis) {
            settleSession(session, nowMillis)
            return null
        }
        return session.snapshot
    }

    private data class StoredSession(val sessionId: String, val snapshot: FocusSessionSnapshot)

    private fun readStoredSession(): StoredSession? {
        val values = preferences.all
        if (!values.containsKey(STARTED_AT) && !values.containsKey(ENDS_AT)) return null
        val startedAt = values[STARTED_AT] as? Long
        val endsAt = values[ENDS_AT] as? Long
        if (startedAt == null || endsAt == null || startedAt <= 0L ||
            endsAt <= startedAt
        ) {
            check(clearFocusSession()) { "Could not clear invalid Focus session" }
            return null
        }
        // Upgrade the old two-timestamp format without changing either timestamp.
        val sessionId = (values[SESSION_ID] as? String)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString(SESSION_ID, it).commit()) {
                    "Could not persist Focus session identity"
                }
            }
        return StoredSession(sessionId, FocusSessionSnapshot(startedAt, endsAt))
    }

    private fun settleSession(session: StoredSession, nowMillis: Long): FocusSessionHistoryEntry {
        val key = HISTORY_PREFIX + session.sessionId
        val snapshot = session.snapshot
        val completed = nowMillis >= snapshot.endsAtMillis
        // First result wins, including if an already-settled identity is encountered again.
        val entry = if (preferences.contains(key)) {
            decodeHistory(session.sessionId, preferences.all[key])
        } else {
            FocusSessionHistoryEntry(
                session.sessionId,
                snapshot.startedAtMillis,
                snapshot.endsAtMillis,
                if (completed) snapshot.endsAtMillis else nowMillis,
                if (completed) FocusSessionOutcome.COMPLETED else FocusSessionOutcome.EARLY_ENDED
            )
        }
        // SharedPreferences commits the entire edit atomically. The disk and memory
        // each contain either the active state or its result, never half a settlement.
        // A failed commit can still update memory, so report failure and never roll back
        // just one side. A subsequent commit persists the whole memory state together.
        check(removeActiveSession(preferences.edit())
            .putString(key, encodeHistory(entry))
            .commit()) { "Could not settle Focus session" }
        return entry
    }

    private fun removeActiveSession(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        editor.remove(STARTED_AT).remove(ENDS_AT).remove(SESSION_ID)

    // Versioned per-session keys avoid JSON dependencies and preserve existing history
    // without rewriting/merging a separate file. The ID is the key; values have four fields.
    private fun encodeHistory(entry: FocusSessionHistoryEntry): String =
        "${entry.startedAtMillis}|${entry.endsAtMillis}|${entry.endedAtMillis}|${entry.outcome.name}"

    private fun decodeHistory(sessionId: String, value: Any?): FocusSessionHistoryEntry {
        val fields = (value as? String)?.split('|')
        check(fields != null && fields.size == 4) { "Invalid Focus history record" }
        return FocusSessionHistoryEntry(
            sessionId, fields[0].toLong(), fields[1].toLong(), fields[2].toLong(),
            FocusSessionOutcome.valueOf(fields[3])
        )
    }

    private companion object {
        const val TAG = "SELF_CONTROL_FOCUS"
        const val STARTED_AT = "started_at_millis"
        const val ENDS_AT = "ends_at_millis"
        const val SESSION_ID = "session_id"
        const val HISTORY_PREFIX = "history_v1."
        val sharedLock = Any()
    }
}

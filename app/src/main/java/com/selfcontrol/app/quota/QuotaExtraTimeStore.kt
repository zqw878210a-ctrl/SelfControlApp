package com.selfcontrol.app.quota

import android.content.Context
import android.util.Log
import java.util.Calendar
import java.util.Locale

enum class QuotaExtraTimeGrantStatus {
    GRANTED,
    ALREADY_GRANTED,
    INVALID_MINUTES,
    STORAGE_ERROR
}

data class QuotaExtraTimeGrantResult(
    val status: QuotaExtraTimeGrantStatus,
    val totalExtraMinutes: Int
)

class QuotaExtraTimeStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences("daily_quota_extra_time", Context.MODE_PRIVATE)
    }

    fun getExtraMinutes(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Int = synchronized(lock) {
        try {
            readTodayState(packageName, localDateKey(nowMillis)).extraMinutes
        } catch (error: RuntimeException) {
            Log.w(TAG, "QUOTA_EXTRA_TIME_READ_FAILED packageName=$packageName", error)
            0
        }
    }

    fun hasUsedGrantToday(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = synchronized(lock) {
        try {
            readTodayState(packageName, localDateKey(nowMillis)).grantUsed
        } catch (error: RuntimeException) {
            Log.w(TAG, "QUOTA_EXTRA_TIME_GRANT_READ_FAILED packageName=$packageName", error)
            // Do not offer another cooldown when availability cannot be read.
            true
        }
    }

    fun tryGrantExtraMinutes(
        packageName: String,
        minutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): QuotaExtraTimeGrantResult = synchronized(lock) {
        var currentMinutes = 0
        try {
            val today = localDateKey(nowMillis)
            // Invalid requests must not mutate even stale or legacy records.
            if (minutes <= 0) {
                val values = preferences.all
                if (values["date:$packageName"] == today) {
                    currentMinutes = (values["extra_minutes:$packageName"] as? Int)?.coerceAtLeast(0) ?: 0
                }
                return@synchronized QuotaExtraTimeGrantResult(
                    QuotaExtraTimeGrantStatus.INVALID_MINUTES, currentMinutes
                )
            }
            val state = readTodayState(packageName, today)
            currentMinutes = state.extraMinutes
            if (state.grantUsed) {
                return@synchronized QuotaExtraTimeGrantResult(
                    QuotaExtraTimeGrantStatus.ALREADY_GRANTED, currentMinutes
                )
            }
            // Persist all three fields together before reporting a successful grant.
            val stored = preferences.edit()
                .putString("date:$packageName", today)
                .putInt("extra_minutes:$packageName", minutes)
                .putBoolean("grant_used:$packageName", true)
                .commit()
            if (stored) {
                QuotaExtraTimeGrantResult(QuotaExtraTimeGrantStatus.GRANTED, minutes)
            } else {
                // A failed commit can still update the in-memory preferences. Keep the
                // used flag conservatively; never turn a storage failure into a retry grant.
                Log.w(TAG, "QUOTA_EXTRA_TIME_WRITE_FAILED packageName=$packageName reason=commit_failed")
                QuotaExtraTimeGrantResult(QuotaExtraTimeGrantStatus.STORAGE_ERROR, minutes)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "QUOTA_EXTRA_TIME_WRITE_FAILED packageName=$packageName", error)
            QuotaExtraTimeGrantResult(QuotaExtraTimeGrantStatus.STORAGE_ERROR, currentMinutes)
        }
    }

    fun clearExtraMinutesPreservingGrant(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            try {
                val today = localDateKey(nowMillis)
                val state = readTodayState(packageName, today)
                // Infer legacy grants before clearing their only evidence (extra minutes).
                preferences.edit()
                    .putString("date:$packageName", today)
                    .putInt("extra_minutes:$packageName", 0)
                    .putBoolean("grant_used:$packageName", state.grantUsed)
                    .apply()
            } catch (error: RuntimeException) {
                Log.w(TAG, "QUOTA_EXTRA_TIME_CLEAR_FAILED packageName=$packageName", error)
            }
        }
    }

    fun clear(packageName: String) {
        synchronized(lock) {
            try {
                preferences.edit()
                    .remove("date:$packageName")
                    .remove("extra_minutes:$packageName")
                    .remove("grant_used:$packageName")
                    .apply()
            } catch (error: RuntimeException) {
                Log.w(TAG, "QUOTA_EXTRA_TIME_CLEAR_FAILED packageName=$packageName", error)
            }
        }
    }

    // Caller holds the shared lock, including when this lazily removes an old day.
    private fun readTodayState(packageName: String, today: String): TodayState {
        val values = preferences.all
        if (values["date:$packageName"] != today) {
            if (values.containsKey("date:$packageName") ||
                values.containsKey("extra_minutes:$packageName") ||
                values.containsKey("grant_used:$packageName")
            ) {
                clear(packageName)
            }
            return TodayState(0, false)
        }
        val extraMinutes = (values["extra_minutes:$packageName"] as? Int)?.coerceAtLeast(0) ?: 0
        // Older installs have no grant_used key. Positive same-day extra is proof of a grant.
        return TodayState(extraMinutes, values["grant_used:$packageName"] == true || extraMinutes > 0)
    }

    private data class TodayState(val extraMinutes: Int, val grantUsed: Boolean)

    private fun localDateKey(nowMillis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private companion object {
        const val TAG = "SELF_CONTROL_QUOTA"
        // Serialize read/modify/write operations across all Store instances in this process.
        val lock = Any()
    }
}

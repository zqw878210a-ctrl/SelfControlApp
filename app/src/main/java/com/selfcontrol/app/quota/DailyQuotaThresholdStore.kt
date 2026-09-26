package com.selfcontrol.app.quota

import android.content.Context
import java.util.Calendar
import java.util.Locale

class DailyQuotaThresholdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "daily_quota_thresholds",
        Context.MODE_PRIVATE
    )

    fun getLastHandledThreshold(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): DailyQuotaThreshold? = synchronized(lock) {
        val values = preferences.all
        if (values["date:$packageName"] != localDateKey(nowMillis)) return@synchronized null

        when (values["threshold:$packageName"] as? String) {
            "50" -> DailyQuotaThreshold.REACHED_50
            "80" -> DailyQuotaThreshold.REACHED_80
            "90" -> DailyQuotaThreshold.REACHED_90
            "100" -> DailyQuotaThreshold.REACHED_100
            else -> null
        }
    }

    fun saveLastHandledThreshold(
        packageName: String,
        threshold: DailyQuotaThreshold,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val value = thresholdValue(threshold) ?: return
        synchronized(lock) {
            val existing = getLastHandledThreshold(packageName, nowMillis)?.let { thresholdValue(it) }
            if (existing != null && existing >= value) return@synchronized

            preferences.edit()
                .putString("date:$packageName", localDateKey(nowMillis))
                .putString("threshold:$packageName", value.toString())
                .apply()
        }
    }

    fun resetForPackage(packageName: String) {
        synchronized(lock) {
            preferences.edit()
                .remove("date:$packageName")
                .remove("threshold:$packageName")
                .apply()
        }
    }

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

    private fun thresholdValue(threshold: DailyQuotaThreshold): Int? = when (threshold) {
        DailyQuotaThreshold.BELOW_50 -> null
        DailyQuotaThreshold.REACHED_50 -> 50
        DailyQuotaThreshold.REACHED_80 -> 80
        DailyQuotaThreshold.REACHED_90 -> 90
        DailyQuotaThreshold.REACHED_100 -> 100
    }

    private companion object {
        // Keep the read/compare/write operation consistent across Store instances.
        val lock = Any()
    }
}

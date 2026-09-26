package com.selfcontrol.app.quota

import android.content.Context
import android.util.Log

class DailyQuotaThresholdTracker(context: Context) {
    private val store = DailyQuotaThresholdStore(context)

    fun consumeNewlyReachedThreshold(
        packageName: String,
        todayUsageMillis: Long,
        dailyQuotaMinutes: Int?,
        nowMillis: Long = System.currentTimeMillis()
    ): DailyQuotaThreshold? = synchronized(lock) {
        val currentThreshold = calculateDailyQuotaThreshold(todayUsageMillis, dailyQuotaMinutes)
        val lastHandledThreshold = store.getLastHandledThreshold(packageName, nowMillis)
        val newlyReachedThreshold = calculateNewlyReachedQuotaThreshold(
            currentThreshold,
            lastHandledThreshold
        ).also { result ->
            Log.i("SELF_CONTROL_QUOTA", "THRESHOLD_EVALUATION packageName=$packageName " +
                "currentThreshold=$currentThreshold lastHandledThreshold=$lastHandledThreshold " +
                "newlyReachedThreshold=$result")
            if (result == null) {
                Log.i("SELF_CONTROL_QUOTA", "THRESHOLD_NOT_NEW packageName=$packageName")
            }
        } ?: return@synchronized null

        store.saveLastHandledThreshold(packageName, newlyReachedThreshold, nowMillis)
        newlyReachedThreshold
    }

    private companion object {
        // Serialize consumption across Tracker instances to avoid returning the same event twice.
        val lock = Any()
    }
}

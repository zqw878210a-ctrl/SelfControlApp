package com.selfcontrol.app.usage

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

object TodayUsageStatsReader {
    fun getTodayUsageMillis(
        context: Context,
        packageNames: Collection<String>
    ): Map<String, Long> {
        if (packageNames.isEmpty()) return emptyMap()

        val usageMillis = packageNames.associateWith { 0L }.toMutableMap()
        val stats = try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return usageMillis
            val now = System.currentTimeMillis()
            val startOfToday = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday, now)
        } catch (_: SecurityException) {
            return usageMillis
        } ?: return usageMillis

        for (stat in stats) {
            val accumulated = usageMillis[stat.packageName] ?: continue
            usageMillis[stat.packageName] = accumulated + stat.totalTimeInForeground
        }
        return usageMillis
    }
}

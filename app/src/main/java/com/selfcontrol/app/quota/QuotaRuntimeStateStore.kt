package com.selfcontrol.app.quota

import com.selfcontrol.app.data.ControlledAppConfig
import java.util.Calendar
import java.util.Locale

data class QuotaRuntimeSnapshot(
    val packageName: String,
    val state: DailyQuotaState,
    val todayUsageMillis: Long,
    val dailyQuotaMinutes: Int,
    val localDateKey: String,
    val updatedAtMillis: Long
)

class QuotaRuntimeStateStore {
    private val lock = Any()
    private val snapshots = mutableMapOf<String, QuotaRuntimeSnapshot>()

    fun update(
        packageName: String,
        state: DailyQuotaState,
        todayUsageMillis: Long,
        dailyQuotaMinutes: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): QuotaRuntimeSnapshot = synchronized(lock) {
        val snapshot = QuotaRuntimeSnapshot(
            packageName, state, todayUsageMillis, dailyQuotaMinutes,
            localDateKey(nowMillis), nowMillis
        )
        snapshots[packageName] = snapshot
        snapshot
    }

    fun getFreshSnapshot(
        packageName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): QuotaRuntimeSnapshot? = synchronized(lock) {
        val snapshot = snapshots[packageName] ?: return@synchronized null
        if (snapshot.localDateKey != localDateKey(nowMillis)) {
            snapshots.remove(packageName)
            return@synchronized null
        }
        snapshot
    }

    fun clear(packageName: String) {
        synchronized(lock) {
            snapshots.remove(packageName)
        }
    }

    fun invalidateChangedConfigs(
        previous: Map<String, ControlledAppConfig>,
        current: Map<String, ControlledAppConfig>
    ): Set<String> = synchronized(lock) {
        val changed = (previous.keys + current.keys).filterTo(mutableSetOf()) { packageName ->
            val old = previous[packageName]
            val new = current[packageName]
            old?.enabled != new?.enabled || old?.intentGateEnabled != new?.intentGateEnabled ||
                old?.dailyQuotaMinutes != new?.dailyQuotaMinutes ||
                old?.quotaEnforcementMode != new?.quotaEnforcementMode
        }
        changed.forEach { snapshots.remove(it) }
        changed
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
}

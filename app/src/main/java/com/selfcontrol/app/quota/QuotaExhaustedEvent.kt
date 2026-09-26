package com.selfcontrol.app.quota

data class QuotaExhaustedEvent(
    val packageName: String,
    val todayUsageMillis: Long,
    val dailyQuotaMinutes: Int,
    val triggeredAtMillis: Long
)

fun createQuotaExhaustedEventIfNeeded(
    previousSnapshot: QuotaRuntimeSnapshot?,
    currentSnapshot: QuotaRuntimeSnapshot,
    nowMillis: Long
): QuotaExhaustedEvent? {
    if (currentSnapshot.state != DailyQuotaState.EXHAUSTED ||
        previousSnapshot?.state == DailyQuotaState.EXHAUSTED
    ) return null

    return QuotaExhaustedEvent(
        packageName = currentSnapshot.packageName,
        todayUsageMillis = currentSnapshot.todayUsageMillis,
        dailyQuotaMinutes = currentSnapshot.dailyQuotaMinutes,
        triggeredAtMillis = nowMillis
    )
}

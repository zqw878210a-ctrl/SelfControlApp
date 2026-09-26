package com.selfcontrol.app.quota

enum class DailyQuotaState {
    NOT_SET,
    WITHIN_QUOTA,
    EXHAUSTED
}

fun calculateDailyQuotaState(
    todayUsageMillis: Long,
    dailyQuotaMinutes: Int?
): DailyQuotaState {
    if (dailyQuotaMinutes == null) return DailyQuotaState.NOT_SET

    val quotaMillis = dailyQuotaMinutes * 60_000L
    return if (todayUsageMillis.coerceAtLeast(0L) < quotaMillis) {
        DailyQuotaState.WITHIN_QUOTA
    } else {
        DailyQuotaState.EXHAUSTED
    }
}

fun calculateRemainingQuotaMillis(
    todayUsageMillis: Long,
    dailyQuotaMinutes: Int?
): Long? {
    if (dailyQuotaMinutes == null) return null

    val normalizedUsageMillis = todayUsageMillis.coerceAtLeast(0L)
    val quotaMillis = dailyQuotaMinutes * 60_000L
    return (quotaMillis - normalizedUsageMillis).coerceAtLeast(0L)
}

fun calculateQuotaUsagePercent(
    todayUsageMillis: Long,
    dailyQuotaMinutes: Int?
): Int? {
    if (dailyQuotaMinutes == null) return null

    val normalizedUsageMillis = todayUsageMillis.coerceAtLeast(0L)
    val quotaMillis = dailyQuotaMinutes * 60_000L
    if (normalizedUsageMillis >= quotaMillis) return 100

    return (normalizedUsageMillis * 100L / quotaMillis).toInt()
}

enum class DailyQuotaThreshold {
    BELOW_50,
    REACHED_50,
    REACHED_80,
    REACHED_90,
    REACHED_100
}

fun calculateDailyQuotaThreshold(
    todayUsageMillis: Long,
    dailyQuotaMinutes: Int?
): DailyQuotaThreshold? {
    val percent = calculateQuotaUsagePercent(todayUsageMillis, dailyQuotaMinutes) ?: return null
    return when {
        percent < 50 -> DailyQuotaThreshold.BELOW_50
        percent < 80 -> DailyQuotaThreshold.REACHED_50
        percent < 90 -> DailyQuotaThreshold.REACHED_80
        percent < 100 -> DailyQuotaThreshold.REACHED_90
        else -> DailyQuotaThreshold.REACHED_100
    }
}

fun calculateNewlyReachedQuotaThreshold(
    currentThreshold: DailyQuotaThreshold?,
    lastHandledThreshold: DailyQuotaThreshold?
): DailyQuotaThreshold? {
    if (currentThreshold == null || currentThreshold == DailyQuotaThreshold.BELOW_50) return null

    return if (lastHandledThreshold == null ||
        quotaThresholdRank(currentThreshold) > quotaThresholdRank(lastHandledThreshold)
    ) {
        currentThreshold
    } else {
        null
    }
}

private fun quotaThresholdRank(threshold: DailyQuotaThreshold): Int = when (threshold) {
    DailyQuotaThreshold.BELOW_50 -> 0
    DailyQuotaThreshold.REACHED_50 -> 1
    DailyQuotaThreshold.REACHED_80 -> 2
    DailyQuotaThreshold.REACHED_90 -> 3
    DailyQuotaThreshold.REACHED_100 -> 4
}

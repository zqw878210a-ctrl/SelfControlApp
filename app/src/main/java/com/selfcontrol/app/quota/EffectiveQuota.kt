package com.selfcontrol.app.quota

fun calculateEffectiveQuotaMinutes(
    baseDailyQuotaMinutes: Int?,
    extraMinutes: Int
): Int? {
    val base = baseDailyQuotaMinutes ?: return null
    val total = base.toLong() + extraMinutes.coerceAtLeast(0).toLong()
    return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

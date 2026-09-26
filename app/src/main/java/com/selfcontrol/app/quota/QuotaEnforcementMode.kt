package com.selfcontrol.app.quota

enum class QuotaEnforcementMode {
    MODERATE,
    STRICT
}

fun parseQuotaEnforcementMode(value: String?): QuotaEnforcementMode = when (value) {
    "STRICT" -> QuotaEnforcementMode.STRICT
    else -> QuotaEnforcementMode.MODERATE
}

package com.selfcontrol.app.quota

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.selfcontrol.app.data.ControlledAppConfig
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class QuotaExtraTimeLifecycleTest {
    private val preferences = ExtraTimeTestPreferences()
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            assertEquals("daily_quota_extra_time", name)
            return preferences
        }
    }
    private val store = QuotaExtraTimeStore(context)
    private val config = ControlledAppConfig("controlled.app", "App", true, true, 30)
    private val now = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 26, 12, 0)
    }.timeInMillis

    @Test fun grantedTimeExtendsQuotaAndExhaustionDoesNotAllowAnotherGrant() {
        assertEquals(DailyQuotaState.EXHAUSTED, calculateDailyQuotaState(30 * 60_000L, 30))
        assertEquals(QuotaExtraTimeGrantStatus.GRANTED,
            store.tryGrantExtraMinutes(config.packageName, 10, now).status)

        val effective = calculateEffectiveQuotaMinutes(30, store.getExtraMinutes(config.packageName, now))
        assertEquals(40, effective)
        assertEquals(DailyQuotaState.WITHIN_QUOTA, calculateDailyQuotaState(35 * 60_000L, effective))
        assertEquals(DailyQuotaState.EXHAUSTED, calculateDailyQuotaState(40 * 60_000L, effective))
        assertEquals(QuotaExtraTimeGrantStatus.ALREADY_GRANTED,
            store.tryGrantExtraMinutes(config.packageName, 10, now).status)
        assertEquals(10, store.getExtraMinutes(config.packageName, now))
    }

    @Test fun configInvalidationAndStoreRecreationPreserveAuthorization() {
        store.tryGrantExtraMinutes(config.packageName, 10, now)
        val before = preferences.all
        val runtime = QuotaRuntimeStateStore()
        var previous = config
        for (current in listOf(config.copy(dailyQuotaMinutes = 60),
            config.copy(dailyQuotaMinutes = 20), config.copy(dailyQuotaMinutes = null),
            config.copy(quotaEnforcementMode = "STRICT"), config,
            config.copy(enabled = false), config, config.copy(intentGateEnabled = false))) {
            runtime.update(config.packageName, DailyQuotaState.WITHIN_QUOTA, 35 * 60_000L, 40, now)
            assertEquals(setOf(config.packageName), runtime.invalidateChangedConfigs(
                mapOf(config.packageName to previous), mapOf(config.packageName to current)))
            assertNull(runtime.getFreshSnapshot(config.packageName, now))

            val restored = QuotaExtraTimeStore(context)
            assertEquals(10, restored.getExtraMinutes(config.packageName, now))
            assertTrue(restored.hasUsedGrantToday(config.packageName, now))
            assertEquals(before, preferences.all)
            val effective = calculateEffectiveQuotaMinutes(current.dailyQuotaMinutes,
                restored.getExtraMinutes(config.packageName, now))
            assertEquals(current.dailyQuotaMinutes?.plus(10), effective)
            val expected = when (current.dailyQuotaMinutes) {
                null -> DailyQuotaState.NOT_SET
                20 -> DailyQuotaState.EXHAUSTED
                else -> DailyQuotaState.WITHIN_QUOTA
            }
            assertEquals(expected, calculateDailyQuotaState(35 * 60_000L, effective))
            previous = current
        }
    }

    @Test fun explicitDisableRevokesOnlyThisAppWithoutResettingDailyGrantLimit() {
        store.tryGrantExtraMinutes(config.packageName, 10, now)
        store.tryGrantExtraMinutes("other.app", 5, now)
        store.clearExtraMinutesPreservingGrant(config.packageName, now)

        val restored = QuotaExtraTimeStore(context)
        assertEquals(0, restored.getExtraMinutes(config.packageName, now))
        assertTrue(restored.hasUsedGrantToday(config.packageName, now))
        assertEquals(QuotaExtraTimeGrantStatus.ALREADY_GRANTED,
            restored.tryGrantExtraMinutes(config.packageName, 10, now).status)
        assertEquals(5, restored.getExtraMinutes("other.app", now))
    }

    @Test fun nextLocalDayExpiresAuthorizationAndRestoresGrantEligibility() {
        store.tryGrantExtraMinutes(config.packageName, 10, now)
        val tomorrow = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

        assertEquals(0, store.getExtraMinutes(config.packageName, tomorrow))
        assertFalse(store.hasUsedGrantToday(config.packageName, tomorrow))
        assertTrue(preferences.all.isEmpty())
        assertEquals(QuotaExtraTimeGrantStatus.GRANTED,
            store.tryGrantExtraMinutes(config.packageName, 10, tomorrow).status)
    }
}

/** In-memory SharedPreferences for exercising the real Store without an Android device. */
private class ExtraTimeTestPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String?) = values.containsKey(key)
    override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        (values[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clearRequested = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean {
            if (clearRequested) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}

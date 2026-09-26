package com.selfcontrol.app

import com.selfcontrol.app.focus.FocusSessionSnapshot
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test

class MonitorNotificationTest {
    private val start = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 26, 14, 0)
    }.timeInMillis
    private val session = FocusSessionSnapshot(start, start + 25 * 60_000L)

    @Test fun startingFocusReplacesOrdinaryMonitorContentWithPersistedDeadline() {
        val ordinary = monitorNotificationContent(null, "example.app", start)
        assertEquals("自律监控正在运行", ordinary.title)
        assertEquals("当前检测：example.app", ordinary.text)
        assertNull(ordinary.focusEndsAtMillis)

        val active = monitorNotificationContent(session, "example.app", start)
        assertEquals("专注进行中", active.title)
        assertEquals("剩余约 25 分钟 · 预计 14:25 结束", active.text)
        assertEquals(session.endsAtMillis, active.focusEndsAtMillis)
    }

    @Test fun foregroundChangesCannotOverwriteActiveFocusContent() {
        val active = monitorNotificationContent(session, "first.app", start)
        assertEquals(active, monitorNotificationContent(session, "second.app", start))
        assertEquals(active, monitorNotificationContent(session, null, start))
    }

    @Test fun naturalExpiryRestoresOrdinaryNotificationAtTheDeadline() {
        val before = monitorNotificationContent(session, "example.app", session.endsAtMillis - 1L)
        assertEquals("专注进行中", before.title)
        assertTrue(before.text.startsWith("剩余约 1 分钟"))
        for (now in listOf(session.endsAtMillis, session.endsAtMillis + 60_000L)) {
            assertEquals(monitorNotificationContent(null, "example.app", now),
                monitorNotificationContent(session, "example.app", now))
        }
    }

    @Test fun earlyEndRestoresOrdinaryContentWithoutWaitingForOriginalDeadline() {
        val now = start + 60_000L
        assertNotNull(monitorNotificationContent(session, "example.app", now).focusEndsAtMillis)
        val ended = monitorNotificationContent(null, "example.app", now)
        assertEquals("自律监控正在运行", ended.title)
        assertEquals("当前检测：example.app", ended.text)
        assertNull(ended.focusEndsAtMillis)
    }

    @Test fun remainingTextChangesByMinuteWhileSystemCountdownKeepsOriginalDeadline() {
        val initial = monitorNotificationContent(session, null, start)
        assertEquals(initial, monitorNotificationContent(session, null, start + 59_999L))
        val nextMinute = monitorNotificationContent(session, null, start + 60_000L)
        assertEquals("剩余约 24 分钟 · 预计 14:25 结束", nextMinute.text)
        assertEquals(initial.focusEndsAtMillis, nextMinute.focusEndsAtMillis)
    }

    @Test fun recreatedPresentationUsesRemainingTimeInsteadOfRestartingDuration() {
        val restored = monitorNotificationContent(session.copy(), null, start + 10 * 60_000L)
        assertEquals("剩余约 15 分钟 · 预计 14:25 结束", restored.text)
        assertEquals(session.endsAtMillis, restored.focusEndsAtMillis)
        assertEquals("当前检测：未确认前台应用", monitorNotificationContent(null, null, start).text)
    }
}

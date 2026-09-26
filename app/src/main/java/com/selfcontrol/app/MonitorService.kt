package com.selfcontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.lang.ref.WeakReference
import com.selfcontrol.app.data.AppDatabase
import com.selfcontrol.app.data.GateEvent
import com.selfcontrol.app.data.ControlledAppConfig
import com.selfcontrol.app.focus.FocusSessionSnapshot
import com.selfcontrol.app.focus.FocusSessionStore
import com.selfcontrol.app.focus.FocusBlockedOverlayController
import com.selfcontrol.app.quota.DailyQuotaThresholdTracker
import com.selfcontrol.app.quota.DailyQuotaThresholdStore
import com.selfcontrol.app.quota.QuotaNotificationHelper
import com.selfcontrol.app.quota.calculateQuotaUsagePercent
import com.selfcontrol.app.quota.calculateDailyQuotaThreshold
import com.selfcontrol.app.quota.DailyQuotaState
import com.selfcontrol.app.quota.calculateDailyQuotaState
import com.selfcontrol.app.quota.QuotaRuntimeStateStore
import com.selfcontrol.app.quota.createQuotaExhaustedEventIfNeeded
import com.selfcontrol.app.quota.QuotaExhaustedOverlayController
import com.selfcontrol.app.quota.ExtraTimeUnavailableReason
import com.selfcontrol.app.quota.QuotaEnforcementMode
import com.selfcontrol.app.quota.parseQuotaEnforcementMode
import com.selfcontrol.app.quota.QuotaExhaustedEvent
import com.selfcontrol.app.quota.QuotaExhaustedAction
import com.selfcontrol.app.quota.QuotaCooldownOverlayController
import com.selfcontrol.app.quota.QuotaExtraTimeAction
import com.selfcontrol.app.quota.QuotaExtraTimeStore
import com.selfcontrol.app.quota.QuotaExtraTimeGrantStatus
import com.selfcontrol.app.quota.calculateEffectiveQuotaMinutes
import com.selfcontrol.app.usage.TodayUsageStatsReader

internal const val MONITOR_START_RETRY_MS = 5_000L
internal const val MONITOR_HEARTBEAT_TIMEOUT_MS = 10_000L

internal data class MonitorNotificationContent(
    val title: String,
    val text: String,
    val focusEndsAtMillis: Long? = null
)

internal fun monitorNotificationContent(
    session: FocusSessionSnapshot?,
    detectedPackage: String?,
    nowMillis: Long
): MonitorNotificationContent {
    if (session == null || nowMillis >= session.endsAtMillis) {
        return MonitorNotificationContent("自律监控正在运行", "当前检测：${detectedPackage ?: "未确认前台应用"}")
    }
    val remainingMinutes = (session.endsAtMillis - nowMillis + 59_999L) / 60_000L
    val endTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.endsAtMillis))
    return MonitorNotificationContent("专注进行中", "剩余约 $remainingMinutes 分钟 · 预计 $endTime 结束",
        session.endsAtMillis)
}

internal enum class MonitorOverlayState {
    UNVERIFIED, PERMISSION_MISSING, NOT_REQUIRED, ATTACHING, ATTACHED, FAILED
}

/** Process-local evidence, not a claim that Android rendered or delivered touch input. */
internal data class MonitorHealthSnapshot(
    val initialized: Boolean = false,
    val lastPollElapsed: Long? = null,
    val pollSucceeded: Boolean = false,
    val nextPollPosted: Boolean = false,
    val foregroundReadSucceeded: Boolean = false,
    val lastForegroundReadElapsed: Long? = null,
    val overlay: MonitorOverlayState = MonitorOverlayState.UNVERIFIED
) {
    fun unavailableReason(serviceExists: Boolean, nowElapsed: Long): String? = when {
        !serviceExists -> "SERVICE_MISSING"
        !initialized -> "INITIALIZING"
        lastPollElapsed == null -> "POLL_NOT_STARTED"
        nowElapsed - lastPollElapsed !in 0 until MONITOR_HEARTBEAT_TIMEOUT_MS -> "HEARTBEAT_STALE"
        !pollSucceeded || !nextPollPosted -> "POLL_FAILED"
        !foregroundReadSucceeded || lastForegroundReadElapsed == null ||
            nowElapsed - lastForegroundReadElapsed !in 0 until MONITOR_HEARTBEAT_TIMEOUT_MS -> "FOREGROUND_UNAVAILABLE"
        overlay != MonitorOverlayState.NOT_REQUIRED && overlay != MonitorOverlayState.ATTACHED -> "OVERLAY_$overlay"
        else -> null
    }
}

/** A dispatched start may never reach onCreate; do not leave it pending forever. */
internal class MonitorStartRetryState {
    private var lastAttemptElapsed: Long? = null

    fun claimStart(isEffective: Boolean, nowElapsed: Long): Boolean {
        if (isEffective) return false
        val previous = lastAttemptElapsed
        if (previous != null && nowElapsed - previous < MONITOR_START_RETRY_MS) return false
        lastAttemptElapsed = nowElapsed
        return true
    }
}

/** Focus-only HOME handoff state, driven by observed entries rather than a timeout. */
internal class FocusHomeExitState {
    class Request(val targetPackage: String, val foregroundEventTime: Long)

    private var pendingExit: Request? = null
    private var pendingHide: Request? = null
    val isHidePending: Boolean get() = pendingHide != null

    fun begin(targetPackage: String, foregroundEventTime: Long): Request =
        Request(targetPackage, foregroundEventTime).also {
            pendingExit = it
            pendingHide = it
        }

    fun suppressesCachedShow(targetPackage: String): Boolean =
        pendingExit?.targetPackage == targetPackage

    // Called only for RESUMED/MOVE_TO_FOREGROUND, never unrelated UsageEvents.
    // Return true when a fresh Focus presentation must replace the retiring one.
    fun onForegroundEntry(eventTime: Long, needsFocusCover: Boolean): Boolean {
        val request = pendingExit ?: pendingHide ?: return false
        if (eventTime <= request.foregroundEventTime) return false
        pendingExit = null
        if (!needsFocusCover || pendingHide == null) return false
        pendingHide = null
        return true
    }

    fun finishHide(request: Request): Boolean {
        if (pendingHide !== request) return false
        pendingHide = null
        // Hiding does not prove that the cached foreground package has changed.
        return true
    }

    fun reset() {
        pendingExit = null
        pendingHide = null
    }
}

internal data class ObservedUsageEvent(
    val time: Long,
    val packageName: String?,
    val className: String?,
    val type: Int
)

/** Reduce history to one observation; never replay historical control decisions. */
internal fun rebuildForegroundState(
    events: List<ObservedUsageEvent>,
    throughMillis: Long
): ObservedUsageEvent? {
    var foreground: ObservedUsageEvent? = null
    for (event in events.filter { it.time <= throughMillis }.sortedBy { it.time }) {
        when (event.type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                foreground = event.takeIf { !it.packageName.isNullOrBlank() }
            }
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                val current = foreground ?: continue
                val sameActivity = current.className == null || event.className == null ||
                    current.className == event.className
                if (current.packageName == event.packageName && sameActivity) foreground = null
            }
            UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> foreground = null
        }
    }
    return foreground
}

class MonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usageStatsManager: UsageStatsManager
    private var lastProcessedUsageEventTime = 0L
    private var observedForegroundPackage: String? = null
    private var isTargetAppForeground = false
    private val sessionsByPackage = mutableMapOf<String, AppSessionState>()
    @Volatile private var destroyed = false
    private var controlledPackages: Set<String> = emptySet()
    private var controlledConfigs: Map<String, ControlledAppConfig> = emptyMap()
    @Volatile private var configGeneration = 0L
    private var quotaTracker: DailyQuotaThresholdTracker? = null
    private var quotaNotificationHelper: QuotaNotificationHelper? = null
    private val quotaRuntimeStateStore = QuotaRuntimeStateStore()
    private val quotaExhaustedOverlayController by lazy { QuotaExhaustedOverlayController(applicationContext) }
    private val quotaCooldownOverlayController by lazy { QuotaCooldownOverlayController(applicationContext) }
    private val quotaExtraTimeStore by lazy { QuotaExtraTimeStore(applicationContext) }
    private val quotaThresholdStore by lazy { DailyQuotaThresholdStore(applicationContext) }
    @Volatile private var extraTimeGeneration = 0L
    private var quotaOverlayHideTask: Runnable? = null
    private var lastQuotaCheckAt = -QUOTA_CHECK_INTERVAL_MS
    private var quotaCheckInProgress = false
    private var monitoringStarted = false
    private var configLoadsPending = 0
    private val pendingQuotaConfigRechecks = mutableSetOf<String>()
    private val overlayController by lazy { OverlayController.getInstance(applicationContext) }
    private var gateRequestGeneration = 0L
    private var activeGatePresentation: Any? = null
    private var quotaUiPackageName: String? = null
    private val focusSessionStore by lazy { FocusSessionStore(applicationContext) }
    private var lastFocusSession: FocusSessionSnapshot? = null
    // Presentation cache only; the persisted Focus deadline remains the time source.
    private var lastNotificationContent: MonitorNotificationContent? = null
    private val focusOverlayController by lazy { FocusBlockedOverlayController(applicationContext) }
    private var shownFocusSession: FocusSessionSnapshot? = null
    private var focusPresentation: Any? = null
    private val focusHomeExitState = FocusHomeExitState()
    private val focusStopUsingInProgress: Boolean
        get() = focusHomeExitState.isHidePending
    private var lastForegroundEntryTime = 0L
    private var focusOverlayHideTask: Runnable? = null
    private var focusHandoffRequest: Any? = null
    private var focusHandoffIsCurrent: (() -> Boolean)? = null
    // Temporary Huawei HOME-transition diagnostics. Only log actions/state changes,
    // never each poll. Retain the event behind the cached foreground package so a
    // repeated show can be distinguished from a genuinely new foreground event.
    private var focusDiagnosticForegroundEvent: ObservedUsageEvent? = null
    private var focusHomeDiagnosticRequestId = 0L
    private var focusHomeDiagnosticRequestedAt = 0L
    private var focusHomeDiagnosticRequestedElapsed = 0L
    private var focusHomeDiagnosticTarget: String? = null
    private var recoveryDiagnosticRound = 0L
    private var lastRc001TickElapsed = -30_000L

    private fun logRecovery(stage: String, detail: String = "") {
        val message = "pid=${android.os.Process.myPid()} " +
            "service=${System.identityHashCode(this)} round=$recoveryDiagnosticRound " +
            "stage=$stage $detail"
        Log.i("FOCUS_RECOVERY_DIAG", message)
        Log.i("FOCUS_KILL_TASK_DIAG", message)
        when (stage) {
            "USAGE_EVENT", "FOREGROUND_INITIALIZED", "FOREGROUND_EVENT", "FOREGROUND_RESULT",
            "FOCUS_CHECK", "FOCUS_MATCH", "SHOW_REQUEST", "SHOW_SKIPPED", "SHOW_RETURN" ->
                Log.i("FOCUS_MULTI_APP_DIAG", "$message currentObservedPackage=$observedForegroundPackage")
        }
    }

    private val monitorTask = object : Runnable {
        override fun run() {
            if (destroyed) return
            recoveryDiagnosticRound++
            logRecovery("MONITOR_RUN", "first=${recoveryDiagnosticRound == 1L} " +
                "running=$isRunning ready=$isMonitoringReady configLoadsPending=$configLoadsPending " +
                "observed=$observedForegroundPackage")
            var pollSucceeded = false
            var foregroundRead = false
            monitorHealth = monitorHealth.copy(foregroundReadSucceeded = false,
                overlay = MonitorOverlayState.UNVERIFIED)
            try {
                refreshFocusState(System.currentTimeMillis())
                // Do not consume foreground entries against the old snapshot during a refresh.
                foregroundRead = configLoadsPending == 0 && detectLatestForegroundApp()
                // A failed catch-up query must not act on a possibly stale startup observation.
                if (foregroundRead) reconcileFocusOverlay()
                monitorHealth = monitorHealth.copy(overlay = readFocusOverlayHealth())
                if (foregroundRead) checkQuotaIfDue()
                pollSucceeded = true
            } catch (error: RuntimeException) {
                logRc001("MONITOR_POLL_FAILED", error = error)
            } finally {
                // A read/control exception must not silently terminate the polling chain.
                val posted = !destroyed && handler.postDelayed(this, POLL_INTERVAL_MS)
                monitorHealth = monitorHealth.copy(
                    initialized = monitoringStarted && configLoadsPending == 0,
                    lastPollElapsed = SystemClock.elapsedRealtime(),
                    pollSucceeded = pollSucceeded, nextPollPosted = posted
                )
                setMonitoringReady(isMonitorEffective)
                logRecovery("MONITOR_RESUBMIT", "accepted=$posted delayMs=$POLL_INTERVAL_MS foregroundRead=$foregroundRead")
            }
            val diagnosticElapsed = SystemClock.elapsedRealtime()
            if (diagnosticElapsed - lastRc001TickElapsed >= 30_000L) {
                lastRc001TickElapsed = diagnosticElapsed
                Log.i("RC001_MONITOR_TICK", "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} " +
                    "service=${System.identityHashCode(this@MonitorService)} elapsed=$diagnosticElapsed " +
                    "foregroundPackage=$observedForegroundPackage focusActive=${lastFocusSession != null} " +
                    "focusStateSource=monitor_cache focusStartedAt=${lastFocusSession?.startedAtMillis} " +
                    "focusEndsAt=${lastFocusSession?.endsAtMillis} configLoadsPending=$configLoadsPending " +
                    "running=$isRunning destroyed=$destroyed ready=$isMonitoringReady " +
                    "foregroundRead=$foregroundRead health=$monitorHealth " +
                    "unavailableReason=${monitorHealth.unavailableReason(isRunning, diagnosticElapsed)}")
            }
        }
    }

    private fun readFocusOverlayHealth(): MonitorOverlayState {
        if (!Settings.canDrawOverlays(this)) return MonitorOverlayState.PERMISSION_MISSING
        val target = observedForegroundPackage
        val coverRequired = target != null && controlledConfigs[target]?.enabled == true &&
            lastFocusSession?.let { System.currentTimeMillis() < it.endsAtMillis } == true &&
            !focusStopUsingInProgress && !focusHomeExitState.suppressesCachedShow(target)
        if (!coverRequired) return MonitorOverlayState.NOT_REQUIRED
        if (focusOverlayController.getShowingPackageName() != target) return MonitorOverlayState.FAILED
        return when {
            focusOverlayController.isAttachedAndVisible() -> MonitorOverlayState.ATTACHED
            focusOverlayController.isShowing() -> MonitorOverlayState.ATTACHING
            else -> MonitorOverlayState.FAILED
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceReference = WeakReference(this)
        Log.i("RC001_SERVICE_CREATE", "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()}")
        logRecovery("SERVICE_ON_CREATE")
        logRc001("CREATE", "service=${System.identityHashCode(this)} model=${Build.MODEL} " +
            "manufacturer=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY}")
        // Bootstrap only the history before service creation. Entries during
        // configuration loading remain live events for the existing control chain.
        lastProcessedUsageEventTime = System.currentTimeMillis() - 1L
        monitorHealth = MonitorHealthSnapshot()
        setMonitoringReady(false)
        latestPackageName = null
        latestDetectionTime = null
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        try {
            logRc001("CHANNEL_CREATE_CALL", "service=${System.identityHashCode(this)} channel=$CHANNEL_ID")
            createNotificationChannel()
            logRc001("CHANNEL_CREATE_RETURN", "service=${System.identityHashCode(this)} applicable=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O}")
            logRc001("NOTIFICATION_BUILD_CALL", "service=${System.identityHashCode(this)} id=$NOTIFICATION_ID")
            val notification = buildNotification(monitorNotificationContent(null, null, System.currentTimeMillis()))
            logRc001("NOTIFICATION_BUILD_RETURN", "service=${System.identityHashCode(this)} id=$NOTIFICATION_ID flags=${notification.flags}")
            logRc001("START_FOREGROUND_CALL", "service=${System.identityHashCode(this)} id=$NOTIFICATION_ID")
            startForeground(NOTIFICATION_ID, notification)
            // A returned API call does not prove notification visibility or continued service survival.
            logRc001("START_FOREGROUND_RETURN", "service=${System.identityHashCode(this)} id=$NOTIFICATION_ID")
        } catch (error: RuntimeException) {
            logRc001("FOREGROUND_SETUP_FAILED", "service=${System.identityHashCode(this)}", error)
            throw error
        }
        isRunning = true
        scheduleFocusMonitoringRecovery(applicationContext)
        logRecovery("SERVICE_FOREGROUND_STARTED", "running=$isRunning ready=$isMonitoringReady")
        try {
            quotaTracker = DailyQuotaThresholdTracker(applicationContext)
            quotaNotificationHelper = QuotaNotificationHelper(applicationContext)
        } catch (error: Exception) {
            Log.w(QUOTA_LOG_TAG, "Quota tracker initialization failed", error)
        }
        loadControlledApps()
    }

    private fun loadControlledApps(isRefresh: Boolean = false) {
        val appContext = applicationContext
        val startupCursor = lastProcessedUsageEventTime
        configLoadsPending++
        monitorHealth = monitorHealth.copy(initialized = false)
        setMonitoringReady(false)
        // Invalidate already queued/running quota work as soon as a refresh starts.
        val reloadGeneration = ++configGeneration
        logRecovery("CONFIG_LOAD_REQUEST", "generation=$reloadGeneration refresh=$isRefresh")
        try {
            AppDatabase.executor.execute {
                try {
                    val configs = AppDatabase.getInstance(appContext)
                        .controlledAppConfigDao().getAllControlledApps()
                    val packages = configs.filter { it.enabled && it.intentGateEnabled }
                        .map { it.packageName }.toSet()
                    val newConfigMap = configs.associateBy { it.packageName }
                    logRecovery("CONFIG_READ_COMPLETE", "generation=$reloadGeneration total=${configs.size} " +
                        "enabledCount=${configs.count { it.enabled }} gateEnabledCount=${packages.size}")
                    // Only onCreate's load performs bootstrap; later config refreshes
                    // must not overwrite live foreground/STOP_USING state.
                    val initialForeground = if (!isRefresh) readInitialForegroundState(startupCursor) else null
                    handler.post {
                        configLoadsPending--
                        if (!destroyed) {
                            // Serialize cleanup with quota writes so an obsolete task cannot restore a cleared snapshot.
                            synchronized(quotaRuntimeStateStore) {
                                val changed = quotaRuntimeStateStore.invalidateChangedConfigs(controlledConfigs, newConfigMap)
                                if (isRefresh) pendingQuotaConfigRechecks.addAll(changed)
                            }
                            controlledPackages = packages
                            controlledConfigs = newConfigMap
                            // Keep changes across overlapping refreshes until the final config is applied.
                            if (configLoadsPending == 0) {
                                val rechecks = pendingQuotaConfigRechecks.toList()
                                rechecks.forEach { packageName ->
                                    val config = newConfigMap[packageName]
                                    if (packageName !in packages || config?.dailyQuotaMinutes == null) {
                                        pendingQuotaConfigRechecks.remove(packageName)
                                    } else {
                                        // Remove only after a current result; a newer refresh may cancel this work.
                                        checkQuotaIfDue(configRecheckPackageName = packageName,
                                            onGateQuotaChecked = { pendingQuotaConfigRechecks.remove(packageName) })
                                    }
                                }
                            }
                            logRecovery("CONFIG_APPLIED", "generation=$reloadGeneration total=${newConfigMap.size} " +
                                "enabledCount=${newConfigMap.values.count { it.enabled }} pending=$configLoadsPending")
                            isTargetAppForeground = observedForegroundPackage?.let { it in packages } == true
                            val message = if (isRefresh) "controlled apps refreshed" else "loaded controlled apps"
                            Log.i(AppDatabase.CONFIG_LOG_TAG, "$message count=${packages.size}")
                            Log.i(AppDatabase.CONFIG_LOG_TAG, "CONFIG_RELOAD_COMPLETE " +
                                "generation=$reloadGeneration count=${newConfigMap.size}")
                            configs.forEach {
                                Log.i(AppDatabase.CONFIG_LOG_TAG, "CONFIG_RELOAD_COMPLETE " +
                                    "generation=$reloadGeneration packageName=${it.packageName} " +
                                    "enabled=${it.enabled} intentGateEnabled=${it.intentGateEnabled} " +
                                    "dailyQuotaMinutes=${it.dailyQuotaMinutes}")
                            }
                            if (!monitoringStarted && initialForeground != null) {
                                val foreground = initialForeground.foreground
                                val previousObservedPackage = observedForegroundPackage
                                observedForegroundPackage = foreground?.packageName
                                isTargetAppForeground = observedForegroundPackage in controlledPackages
                                lastForegroundEntryTime = foreground?.time ?: 0L
                                focusDiagnosticForegroundEvent = foreground
                                lastProcessedUsageEventTime = initialForeground.cursor
                                latestPackageName = observedForegroundPackage
                                latestDetectionTime = foreground?.let {
                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.time))
                                }
                                Log.i(LOG_TAG, "FOREGROUND_INITIALIZED package=$observedForegroundPackage " +
                                    "eventTime=${foreground?.time} cursor=$lastProcessedUsageEventTime")
                                logRecovery("FOREGROUND_INITIALIZED", "package=$observedForegroundPackage " +
                                    "eventTime=${foreground?.time} cursor=$lastProcessedUsageEventTime " +
                                    "before=$previousObservedPackage after=$observedForegroundPackage " +
                                    "triggerPackage=${foreground?.packageName} class=${foreground?.className} " +
                                    "type=${foreground?.type}")
                                monitoringStarted = true
                                // The first pass reads Focus and catches up incremental events
                                // before showing anything for the reconstructed foreground.
                                val posted = handler.post(monitorTask)
                                logRecovery("MONITOR_SUBMIT", "accepted=$posted ready=$isMonitoringReady")
                            }
                        }
                    }
                } catch (error: Exception) {
                    logRecovery("CONFIG_OR_INITIALIZATION_FAILED", "error=${error.javaClass.simpleName}: ${error.message}")
                    Log.e(AppDatabase.CONFIG_LOG_TAG, "Monitor config/foreground initialization failed", error)
                    handler.post {
                        configLoadsPending--
                        if (!destroyed && !monitoringStarted) stopSelf()
                    }
                }
            }
        } catch (error: Exception) {
            configLoadsPending--
            logRecovery("CONFIG_ENQUEUE_FAILED", "error=${error.javaClass.simpleName}: ${error.message}")
            Log.e(AppDatabase.CONFIG_LOG_TAG, "Controlled app config load enqueue failed", error)
            if (!monitoringStarted) stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logRc001("START_COMMAND", "service=${System.identityHashCode(this)} nullIntent=${intent == null} " +
            "action=${intent?.action} startId=$startId flags=$flags")
        // Read only the monitor snapshot; querying the store here could settle an expired session.
        Log.i("RC001_SERVICE_START", "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} " +
            "intent=$intent nullIntent=${intent == null} flags=$flags startId=$startId " +
            "focusStateSource=monitor_cache focusActive=${lastFocusSession?.let { System.currentTimeMillis() < it.endsAtMillis }} " +
            "focusStartedAt=${lastFocusSession?.startedAtMillis} focusEndsAt=${lastFocusSession?.endsAtMillis}")
        logRecovery("SERVICE_ON_START_COMMAND", "startId=$startId flags=$flags action=${intent?.action} " +
            "nullIntent=${intent == null} monitoringStarted=$monitoringStarted ready=$isMonitoringReady " +
            "return=START_STICKY")
        if (intent?.action == ACTION_REFRESH_CONTROLLED_APPS) {
            Log.i(AppDatabase.CONFIG_LOG_TAG, "CONFIG_REFRESH_RECEIVED startId=$startId")
            loadControlledApps(isRefresh = true)
        }
        // Keep process-death recovery sticky. Explicit stops use the Focus recovery check in onDestroy.
        // onCreate owns initialization and the single monitorTask, including null-intent restarts.
        logRc001("START_COMMAND_RETURN", "service=${System.identityHashCode(this)} startId=$startId result=START_STICKY")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun recoverMonitoring() {
        if (destroyed || isMonitorEffective) return
        logRc001("RECOVERY_IN_PLACE", "health=$monitorHealth pending=$configLoadsPending " +
            "reason=${monitorHealth.unavailableReason(isRunning, SystemClock.elapsedRealtime())}")
        // Do not queue duplicate initialization or overwrite the live foreground/session state.
        if (configLoadsPending != 0) return
        if (!monitoringStarted) {
            loadControlledApps()
            return
        }
        handler.removeCallbacks(monitorTask)
        val posted = handler.post(monitorTask)
        monitorHealth = monitorHealth.copy(nextPollPosted = posted)
        logRc001("RECOVERY_POLL_REARMED", "accepted=$posted")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        logRc001("TASK_REMOVED", "service=${System.identityHashCode(this)} running=$isRunning " +
            "ready=$isMonitoringReady focusStateSource=monitor_cache focusEndsAt=${lastFocusSession?.endsAtMillis}")
        super.onTaskRemoved(rootIntent)
        scheduleFocusMonitoringRecovery(applicationContext)
    }

    override fun onDestroy() {
        logRc001("DESTROY_BEGIN", "service=${System.identityHashCode(this)} running=$isRunning " +
            "ready=$isMonitoringReady focusStateSource=monitor_cache focusEndsAt=${lastFocusSession?.endsAtMillis}")
        Log.i("RC001_SERVICE_DESTROY", "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} " +
            "running=$isRunning foregroundPackage=$observedForegroundPackage")
        logRecovery("SERVICE_ON_DESTROY", "running=$isRunning ready=$isMonitoringReady")
        destroyed = true
        if (serviceReference?.get() === this) serviceReference = null
        monitorHealth = MonitorHealthSnapshot()
        setMonitoringReady(false)
        handler.removeCallbacks(monitorTask)
        lastFocusSession = null
        focusOverlayHideTask?.let { handler.removeCallbacks(it) }
        focusOverlayHideTask = null
        focusHomeExitState.reset()
        hideFocusOverlay()
        quotaOverlayHideTask?.let { handler.removeCallbacks(it) }
        quotaOverlayHideTask = null
        quotaExhaustedOverlayController.hide()
        quotaCooldownOverlayController.hide()
        isRunning = false
        logRc001("STOP_FOREGROUND_CALL", "service=${System.identityHashCode(this)} removeNotification=true")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        logRc001("STOP_FOREGROUND_RETURN", "service=${System.identityHashCode(this)}")
        super.onDestroy()
        scheduleFocusMonitoringRecovery(applicationContext)
        logRc001("DESTROY_END", "service=${System.identityHashCode(this)} running=$isRunning")
    }

    private fun refreshFocusState(nowMillis: Long) {
        logRecovery("FOCUS_READ", "now=$nowMillis destroyed=$destroyed")
        if (destroyed) return
        try {
            val session = focusSessionStore.getActiveSession(nowMillis)
            updateMonitorNotification(session, nowMillis)
            logRecovery("FOCUS_STATE", "active=${session != null} startedAt=${session?.startedAtMillis} " +
                "endsAtMillis=${session?.endsAtMillis}")
            // Snapshot equality compares both timestamps, including active-to-active replacement.
            if (session == lastFocusSession) return
            lastFocusSession = session
            if (session == null) {
                Log.i("SELF_CONTROL_FOCUS", "FOCUS_STATE_CHANGED active=false")
            } else {
                val remainingMillis = (session.endsAtMillis - nowMillis).coerceAtLeast(0L)
                Log.i("SELF_CONTROL_FOCUS", "FOCUS_STATE_CHANGED active=true " +
                    "startedAtMillis=${session.startedAtMillis} endsAtMillis=${session.endsAtMillis} " +
                    "remainingMillis=$remainingMillis")
            }
        } catch (error: Exception) {
            logRecovery("FOCUS_READ_FAILED", "error=${error.javaClass.simpleName}: ${error.message}")
            Log.w("SELF_CONTROL_FOCUS", "FOCUS_STATE_CHECK_FAILED", error)
        }
    }

    private fun blockForFocus(targetPackage: String, source: String = "access_callback"): Boolean {
        logRecovery("FOCUS_CHECK", "target=$targetPackage source=$source observed=$observedForegroundPackage " +
            "enabled=${controlledConfigs[targetPackage]?.enabled} destroyed=$destroyed stopping=$focusStopUsingInProgress")
        if (destroyed) return true
        if (focusStopUsingInProgress) return true
        val config = controlledConfigs[targetPackage] ?: return false
        if (!config.enabled) return false
        // Also called immediately before Continue/Session approval, not just by the poll.
        refreshFocusState(System.currentTimeMillis())
        val session = lastFocusSession ?: return false
        logRecovery("FOCUS_MATCH", "target=$targetPackage endsAtMillis=${session.endsAtMillis} " +
            "isForeground=${observedForegroundPackage == targetPackage}")
        if (observedForegroundPackage == targetPackage) {
            showFocusBlockedOverlay(targetPackage, config, session, source)
        }
        return true
    }

    private fun showFocusBlockedOverlay(
        targetPackage: String,
        config: ControlledAppConfig,
        session: FocusSessionSnapshot,
        source: String
    ) {
        if (destroyed || focusStopUsingInProgress) {
            logRecovery("SHOW_SKIPPED", "target=$targetPackage destroyed=$destroyed stopping=$focusStopUsingInProgress")
            return
        }
        // Keep Focus's access decision intact; only suppress redisplaying a cover
        // from the foreground cache that the user has just asked to leave.
        if (focusHomeExitState.suppressesCachedShow(targetPackage)) {
            logRecovery("SHOW_SKIPPED", "target=$targetPackage reason=home_exit_old_cache")
            return
        }
        if (focusOverlayController.isShowing() &&
            focusOverlayController.getShowingPackageName() == targetPackage && shownFocusSession == session
        ) {
            logRecovery("SHOW_SKIPPED", "target=$targetPackage reason=already_showing_same_session")
            return
        }
        logRecovery("SHOW_REQUEST", "target=$targetPackage source=$source observed=$observedForegroundPackage " +
            "endsAtMillis=${session.endsAtMillis}")
        logFocusHomeTransition("SHOW_REQUEST", "source=$source target=$targetPackage " +
            "startedAt=${session.startedAtMillis} endsAt=${session.endsAtMillis}")
        focusHandoffRequest = null
        focusHandoffIsCurrent = null
        // UI and pending Gate requests only; retain all Session/quota data.
        hideIntentGateForQuota()
        quotaOverlayHideTask?.let { handler.removeCallbacks(it) }
        quotaOverlayHideTask = null
        quotaExhaustedOverlayController.hide()
        quotaCooldownOverlayController.hide()
        quotaUiPackageName = null
        val presentation = Any()
        focusPresentation = presentation
        shownFocusSession = session
        Log.i("SELF_CONTROL_FOCUS", "FOCUS_BLOCKS_APP packageName=$targetPackage endsAtMillis=${session.endsAtMillis}")
        focusOverlayController.show(targetPackage, config.displayName, session) {
            if (!destroyed && focusPresentation === presentation) stopFocusAppUsage(targetPackage)
        }
        val showing = focusOverlayController.isShowing()
        val showingPackage = focusOverlayController.getShowingPackageName()
        logRecovery("SHOW_RETURN", "target=$targetPackage showing=$showing showingPackage=$showingPackage")
        // Controller state includes a registered window awaiting first attach; not proof of rendered pixels.
        logRecovery(if (showing && showingPackage == targetPackage) "SHOW_SUCCESS" else "SHOW_FAILED",
            "target=$targetPackage source=$source showing=$showing showingPackage=$showingPackage " +
                "verification=controller_state endsAtMillis=${session.endsAtMillis}")
    }

    private fun hideFocusOverlay() {
        logRecovery("HIDE_REQUEST", "observed=$observedForegroundPackage " +
            "hasPresentation=${focusPresentation != null} endsAt=${shownFocusSession?.endsAtMillis}")
        if (focusOverlayController.isShowing() || focusPresentation != null) {
            logFocusHomeTransition("HIDE_REQUEST")
        }
        focusPresentation = null
        shownFocusSession = null
        focusHandoffRequest = null
        focusHandoffIsCurrent = null
        focusOverlayController.hide()
        logRecovery("HIDE_RETURN", "showing=${focusOverlayController.isShowing()}")
    }

    private fun reconcileFocusOverlay() {
        if (destroyed || configLoadsPending != 0 || focusStopUsingInProgress) return
        val targetPackage = observedForegroundPackage
        if (targetPackage != null && blockForFocus(targetPackage, "reconcile")) return
        if (!focusOverlayController.isShowing()) return
        if (targetPackage == null || controlledConfigs[targetPackage]?.enabled != true ||
            focusOverlayController.getShowingPackageName() != targetPackage
        ) {
            Log.i("SELF_CONTROL_FOCUS", "FOCUS_HANDOFF packageName=${focusOverlayController.getShowingPackageName()} result=NOT_FOREGROUND")
            hideFocusOverlay()
            // The foreground event was held while the previous package's cover was visible.
            if (targetPackage != null && targetPackage in controlledPackages) {
                evaluateIntentGate(targetPackage, System.currentTimeMillis())
            }
            return
        }
        if (focusHandoffIsCurrent?.invoke() == true) return
        val request = Any()
        val configVersion = configGeneration
        val gateVersion = gateRequestGeneration
        val extraVersion = extraTimeGeneration
        focusHandoffRequest = request
        val isCurrent = {
            !destroyed && focusHandoffRequest === request && configLoadsPending == 0 &&
                configVersion == configGeneration && gateVersion == gateRequestGeneration &&
                extraVersion == extraTimeGeneration && observedForegroundPackage == targetPackage
        }
        focusHandoffIsCurrent = isCurrent
        var awaitingQuotaCheck = false
        Log.i("SELF_CONTROL_FOCUS", "FOCUS_ENDED_REEVALUATE packageName=$targetPackage")
        evaluateIntentGate(targetPackage, System.currentTimeMillis(),
            onDecision = { result ->
                // Quota takeover invalidates Gate tokens itself, so check request identity here.
                if (focusHandoffRequest === request && !destroyed && !focusStopUsingInProgress &&
                    observedForegroundPackage == targetPackage && !blockForFocus(targetPackage)
                ) {
                    Log.i("SELF_CONTROL_FOCUS", "FOCUS_HANDOFF packageName=$targetPackage result=$result")
                    hideFocusOverlay()
                }
            },
            isDecisionCurrent = isCurrent,
            onCheckStarted = { awaitingQuotaCheck = true },
            onCheckFinished = {
                // Failure/stale/missing quota results keep the cover; the existing loop may retry.
                if (focusHandoffRequest === request) {
                    focusHandoffRequest = null
                    focusHandoffIsCurrent = null
                }
            }
        )
        // Synchronous failures (e.g. inability to attach Gate) can retry on the next poll.
        if (focusHandoffRequest === request && !awaitingQuotaCheck) {
            focusHandoffRequest = null
            focusHandoffIsCurrent = null
        }
    }

    private fun stopFocusAppUsage(targetPackage: String) {
        if (destroyed || focusStopUsingInProgress) return
        focusHomeDiagnosticRequestId++
        focusHomeDiagnosticRequestedAt = System.currentTimeMillis()
        focusHomeDiagnosticRequestedElapsed = SystemClock.elapsedRealtime()
        focusHomeDiagnosticTarget = targetPackage
        val homeExit = focusHomeExitState.begin(targetPackage, lastForegroundEntryTime)
        focusHandoffRequest = null
        focusHandoffIsCurrent = null
        val presentation = focusPresentation
        try {
            logFocusHomeTransition("STOP_USING")
            Log.i("SELF_CONTROL_FOCUS", "FOCUS_STOP_USING packageName=$targetPackage")
            // Existing explicit HOME resolution; it does not hide windows or mutate Sessions.
            launchHome()
        } finally {
            // launchHome returning is not proof that HOME has become foreground.
            logFocusHomeTransition("HOME_CALL_FINISHED")
            val hideOverlay = Runnable {
                // A genuine foreground entry (or a newer STOP_USING) may have
                // superseded this request, even if this callback was already queued.
                if (!focusHomeExitState.finishHide(homeExit)) return@Runnable
                logFocusHomeTransition("DELAYED_HIDE_BEGIN",
                    "samePresentation=${focusPresentation === presentation}")
                focusOverlayHideTask = null
                if (focusPresentation === presentation) hideFocusOverlay()
                logFocusHomeTransition("DELAYED_HIDE_END")
            }
            focusOverlayHideTask = hideOverlay
            if (!handler.postDelayed(hideOverlay, 200L)) hideOverlay.run()
        }
    }

    private fun logFocusHomeTransition(stage: String, detail: String = "") {
        if (focusHomeDiagnosticRequestId == 0L) return
        val event = focusDiagnosticForegroundEvent
        Log.i("SELF_CONTROL_FOCUS", "FOCUS_HOME_DIAG stage=$stage " +
            "requestId=$focusHomeDiagnosticRequestId stopTarget=$focusHomeDiagnosticTarget " +
            "requestTime=$focusHomeDiagnosticRequestedAt " +
            "elapsedMs=${SystemClock.elapsedRealtime() - focusHomeDiagnosticRequestedElapsed} " +
            "observed=$observedForegroundPackage eventTime=${event?.time} " +
            "eventPackage=${event?.packageName} eventClass=${event?.className} eventType=${event?.type} " +
            "cursor=$lastProcessedUsageEventTime stopping=$focusStopUsingInProgress " +
            "showing=${focusOverlayController.isShowing()} " +
            "showingPackage=${focusOverlayController.getShowingPackageName()} " +
            "presentation=${focusPresentation?.let { System.identityHashCode(it) }} $detail")
    }

    private enum class AccessDecision { QUOTA, GATE, ALLOW, NOT_FOREGROUND }

    private fun checkQuotaIfDue(
        extraTimeRecheckPackageName: String? = null,
        gateRecheckPackageName: String? = null,
        onGateQuotaChecked: (() -> Unit)? = null,
        onCheckFinished: (() -> Unit)? = null,
        configRecheckPackageName: String? = null
    ) {
        val isExtraTimeRecheck = extraTimeRecheckPackageName != null
        val isConfigRecheck = configRecheckPackageName != null
        val isStateOnlyRecheck = isExtraTimeRecheck || gateRecheckPackageName != null || isConfigRecheck
        var enqueued = false
        try {
            if (destroyed || configLoadsPending != 0) return
            if (!isStateOnlyRecheck && quotaCheckInProgress) return
            val tracker = quotaTracker
            val notificationHelper = quotaNotificationHelper
            if (!isStateOnlyRecheck && (tracker == null || notificationHelper == null)) return
            val checkAt = SystemClock.elapsedRealtime()
            if (!isStateOnlyRecheck && checkAt - lastQuotaCheckAt < QUOTA_CHECK_INTERVAL_MS) return
            val targetPackage = configRecheckPackageName ?: extraTimeRecheckPackageName ?:
                gateRecheckPackageName ?: observedForegroundPackage ?: return
            if (targetPackage !in controlledPackages) return
            // Resolve the current config on the main thread for both regular checks and grant rechecks.
            val config = controlledConfigs[targetPackage] ?: return
            if (!config.enabled || config.dailyQuotaMinutes == null) return
            val baseDailyQuotaMinutes = config.dailyQuotaMinutes
            val quotaConfigGeneration = configGeneration
            val quotaExtraTimeGeneration = extraTimeGeneration
            val quotaGateRequestGeneration = gateRequestGeneration

            val appContext = applicationContext
            // Rechecks join the same single-thread executor even if a regular check is pending.
            // Only regular checks own the throttle timestamp and quotaCheckInProgress flag.
            if (!isStateOnlyRecheck) {
                lastQuotaCheckAt = checkAt
                quotaCheckInProgress = true
            }
            AppDatabase.executor.execute {
                try {
                    if (destroyed || quotaConfigGeneration != configGeneration ||
                        quotaExtraTimeGeneration != extraTimeGeneration
                    ) return@execute
                    val now = System.currentTimeMillis()
                    val usageMillis = TodayUsageStatsReader.getTodayUsageMillis(
                        appContext,
                        listOf(targetPackage)
                    )[targetPackage] ?: 0L
                    if (destroyed || quotaConfigGeneration != configGeneration ||
                        quotaExtraTimeGeneration != extraTimeGeneration
                    ) return@execute
                    val extraMinutes = quotaExtraTimeStore.getExtraMinutes(targetPackage, now)
                    val effectiveQuotaMinutes = calculateEffectiveQuotaMinutes(baseDailyQuotaMinutes, extraMinutes)
                        ?: return@execute
                    val canPostNotifications = if (isStateOnlyRecheck) false
                        else checkNotNull(notificationHelper).canPostQuotaNotifications()
                    val quotaState = calculateDailyQuotaState(usageMillis, effectiveQuotaMinutes)
                    synchronized(quotaRuntimeStateStore) {
                        if (destroyed || quotaConfigGeneration != configGeneration ||
                            quotaExtraTimeGeneration != extraTimeGeneration
                        ) return@execute
                        val previousSnapshot = quotaRuntimeStateStore.getFreshSnapshot(targetPackage, now)
                        val oldState = previousSnapshot?.state
                        val currentSnapshot = quotaRuntimeStateStore.update(
                            packageName = targetPackage,
                            state = quotaState,
                            todayUsageMillis = usageMillis,
                            // This snapshot stores the effective runtime limit, not the configured base.
                            dailyQuotaMinutes = effectiveQuotaMinutes,
                            nowMillis = now
                        )
                        if (oldState != quotaState) {
                            Log.i(QUOTA_LOG_TAG, "QUOTA_RUNTIME_STATE_UPDATED packageName=$targetPackage " +
                                "oldState=$oldState newState=$quotaState " +
                                "todayUsageMinutes=${usageMillis / 60_000L} effectiveQuotaMinutes=$effectiveQuotaMinutes")
                        }
                        if (isExtraTimeRecheck) {
                            Log.i(QUOTA_LOG_TAG, "QUOTA_EXTRA_TIME_RECHECK packageName=$targetPackage " +
                                "todayUsageMinutes=${usageMillis / 60_000L} " +
                                "baseDailyQuotaMinutes=$baseDailyQuotaMinutes extraMinutes=$extraMinutes " +
                                "effectiveQuotaMinutes=$effectiveQuotaMinutes quotaState=$quotaState")
                            // This recheck follows a grant from the exhausted flow; the old snapshot was cleared.
                            if (quotaState == DailyQuotaState.WITHIN_QUOTA) {
                                Log.i(QUOTA_LOG_TAG, "QUOTA_RELIEVED packageName=$targetPackage " +
                                    "effectiveQuotaMinutes=$effectiveQuotaMinutes todayUsageMinutes=${usageMillis / 60_000L}")
                                handler.post {
                                    resumeGateAfterQuotaRelief(targetPackage, quotaConfigGeneration,
                                        quotaExtraTimeGeneration, quotaGateRequestGeneration)
                                }
                            }
                        }
                        if (isConfigRecheck && quotaState == DailyQuotaState.WITHIN_QUOTA) {
                            handler.post {
                                resumeGateAfterQuotaRelief(targetPackage, quotaConfigGeneration,
                                    quotaExtraTimeGeneration, quotaGateRequestGeneration)
                            }
                        }
                        if (destroyed || quotaConfigGeneration != configGeneration) return@execute
                        val event = createQuotaExhaustedEventIfNeeded(previousSnapshot, currentSnapshot, now)
                        if (event != null) {
                            Log.i(QUOTA_LOG_TAG, "QUOTA_EXHAUSTED_EVENT packageName=${event.packageName} " +
                                "todayUsageMillis=${event.todayUsageMillis} " +
                                "todayUsageMinutes=${event.todayUsageMillis / 60_000L} " +
                                "dailyQuotaMinutes=${event.dailyQuotaMinutes} " +
                                "triggeredAtMillis=${event.triggeredAtMillis}")
                            handler.post {
                                if (!destroyed && quotaConfigGeneration == configGeneration &&
                                    quotaExtraTimeGeneration == extraTimeGeneration &&
                                    (!isConfigRecheck || observedForegroundPackage == targetPackage)
                                ) {
                                    showQuotaExhaustedOverlay(event, config.displayName)
                                }
                            }
                        }
                    }
                    if (onGateQuotaChecked != null) {
                        handler.post {
                            if (!destroyed && quotaConfigGeneration == configGeneration &&
                                quotaExtraTimeGeneration == extraTimeGeneration
                            ) onGateQuotaChecked()
                        }
                    }
                    // State-only rechecks do not consume thresholds or change the regular check timer.
                    if (isStateOnlyRecheck) return@execute
                    // Serialize threshold consumption with grant/reset so an old check cannot restore REACHED_100.
                    synchronized(quotaRuntimeStateStore) {
                        if (destroyed || quotaConfigGeneration != configGeneration ||
                            quotaExtraTimeGeneration != extraTimeGeneration
                        ) return@execute
                        Log.i(QUOTA_LOG_TAG, "QUOTA_CHECK packageName=$targetPackage " +
                            "todayUsageMillis=$usageMillis todayUsageMinutes=${usageMillis / 60_000L} " +
                            "baseDailyQuotaMinutes=$baseDailyQuotaMinutes extraMinutes=$extraMinutes " +
                            "effectiveQuotaMinutes=$effectiveQuotaMinutes " +
                            "quotaUsagePercent=${calculateQuotaUsagePercent(usageMillis, effectiveQuotaMinutes)} " +
                            "currentThreshold=${calculateDailyQuotaThreshold(usageMillis, effectiveQuotaMinutes)} " +
                            "quotaState=$quotaState " +
                            "canPostQuotaNotifications=$canPostNotifications")
                        if (quotaState == DailyQuotaState.EXHAUSTED) {
                            Log.i(QUOTA_LOG_TAG, "QUOTA_EXHAUSTED packageName=$targetPackage " +
                                "todayUsageMillis=$usageMillis todayUsageMinutes=${usageMillis / 60_000L} " +
                                "effectiveQuotaMinutes=$effectiveQuotaMinutes")
                        }
                        if (!canPostNotifications) {
                            Log.d(QUOTA_LOG_TAG, "NOTIFICATION_UNAVAILABLE packageName=$targetPackage " +
                                "thresholdNotConsumed=true")
                            return@execute
                        }
                        Log.i(AppDatabase.CONFIG_LOG_TAG, "QUOTA_CONFIG_USED " +
                            "generation=$quotaConfigGeneration packageName=$targetPackage " +
                            "baseDailyQuotaMinutes=$baseDailyQuotaMinutes extraMinutes=$extraMinutes " +
                            "effectiveQuotaMinutes=$effectiveQuotaMinutes")
                        val threshold = checkNotNull(tracker).consumeNewlyReachedThreshold(
                            packageName = targetPackage,
                            todayUsageMillis = usageMillis,
                            dailyQuotaMinutes = effectiveQuotaMinutes,
                            nowMillis = now
                        )
                        if (threshold != null) {
                            Log.i(QUOTA_LOG_TAG, "Quota threshold reached: package=$targetPackage " +
                                "todayUsageMillis=$usageMillis effectiveQuotaMinutes=$effectiveQuotaMinutes " +
                                "reachedThreshold=$threshold")
                            checkNotNull(notificationHelper).showQuotaThresholdNotification(
                                packageName = targetPackage,
                                displayName = config.displayName,
                                todayUsageMillis = usageMillis,
                                dailyQuotaMinutes = effectiveQuotaMinutes,
                                threshold = threshold
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (isExtraTimeRecheck) {
                        Log.w(QUOTA_LOG_TAG, "QUOTA_EXTRA_TIME_RECHECK_FAILED packageName=$targetPackage " +
                            "error=${error.javaClass.simpleName}: ${error.message}", error)
                    } else if (gateRecheckPackageName != null) {
                        Log.w(QUOTA_LOG_TAG, "QUOTA_GATE_RECHECK_FAILED packageName=$targetPackage " +
                            "error=${error.javaClass.simpleName}: ${error.message}", error)
                    } else {
                        Log.w(QUOTA_LOG_TAG, "Quota check failed package=$targetPackage", error)
                    }
                } finally {
                    if (onCheckFinished != null) handler.post { onCheckFinished() }
                    if (!isStateOnlyRecheck) {
                        handler.post {
                            lastQuotaCheckAt = SystemClock.elapsedRealtime()
                            quotaCheckInProgress = false
                        }
                    }
                }
            }
            enqueued = true
        } catch (error: Exception) {
            if (isExtraTimeRecheck) {
                Log.w(QUOTA_LOG_TAG, "QUOTA_EXTRA_TIME_RECHECK_FAILED packageName=$extraTimeRecheckPackageName " +
                    "error=${error.javaClass.simpleName}: ${error.message}", error)
            } else if (gateRecheckPackageName != null) {
                Log.w(QUOTA_LOG_TAG, "QUOTA_GATE_RECHECK_FAILED packageName=$gateRecheckPackageName " +
                    "error=${error.javaClass.simpleName}: ${error.message}", error)
            } else {
                quotaCheckInProgress = false
                Log.w(QUOTA_LOG_TAG, "Quota check enqueue failed", error)
            }
        } finally {
            if (!enqueued) onCheckFinished?.invoke()
        }
    }

    private fun resumeGateAfterQuotaRelief(
        targetPackage: String,
        quotaConfigGeneration: Long,
        quotaExtraTimeGeneration: Long,
        quotaGateRequestGeneration: Long
    ) {
        if (destroyed || configLoadsPending != 0 || quotaConfigGeneration != configGeneration ||
            quotaExtraTimeGeneration != extraTimeGeneration
        ) return
        if (observedForegroundPackage != targetPackage) {
            Log.i(QUOTA_LOG_TAG, "QUOTA_RESUME_GATE_SKIPPED packageName=$targetPackage reason=not_foreground")
            return
        }
        if (quotaGateRequestGeneration != gateRequestGeneration || targetPackage !in controlledPackages) return
        val config = controlledConfigs[targetPackage] ?: return
        if (!config.enabled || !config.intentGateEnabled) return
        synchronized(quotaRuntimeStateStore) {
            // A newer check may have exhausted the quota again before this main-thread callback runs.
            val nowMillis = System.currentTimeMillis()
            if (quotaRuntimeStateStore.getFreshSnapshot(targetPackage, nowMillis)?.state != DailyQuotaState.WITHIN_QUOTA) return
            if (quotaUiPackageName == targetPackage) {
                quotaOverlayHideTask?.let { handler.removeCallbacks(it) }
                quotaOverlayHideTask = null
                quotaExhaustedOverlayController.hide()
                quotaCooldownOverlayController.hide()
                quotaUiPackageName = null
            }
            if (quotaExhaustedOverlayController.isShowing() || quotaCooldownOverlayController.isShowing()) return
            Log.i(QUOTA_LOG_TAG, "QUOTA_RESUME_GATE_EVALUATION packageName=$targetPackage")
            evaluateIntentGate(targetPackage, nowMillis)
        }
    }

    private fun showQuotaExhaustedOverlay(event: QuotaExhaustedEvent, displayName: String) {
        try {
            if (blockForFocus(event.packageName)) return
            // An old posted quota event must not cover Focus for a different foreground app.
            if (focusOverlayController.isShowing() && observedForegroundPackage != event.packageName) return
            hideIntentGateForQuota()
            quotaOverlayHideTask?.let { handler.removeCallbacks(it) }
            quotaOverlayHideTask = null
            // A new exhaustion event replaces any active cooldown instead of stacking quota windows.
            quotaCooldownOverlayController.hide()
            quotaUiPackageName = event.packageName
            val nowMillis = System.currentTimeMillis()
            val config = controlledConfigs[event.packageName]
            val enforcementMode = parseQuotaEnforcementMode(config?.quotaEnforcementMode)
            val hasUsedGrantToday = quotaExtraTimeStore.hasUsedGrantToday(event.packageName, nowMillis)
            val canRequestExtraTime = enforcementMode == QuotaEnforcementMode.MODERATE && !hasUsedGrantToday
            val unavailableReason = when {
                enforcementMode == QuotaEnforcementMode.STRICT -> ExtraTimeUnavailableReason.STRICT_MODE
                hasUsedGrantToday -> ExtraTimeUnavailableReason.ALREADY_USED_TODAY
                else -> null
            }
            val currentDisplayName = config?.displayName ?: displayName
            Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_EXTRA_TIME_AVAILABILITY " +
                "packageName=${event.packageName} enforcementMode=$enforcementMode " +
                "hasUsedGrantToday=$hasUsedGrantToday canRequestExtraTime=$canRequestExtraTime")
            quotaExhaustedOverlayController.show(event, currentDisplayName, canRequestExtraTime, unavailableReason) quotaAction@ { action ->
                if (!destroyed) {
                    if (blockForFocus(event.packageName)) return@quotaAction
                    Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_OVERLAY_ACTION " +
                        "packageName=${event.packageName} action=$action")
                    when (action) {
                        QuotaExhaustedAction.STOP_USING -> stopQuotaAppUsage(event.packageName)
                        QuotaExhaustedAction.REQUEST_EXTRA_TIME -> {
                            // Both calls run on the main thread; hide removes the old window immediately.
                            quotaExhaustedOverlayController.hide()
                            quotaCooldownOverlayController.show(
                                packageName = event.packageName,
                                displayName = currentDisplayName,
                                durationSeconds = 30,
                                onCompleted = {
                                    Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_COOLDOWN_COMPLETION_CALLBACK " +
                                        "packageName=${event.packageName}")
                                },
                                onExtraTimeSelected = { extraTimeAction ->
                                    if (!destroyed) {
                                        Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_EXTRA_TIME_SELECTED " +
                                            "packageName=${event.packageName} action=$extraTimeAction")
                                        grantQuotaExtraTime(event.packageName, extraTimeAction)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.w("SELF_CONTROL_QUOTA_UI", "QUOTA_OVERLAY_SHOW_FAILED " +
                "packageName=${event.packageName}", error)
        }
    }

    private fun hideIntentGateForQuota() {
        // Invalidate pending quota checks and callbacks belonging to the old Gate before removing it.
        gateRequestGeneration++
        activeGatePresentation = null
        overlayController.hide()
    }

    private fun grantQuotaExtraTime(targetPackageName: String, action: QuotaExtraTimeAction) {
        if (destroyed) return
        if (blockForFocus(targetPackageName)) return
        try {
            val addedMinutes = when (action) {
                QuotaExtraTimeAction.ADD_5_MINUTES -> 5
                QuotaExtraTimeAction.ADD_10_MINUTES -> 10
            }
            synchronized(quotaRuntimeStateStore) {
                val nowMillis = System.currentTimeMillis()
                val result = quotaExtraTimeStore.tryGrantExtraMinutes(
                    packageName = targetPackageName,
                    minutes = addedMinutes,
                    nowMillis = nowMillis
                )
                if (result.status != QuotaExtraTimeGrantStatus.GRANTED) {
                    Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_EXTRA_TIME_GRANT_DENIED " +
                        "packageName=$targetPackageName reason=${result.status}")
                    if (observedForegroundPackage == targetPackageName && targetPackageName in controlledPackages) {
                        // Reuse the quota-first presentation with the latest same-day snapshot.
                        // A denial must neither recheck usage nor approve/reuse a Session.
                        withQuotaPermissionForGate(targetPackageName, recheckIfMissing = false) { }
                    }
                    return
                }
                val totalExtraMinutes = result.totalExtraMinutes
                // Reject checks and posted exhaustion events that captured the old effective quota.
                extraTimeGeneration++
                try {
                    quotaThresholdStore.resetForPackage(targetPackageName)
                } finally {
                    quotaRuntimeStateStore.clear(targetPackageName)
                }
                val baseDailyQuotaMinutes = controlledConfigs[targetPackageName]?.dailyQuotaMinutes
                val effectiveQuotaMinutes = calculateEffectiveQuotaMinutes(baseDailyQuotaMinutes, totalExtraMinutes)
                Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_EXTRA_TIME_GRANTED " +
                    "packageName=$targetPackageName addedMinutes=$addedMinutes totalExtraMinutes=$totalExtraMinutes " +
                    "baseDailyQuotaMinutes=$baseDailyQuotaMinutes effectiveQuotaMinutes=$effectiveQuotaMinutes")
            }
            checkQuotaIfDue(extraTimeRecheckPackageName = targetPackageName)
        } catch (error: Exception) {
            Log.w("SELF_CONTROL_QUOTA_UI", "QUOTA_EXTRA_TIME_GRANT_FAILED " +
                "packageName=$targetPackageName action=$action " +
                "error=${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private fun stopQuotaAppUsage(targetPackageName: String) {
        // Called on the main thread, with the quota overlay still visible.
        try {
            hideIntentGateForQuota()
            if (!overlayController.isShowing()) {
                Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_STOP_USING_GATE_HIDDEN packageName=$targetPackageName")
            }
            Log.i("SELF_CONTROL_QUOTA_UI", "QUOTA_STOP_USING_HOME_REQUEST packageName=$targetPackageName")
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val activity = checkNotNull(
                packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ) { "HOME activity not found" }
            // Match the existing HOME path: reject a resolver/chooser, then launch the explicit component.
            val isHomeActivity = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == activity.packageName && it.activityInfo.name == activity.name }
            check(isHomeActivity) { "Resolved component is not a HOME activity" }
            val explicitHomeIntent = Intent(homeIntent).apply {
                component = ComponentName(activity.packageName, activity.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            applicationContext.startActivity(explicitHomeIntent)
        } catch (error: Exception) {
            Log.w("SELF_CONTROL_QUOTA_UI", "QUOTA_STOP_USING_HOME_FAILED packageName=$targetPackageName " +
                "error=${error.javaClass.simpleName}: ${error.message}", error)
        } finally {
            // Match the verified Abandon timing without touching Gate or Session state.
            val hideOverlay = Runnable {
                quotaOverlayHideTask = null
                quotaExhaustedOverlayController.hide()
            }
            quotaOverlayHideTask = hideOverlay
            if (!handler.postDelayed(hideOverlay, 200L)) {
                hideOverlay.run()
            }
        }
    }

    private data class InitialForegroundState(val foreground: ObservedUsageEvent?, val cursor: Long)

    private fun readInitialForegroundState(cursor: Long): InitialForegroundState {
        val endExclusive = cursor + 1L
        logRecovery("FOREGROUND_INITIALIZATION_QUERY", "endExclusive=$endExclusive")
        // Request the OS-retained history, rather than guessing a recent lookback
        // that could omit an app which has been foreground for a long time.
        val events = checkNotNull(usageStatsManager.queryEvents(0L, endExclusive)) {
            "UsageEvents unavailable during foreground initialization"
        }
        val event = UsageEvents.Event()
        val records = mutableListOf<ObservedUsageEvent>()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            when (event.eventType) {
                FOREGROUND_EVENT_TYPE, BACKGROUND_EVENT_TYPE, UsageEvents.Event.ACTIVITY_STOPPED,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> {
                    records.add(ObservedUsageEvent(event.timeStamp, event.packageName, event.className, event.eventType))
                }
            }
        }
        // Incremental reads use > cursor; include every event at the query's
        // exclusive end in the next batch instead of losing that millisecond.
        val initial = InitialForegroundState(rebuildForegroundState(records, cursor), cursor)
        logRecovery("FOREGROUND_INITIALIZATION_RESULT", "eventCount=${records.size} " +
            "package=${initial.foreground?.packageName} eventTime=${initial.foreground?.time} cursor=$cursor")
        return initial
    }

    private fun setMonitoringReady(ready: Boolean) {
        if (isMonitoringReady == ready) return
        isMonitoringReady = ready
        logRecovery("MONITOR_READY_CHANGED", "ready=$ready configLoadsPending=$configLoadsPending")
        Log.i(LOG_TAG, "MONITOR_READY_CHANGED ready=$ready configLoadsPending=$configLoadsPending")
    }

    private fun detectLatestForegroundApp(): Boolean {
        val endTime = System.currentTimeMillis()
        logRecovery("FOREGROUND_QUERY", "from=$lastProcessedUsageEventTime to=$endTime observed=$observedForegroundPackage")
        if (!hasUsageAccessPermission(this)) {
            logRecovery("FOREGROUND_QUERY_UNAVAILABLE", "reason=usage_permission_missing")
            return false
        }
        val events = try {
            usageStatsManager.queryEvents(lastProcessedUsageEventTime, endTime)
        } catch (error: RuntimeException) {
            logRecovery("FOREGROUND_QUERY_FAILED", "error=${error.javaClass.simpleName}: ${error.message}")
            Log.w(LOG_TAG, "FOREGROUND_QUERY_FAILED", error)
            return false
        } ?: run {
            logRecovery("FOREGROUND_QUERY_UNAVAILABLE", "observed=$observedForegroundPackage")
            return false
        }

        val event = UsageEvents.Event()
        val newEvents = mutableListOf<ObservedUsageEvent>()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            if (event.timeStamp > lastProcessedUsageEventTime) {
                // Snapshot before advancing the cursor, including events sharing a timestamp.
                newEvents.add(ObservedUsageEvent(event.timeStamp, event.packageName, event.className, event.eventType))
            }
        }

        // An empty batch is valid when the user stays in one app; event age is not a heartbeat.
        monitorHealth = monitorHealth.copy(foregroundReadSucceeded = true,
            lastForegroundReadElapsed = SystemClock.elapsedRealtime())

        logRecovery("USAGE_EVENTS_BATCH", "hasNewEvents=${newEvents.isNotEmpty()} count=${newEvents.size}")
        var gateEventTime: Long? = null
        for (record in newEvents.sortedBy { it.time }) {
            lastProcessedUsageEventTime = maxOf(lastProcessedUsageEventTime, record.time)
            logRecovery("USAGE_EVENT", "eventTime=${record.time} package=${record.packageName} " +
                "class=${record.className} type=${record.type}")
            // Keep unfiltered raw events for device diagnostics, once per new event.
            Log.d(LOG_TAG, "UsageEvent eventTime=${record.time} package=${record.packageName} " +
                "class=${record.className} eventType=${record.type}")
            val eventPackage = record.packageName?.takeIf { it.isNotBlank() } ?: continue
            val previousObservedPackage = observedForegroundPackage
            val previouslyTargetForeground = isTargetAppForeground
            val eventName = when {
                record.type == FOREGROUND_EVENT_TYPE -> {
                    lastForegroundEntryTime = record.time
                    val renewFocusCover = focusHomeExitState.onForegroundEntry(
                        record.time,
                        needsFocusCover = lastFocusSession != null && controlledConfigs[eventPackage]?.enabled == true
                    )
                    if (renewFocusCover) {
                        focusOverlayHideTask?.let { handler.removeCallbacks(it) }
                        focusOverlayHideTask = null
                        // Do not hide first. Rebind even the same package/session so
                        // its STOP_USING callback belongs to the new presentation.
                        shownFocusSession = null
                        logFocusHomeTransition("NEW_ENTRY_SUPERSEDES_HIDE", "target=$eventPackage eventTime=${record.time}")
                    }
                    focusDiagnosticForegroundEvent = record
                    observedForegroundPackage = eventPackage
                    isTargetAppForeground = eventPackage in controlledPackages
                    if (isTargetAppForeground &&
                        (!previouslyTargetForeground || previousObservedPackage != eventPackage)
                    ) {
                        // Check quota before allowing even an existing approved Session to bypass Gate.
                        gateEventTime = record.time
                    }
                    "ACTIVITY_RESUMED/MOVE_TO_FOREGROUND"
                }
                record.type == BACKGROUND_EVENT_TYPE ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        record.type == UsageEvents.Event.ACTIVITY_STOPPED) -> {
                    if (eventPackage == observedForegroundPackage) {
                        focusDiagnosticForegroundEvent = record
                        observedForegroundPackage = null
                        isTargetAppForeground = false
                    }
                    if (record.type == BACKGROUND_EVENT_TYPE) "ACTIVITY_PAUSED/MOVE_TO_BACKGROUND"
                    else "ACTIVITY_STOPPED"
                }
                else -> continue
            }
            logRecovery("FOREGROUND_EVENT", "before=$previousObservedPackage after=$observedForegroundPackage " +
                "triggerPackage=${record.packageName} class=${record.className} " +
                "type=${record.type} eventName=$eventName eventTime=${record.time}")
            // A same-package RESUMED is evidence too; it need not change the cache.
            if (focusDiagnosticForegroundEvent === record) {
                logFocusHomeTransition("FOREGROUND_EVENT", "previous=$previousObservedPackage")
            }
            if (previousObservedPackage != null &&
                previousObservedPackage != observedForegroundPackage
            ) {
                val previousSession = sessionsByPackage[previousObservedPackage]
                if (previousSession?.approved == true && previousSession.leftAt == null) {
                    previousSession.leftAt = record.time
                    Log.i(SESSION_LOG_TAG, "session left package=$previousObservedPackage\neventTime=${record.time}")
                }
            }
            if (previousObservedPackage != observedForegroundPackage) gateRequestGeneration++
            if (!isTargetAppForeground) gateEventTime = null
            if (previousObservedPackage != observedForegroundPackage ||
                previouslyTargetForeground != isTargetAppForeground
            ) {
                Log.i(LOG_TAG, "eventTime=${record.time}\npackage=$eventPackage\neventType=$eventName\n" +
                    "previousObservedPackage=$previousObservedPackage\n" +
                    "newObservedPackage=$observedForegroundPackage\n" +
                    "isTargetAppForeground=$isTargetAppForeground")
                latestPackageName = observedForegroundPackage
                latestDetectionTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.time))
                updateMonitorNotification()
            }
        }
        // Do not show for an entry already followed by an exit in this batch.
        logRecovery("FOREGROUND_RESULT", "package=$observedForegroundPackage " +
            "enabled=${controlledConfigs[observedForegroundPackage]?.enabled} " +
            "hasNewEvents=${newEvents.isNotEmpty()} cursor=$lastProcessedUsageEventTime")
        observedForegroundPackage?.let { if (blockForFocus(it, "usage_events_batch")) return true }
        // A Focus cover is removed only by the completed normal-access handoff below.
        if (focusOverlayController.isShowing()) return true
        val eventTime = gateEventTime ?: return true
        val controlledPackage = observedForegroundPackage?.takeIf { it in controlledPackages } ?: return true
        evaluateIntentGate(controlledPackage, eventTime)
        return true
    }

    private fun evaluateIntentGate(
        controlledPackage: String,
        eventTime: Long,
        onDecision: ((AccessDecision) -> Unit)? = null,
        isDecisionCurrent: (() -> Boolean)? = null,
        onCheckStarted: (() -> Unit)? = null,
        onCheckFinished: (() -> Unit)? = null
    ) {
        var completed = false
        fun complete(result: AccessDecision) {
            if (completed) return
            completed = true
            onDecision?.invoke(result)
        }
        withQuotaPermissionForGate(controlledPackage,
            onDecision = if (onDecision == null) null else ::complete,
            isDecisionCurrent = isDecisionCurrent,
            onCheckStarted = onCheckStarted,
            onCheckFinished = onCheckFinished
        ) {
            if (blockForFocus(controlledPackage)) return@withQuotaPermissionForGate
            if (reuseApprovedSession(controlledPackage, eventTime)) {
                complete(AccessDecision.ALLOW)
            } else {
                showIntentGate(controlledPackage, eventTime)
                if (overlayController.isShowing() && activeGatePresentation != null) complete(AccessDecision.GATE)
            }
        }
    }

    private fun withQuotaPermissionForGate(
        controlledPackage: String,
        fromContinue: Boolean = false,
        recheckIfMissing: Boolean = true,
        onDecision: ((AccessDecision) -> Unit)? = null,
        isDecisionCurrent: (() -> Boolean)? = null,
        onCheckStarted: (() -> Unit)? = null,
        onCheckFinished: (() -> Unit)? = null,
        onAllowed: () -> Unit
    ) {
        if (destroyed || configLoadsPending != 0) return
        if (isDecisionCurrent?.invoke() == false) return
        if (onDecision != null && observedForegroundPackage != controlledPackage) {
            onDecision(AccessDecision.NOT_FOREGROUND)
            return
        }
        if (blockForFocus(controlledPackage)) return
        val config = controlledConfigs[controlledPackage]
        if (config == null || !config.enabled || !config.intentGateEnabled) {
            // Match existing disabled-Gate configuration behavior without creating a Session.
            onDecision?.invoke(AccessDecision.ALLOW)
            return
        }
        // Serialize the snapshot decision and Session approval against background quota updates.
        synchronized(quotaRuntimeStateStore) {
            if (config.dailyQuotaMinutes != null) {
                val nowMillis = System.currentTimeMillis()
                val snapshot = quotaRuntimeStateStore.getFreshSnapshot(controlledPackage, nowMillis)
                if (snapshot == null) {
                    if (recheckIfMissing) {
                        val requestGeneration = gateRequestGeneration
                        onCheckStarted?.invoke()
                        checkQuotaIfDue(gateRecheckPackageName = controlledPackage, onGateQuotaChecked = {
                            if (requestGeneration == gateRequestGeneration &&
                                observedForegroundPackage == controlledPackage
                            ) {
                                // Re-read the snapshot; a missing/expired result never grants permission.
                                withQuotaPermissionForGate(controlledPackage, fromContinue, false,
                                    onDecision, isDecisionCurrent, onCheckStarted, onCheckFinished, onAllowed)
                            }
                        }, onCheckFinished = onCheckFinished)
                    }
                    return@synchronized
                }
                if (snapshot.state == DailyQuotaState.EXHAUSTED) {
                    if (fromContinue) {
                        Log.i(QUOTA_LOG_TAG, "QUOTA_BLOCKS_GATE_CONTINUE packageName=$controlledPackage")
                    } else {
                        Log.i(QUOTA_LOG_TAG, "QUOTA_BLOCKS_INTENT_GATE " +
                            "packageName=$controlledPackage quotaState=EXHAUSTED")
                    }
                    if (quotaUiPackageName == controlledPackage &&
                        (quotaExhaustedOverlayController.isShowing() || quotaCooldownOverlayController.isShowing())
                    ) {
                        // Keep an existing cooldown or the STOP_USING delayed hide intact.
                        hideIntentGateForQuota()
                    } else {
                        // Re-entry presents the existing exhausted state without fabricating a state transition.
                        showQuotaExhaustedOverlay(
                            QuotaExhaustedEvent(controlledPackage, snapshot.todayUsageMillis,
                                snapshot.dailyQuotaMinutes, nowMillis),
                            config.displayName
                        )
                    }
                    if (quotaUiPackageName == controlledPackage &&
                        (quotaExhaustedOverlayController.isShowing() || quotaCooldownOverlayController.isShowing())
                    ) onDecision?.invoke(AccessDecision.QUOTA)
                    return@synchronized
                }
            }
            // The async quota check may have completed after Focus started.
            if (!blockForFocus(controlledPackage)) onAllowed()
        }
    }

    private fun showIntentGate(controlledPackage: String, eventTime: Long) {
        if (blockForFocus(controlledPackage)) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(GATE_LOG_TAG, "Overlay permission missing; package=$controlledPackage eventTime=$eventTime")
            return
        }
        // monitorTask runs on the main Handler; show also guards against stacking.
        if (overlayController.isShowing()) return
        // The application-wide overlay must not retain a stopped Service instance.
        val serviceReference = WeakReference(this)
        val appContext = applicationContext
        val presentation = Any()
        activeGatePresentation = presentation
        var resultRecorded = false
        fun recordResult(reason: String?, clickedAt: Long, action: String) {
            // One final result per formal Gate, including repeated taps during delayed hide.
            if (resultRecorded) return
            resultRecorded = true
            AppDatabase.recordGateEvent(appContext, GateEvent(
                packageName = controlledPackage,
                eventTime = clickedAt,
                reason = reason,
                action = action
            ))
        }
        overlayController.show(onContinue = continueAction@ { reason, clickedAt ->
            val service = serviceReference.get() ?: return@continueAction
            if (service.destroyed || service.activeGatePresentation !== presentation || resultRecorded) return@continueAction
            service.withQuotaPermissionForGate(controlledPackage, fromContinue = true) {
                if (service.blockForFocus(controlledPackage)) return@withQuotaPermissionForGate
                if (service.activeGatePresentation === presentation && !resultRecorded) {
                    try {
                        service.approveSession(controlledPackage)
                    } finally {
                        recordResult(reason, clickedAt, "CONTINUE")
                        service.activeGatePresentation = null
                    }
                }
            }
        }, onAbandon = abandonAction@ { reason, clickedAt ->
            val service = serviceReference.get()
            if (service != null && (service.destroyed || service.activeGatePresentation !== presentation || resultRecorded)) {
                return@abandonAction
            }
            try {
                if (service != null) {
                    service.abandonSession(controlledPackage)
                } else {
                    Log.e(GATE_LOG_TAG, "onAbandon callback failed: MonitorService unavailable")
                }
            } finally {
                recordResult(reason, clickedAt, "ABANDON")
            }
        })
        if (overlayController.isShowing()) {
            Log.i(GATE_LOG_TAG, "new foreground event\npackage=$controlledPackage\neventTime=$eventTime")
        }
    }

    private fun abandonSession(controlledPackage: String) {
        Log.i(GATE_LOG_TAG, "abandon requested")
        Log.i(GATE_LOG_TAG, "onAbandon reached MonitorService")
        sessionsByPackage.remove(controlledPackage)
        Log.i(SESSION_LOG_TAG, "session cleared package=$controlledPackage")
        Log.i(GATE_LOG_TAG, "abandon package=$controlledPackage sessionApproved=false")
        // The button callback runs on the main thread; keep its overlay until HOME returns.
        Log.i(GATE_LOG_TAG, "overlay still visible before home=${overlayController.isShowing()}")
        launchHome()
    }

    private fun launchHome() {
        Log.i(GATE_LOG_TAG, "home launch requested")
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val homeComponent = try {
            val activity = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
            if (activity == null) {
                Log.e(GATE_LOG_TAG, "home resolve failed")
                return
            }
            Log.i(GATE_LOG_TAG, "home resolved package=${activity.packageName}")
            Log.i(GATE_LOG_TAG, "home resolved activity=${activity.name}")
            // A resolver/chooser is not the user's HOME Activity.
            val isHomeActivity = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.packageName == activity.packageName && it.activityInfo.name == activity.name }
            if (!isHomeActivity) {
                Log.e(GATE_LOG_TAG, "home resolve failed: resolved component is not a HOME activity")
                return
            }
            ComponentName(activity.packageName, activity.name)
        } catch (error: RuntimeException) {
            Log.e(GATE_LOG_TAG, "home resolve failed: ${error.javaClass.name}: ${error.message}", error)
            return
        }
        val explicitHomeIntent = Intent(homeIntent).apply {
            component = homeComponent
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            Log.i(GATE_LOG_TAG, "home startActivity calling component=${homeComponent.flattenToString()} " +
                "flags=0x${Integer.toHexString(explicitHomeIntent.flags)}")
            applicationContext.startActivity(explicitHomeIntent)
            // Returning only confirms submission; the system may still block the UI switch.
            Log.i(GATE_LOG_TAG, "home startActivity returned")
        } catch (error: RuntimeException) {
            Log.e(GATE_LOG_TAG, "home startActivity failed: ${error.javaClass.name}: ${error.message}", error)
        }
    }

    private fun approveSession(controlledPackage: String) {
        if (destroyed) return
        sessionsByPackage[controlledPackage] = AppSessionState(approved = true, leftAt = null)
        Log.i(SESSION_LOG_TAG, "session approved package=$controlledPackage\neventTime=${System.currentTimeMillis()}")
    }

    private fun reuseApprovedSession(currentPackage: String, eventTime: Long): Boolean {
        val session = sessionsByPackage[currentPackage] ?: return false
        if (!session.approved) return false
        val leftAt = session.leftAt
        if (leftAt == null) {
            Log.i(SESSION_LOG_TAG, "session reused package=$currentPackage\neventTime=$eventTime")
            return true
        }
        val awayMs = eventTime - leftAt
        if (awayMs <= SESSION_GRACE_MS) {
            session.leftAt = null
            Log.i(SESSION_LOG_TAG, "session reused package=$currentPackage\neventTime=$eventTime\nawayMs=$awayMs")
            return true
        }
        sessionsByPackage.remove(currentPackage)
        Log.i(SESSION_LOG_TAG, "session expired package=$currentPackage\neventTime=$eventTime\nawayMs=$awayMs")
        return false
    }

    private data class AppSessionState(
        var approved: Boolean,
        var leftAt: Long?
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自律实时监控",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateMonitorNotification(
        session: FocusSessionSnapshot? = lastFocusSession,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        try {
            val content = monitorNotificationContent(session, observedForegroundPackage, nowMillis)
            if (content == lastNotificationContent) return
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
            lastNotificationContent = content
        } catch (error: RuntimeException) {
            // A notification failure must not interrupt Focus or foreground-app detection.
            Log.w(LOG_TAG, "MONITOR_NOTIFICATION_UPDATE_FAILED", error)
        }
    }

    private fun buildNotification(content: MonitorNotificationContent): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_SHOW_FOCUS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        if (content.focusEndsAtMillis != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setWhen(content.focusEndsAtMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private fun logRc001(stage: String, detail: String = "", error: Throwable? = null) {
            val message = "stage=$stage time=${System.currentTimeMillis()} " +
                "elapsed=${SystemClock.elapsedRealtime()} pid=${android.os.Process.myPid()} $detail"
            if (error == null) Log.i("RC001_DIAG", message) else Log.e("RC001_DIAG", message, error)
        }

        private val startRetryState = MonitorStartRetryState()
        private var serviceReference: WeakReference<MonitorService>? = null
        private val recoveryHandler by lazy { Handler(Looper.getMainLooper()) }
        private var focusRecoveryTask: Runnable? = null

        // Called on the main thread by Activity starts and the recovery callback.
        fun startIfNeeded(context: Context) {
            val appContext = context.applicationContext
            // A healthy service still needs a watchdog when Focus starts later.
            scheduleFocusMonitoringRecovery(appContext)
            if (!startRetryState.claimStart(isMonitorEffective, SystemClock.elapsedRealtime())) return
            try {
                val existingService = serviceReference?.get()
                if (existingService != null && !existingService.destroyed) {
                    // In-process repair does not require another background foreground-service start.
                    existingService.recoverMonitoring()
                    return
                }
                logRc001("START_REQUEST_CALL", "foregroundApi=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.O}")
                val intent = Intent(appContext, MonitorService::class.java)
                val service = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                checkNotNull(service) { "MonitorService start was not dispatched" }
                logRc001("START_REQUEST_RETURN", "component=$service")
            } catch (error: RuntimeException) {
                logRc001("START_REQUEST_FAILED", error = error)
                throw error
            } finally {
                // Includes synchronous failures and starts that never reach onCreate.
                scheduleFocusMonitoringRecovery(appContext)
            }
        }

        fun scheduleFocusMonitoringRecovery(context: Context) {
            if (focusRecoveryTask != null) return
            val appContext = context.applicationContext
            val task = Runnable {
                focusRecoveryTask = null
                var keepChecking = true
                try {
                    keepChecking = FocusSessionStore(appContext).hasActiveSessionForMonitoring()
                    val reason = monitorHealth.unavailableReason(isRunning, SystemClock.elapsedRealtime())
                    isMonitoringReady = reason == null
                    logRc001("RECOVERY_FIRED", "running=$isRunning focusActive=$keepChecking " +
                        "reason=$reason health=$monitorHealth")
                    if (keepChecking && reason != null) {
                        logRc001("RECOVERY_ACTIVE_FOCUS", "reason=$reason serviceExists=$isRunning")
                        startIfNeeded(appContext)
                    }
                } catch (error: RuntimeException) {
                    logRc001("RECOVERY_FAILED", error = error)
                    Log.w("SELF_CONTROL_FOCUS", "Focus monitor recovery failed", error)
                } finally {
                    // Continue through healthy periods and failures, until Focus actually ends.
                    if (keepChecking) scheduleFocusMonitoringRecovery(appContext)
                }
            }
            focusRecoveryTask = task
            val posted = recoveryHandler.postDelayed(task, MONITOR_START_RETRY_MS)
            logRc001("RECOVERY_SCHEDULED", "accepted=$posted delayMs=$MONITOR_START_RETRY_MS")
            if (!posted) focusRecoveryTask = null
        }

        const val ACTION_REFRESH_CONTROLLED_APPS = "com.selfcontrol.app.action.REFRESH_CONTROLLED_APPS"
        private const val CHANNEL_ID = "self_control_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 1_000L
        private const val QUOTA_CHECK_INTERVAL_MS = 30_000L
        private const val QUOTA_LOG_TAG = "SELF_CONTROL_QUOTA"
        private const val SESSION_GRACE_MS = 5 * 60 * 1000L
        private const val SESSION_LOG_TAG = "SELF_CONTROL_SESSION"
        // ACTIVITY_RESUMED and legacy MOVE_TO_FOREGROUND share the value 1.
        @Suppress("DEPRECATION")
        private val FOREGROUND_EVENT_TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        @Suppress("DEPRECATION")
        private val BACKGROUND_EVENT_TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_PAUSED
        } else {
            UsageEvents.Event.MOVE_TO_BACKGROUND
        }
        private const val GATE_LOG_TAG = "SELF_CONTROL_GATE"
        private const val LOG_TAG = "SELF_CONTROL_MONITOR"

        var isRunning by mutableStateOf(false)
            private set
        internal var monitorHealth by mutableStateOf(MonitorHealthSnapshot())
            private set
        val isMonitorEffective: Boolean
            get() = monitorHealth.unavailableReason(isRunning, SystemClock.elapsedRealtime()) == null
        // Service existence and initialized monitoring are separate states.
        var isMonitoringReady by mutableStateOf(false)
            private set
        var latestPackageName by mutableStateOf<String?>(null)
            private set
        var latestDetectionTime by mutableStateOf<String?>(null)
            private set
    }
}

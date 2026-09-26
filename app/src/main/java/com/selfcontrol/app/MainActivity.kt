package com.selfcontrol.app

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import com.selfcontrol.app.data.AppDatabase
import com.selfcontrol.app.data.ControlledAppConfig
import com.selfcontrol.app.focus.FocusSessionSnapshot
import com.selfcontrol.app.focus.FocusSessionStartStatus
import com.selfcontrol.app.focus.FocusSessionStore
import com.selfcontrol.app.focus.FocusSessionEndStatus
import com.selfcontrol.app.focus.FocusSessionOutcome
import com.selfcontrol.app.focus.FocusSessionHistoryEntry
import com.selfcontrol.app.quota.DailyQuotaState
import com.selfcontrol.app.quota.DailyQuotaThresholdStore
import com.selfcontrol.app.quota.QuotaExtraTimeStore
import com.selfcontrol.app.quota.QuotaEnforcementMode
import com.selfcontrol.app.quota.parseQuotaEnforcementMode
import com.selfcontrol.app.quota.calculateDailyQuotaState
import com.selfcontrol.app.quota.calculateEffectiveQuotaMinutes
import com.selfcontrol.app.quota.calculateQuotaUsagePercent
import com.selfcontrol.app.quota.calculateRemainingQuotaMillis
import com.selfcontrol.app.usage.TodayUsageStatsReader
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

internal enum class AppSetupPermission { USAGE_ACCESS, OVERLAY }
internal const val ACTION_SHOW_FOCUS = "com.selfcontrol.app.action.SHOW_FOCUS"

internal enum class FocusMonitorStatus { NORMAL, CHECKING, UNAVAILABLE, NEEDS_SETTINGS }

internal fun focusMonitorStatus(
    usageGranted: Boolean,
    overlayGranted: Boolean,
    serviceRunning: Boolean,
    effective: Boolean,
    health: MonitorHealthSnapshot,
    nowElapsed: Long,
    checkingSinceElapsed: Long
): FocusMonitorStatus = when {
    !usageGranted || !overlayGranted -> FocusMonitorStatus.NEEDS_SETTINGS
    !serviceRunning -> FocusMonitorStatus.UNAVAILABLE
    effective -> FocusMonitorStatus.NORMAL
    // Give initial startup a bounded display grace period; failed/stale polls are never "checking".
    health.lastPollElapsed == null &&
        nowElapsed - checkingSinceElapsed in 0 until MONITOR_HEARTBEAT_TIMEOUT_MS -> FocusMonitorStatus.CHECKING
    else -> FocusMonitorStatus.UNAVAILABLE
}

internal fun missingAppPermissions(usageAccessGranted: Boolean, overlayGranted: Boolean): List<AppSetupPermission> =
    buildList {
        if (!usageAccessGranted) add(AppSetupPermission.USAGE_ACCESS)
        if (!overlayGranted) add(AppSetupPermission.OVERLAY)
    }

class MainActivity : ComponentActivity() {
    private enum class Page { HOME, CONTROLLED_APP_MANAGER }
    private var currentPage by mutableStateOf(Page.HOME)
    private var focusNotificationEntry by mutableStateOf(0)
    private var showBackgroundHelp by mutableStateOf(false)
    private val overlayController by lazy { OverlayController.getInstance(applicationContext) }
    private var usageAccessGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var missingPermissions by mutableStateOf<List<AppSetupPermission>>(emptyList())
    private var showPermissionGuide by mutableStateOf(false)
    private var foregroundResult by mutableStateOf("点击按钮检测最近前台应用")
    private var rawEventsResult by mutableStateOf("尚未检测")
    private var douyinResult by mutableStateOf("尚未检测")
    private var gateEventsResult by mutableStateOf("尚未查询 GateEvent")
    private var gateEventsLoading by mutableStateOf(false)
    private var controlledAppsResult by mutableStateOf("正在加载受控 App 配置…")
    private var controlledApps by mutableStateOf<List<ControlledAppConfig>>(emptyList())
    private var todayUsageMillis by mutableStateOf<Map<String, Long>>(emptyMap())
    private var quotaDataLoading by mutableStateOf(false)
    private var quotaDataUpdatedAtMillis by mutableStateOf<Long?>(null)
    private var quotaDataError by mutableStateOf<String?>(null)
    private var quotaReadGeneration = 0L
    private var extraMinutesByPackage by mutableStateOf<Map<String, Int>>(emptyMap())
    private var quotaInputs by mutableStateOf<Map<String, String>>(emptyMap())
    private var quotaErrors by mutableStateOf<Map<String, String>>(emptyMap())
    private var quotaSaveMessages by mutableStateOf<Map<String, String>>(emptyMap())
    private var quotaSavingPackages by mutableStateOf<Set<String>>(emptySet())
    private var modeInputs by mutableStateOf<Map<String, QuotaEnforcementMode>>(emptyMap())
    private var modeErrors by mutableStateOf<Map<String, String>>(emptyMap())
    private var modeSavingPackages by mutableStateOf<Set<String>>(emptySet())
    private var showAppManager by mutableStateOf(false)
    private var launcherApps by mutableStateOf<List<LauncherApp>>(emptyList())
    private var launcherAppsLoading by mutableStateOf(false)
    private var configSaving by mutableStateOf(false)
    private var appManagerMessage by mutableStateOf("")
    private val focusSessionStore by lazy { FocusSessionStore(applicationContext) }
    private var focusDurationInput by mutableStateOf("25")
    private var focusSnapshot by mutableStateOf<FocusSessionSnapshot?>(null)
    private var focusNowMillis by mutableStateOf(System.currentTimeMillis())
    private var focusUiResumed by mutableStateOf(false)
    private var focusLoading by mutableStateOf(true)
    private var focusStarting by mutableStateOf(false)
    private var focusMessage by mutableStateOf("")
    private var focusLatestResult by mutableStateOf<FocusSessionHistoryEntry?>(null)
    private var focusError by mutableStateOf<String?>(null)
    private enum class FocusSetupRequirement { USAGE_ACCESS, OVERLAY, CONTROLLED_APP }
    private var focusSetupRequirement by mutableStateOf<FocusSetupRequirement?>(null)
    private var reportsHistoryLoading by mutableStateOf(false)
    private var reportsHistory by mutableStateOf<List<FocusSessionHistoryEntry>>(emptyList())
    private var reportsHistoryError by mutableStateOf<String?>(null)
    private var showReports by mutableStateOf(false)
    private data class FocusEndConfirmation(
        val session: FocusSessionSnapshot,
        val readyAtElapsedMillis: Long
    )
    private var focusEndConfirmation by mutableStateOf<FocusEndConfirmation?>(null)
    private var focusConfirmWaitSeconds by mutableStateOf(5L)
    private var focusEnding by mutableStateOf(false)
    private var focusReadGeneration = 0L
    private var notificationPermissionPending = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationPermissionPending = false
        startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNotificationEntry(intent)
        refreshEntryPermissionGuide()
        setContent {
            MaterialTheme {
                val homeScrollState = rememberScrollState()
                LaunchedEffect(focusNotificationEntry) {
                    if (focusNotificationEntry > 0) homeScrollState.scrollTo(0)
                }
                if (showBackgroundHelp) {
                    AlertDialog(
                        onDismissRequest = { showBackgroundHelp = false },
                        title = { Text("后台运行说明") },
                        text = { Text("清理后台可能导致监控中断。专注计时保留，不代表应用限制持续有效。" +
                            "\n\n华为设备可在系统设置中查看应用启动管理、允许后台活动和电池优化设置，" +
                            "并在专注期间避免清理 SelfControlApp。具体入口因系统版本而异，设置后仍需检查监控状态。") },
                        confirmButton = {
                            TextButton(onClick = { showBackgroundHelp = false }) { Text("知道了") }
                        }
                    )
                }
                BackHandler(enabled = currentPage == Page.CONTROLLED_APP_MANAGER) {
                    currentPage = Page.HOME
                }
                if (showPermissionGuide && missingPermissions.isNotEmpty() && focusSetupRequirement == null) {
                    val nextPermission = missingPermissions.first()
                    AlertDialog(
                        onDismissRequest = { showPermissionGuide = false },
                        title = { Text("开启必要权限") },
                        text = {
                            Text(missingPermissions.joinToString("\n") {
                                when (it) {
                                    AppSetupPermission.USAGE_ACCESS -> "使用情况访问权限：用于识别受控 App。"
                                    AppSetupPermission.OVERLAY -> "悬浮窗权限：用于显示使用限制提示。"
                                }
                            } + "\n设置完成后返回，App 会自动重新检查。")
                        },
                        dismissButton = {
                            TextButton(onClick = { showPermissionGuide = false }) { Text("暂不设置") }
                        },
                        confirmButton = {
                            TextButton(onClick = { openPermissionGuideSettings(nextPermission) }) {
                                Text(when (nextPermission) {
                                    AppSetupPermission.USAGE_ACCESS -> "开启使用情况访问权限"
                                    AppSetupPermission.OVERLAY -> "开启悬浮窗权限"
                                })
                            }
                        }
                    )
                }
                focusSetupRequirement?.let { requirement ->
                    AlertDialog(
                        onDismissRequest = { focusSetupRequirement = null },
                        title = { Text("开始专注前") },
                        text = {
                            Text(when (requirement) {
                                FocusSetupRequirement.USAGE_ACCESS -> "请先开启使用情况访问权限，以识别受控 App。设置完成后，返回并再次点击开始专注。"
                                FocusSetupRequirement.OVERLAY -> "请先开启悬浮窗权限，以显示专注限制提示。设置完成后，返回并再次点击开始专注。"
                                FocusSetupRequirement.CONTROLLED_APP -> "请先在管理受控 App 中启用至少一个应用，再开始专注。"
                            })
                        },
                        dismissButton = {
                            TextButton(onClick = { focusSetupRequirement = null }) { Text("暂不设置") }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                focusSetupRequirement = null
                                when (requirement) {
                                    FocusSetupRequirement.USAGE_ACCESS -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    FocusSetupRequirement.OVERLAY -> startActivity(Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")
                                    ))
                                    FocusSetupRequirement.CONTROLLED_APP -> {
                                        showAppManager = true
                                        loadLauncherApps()
                                    }
                                }
                            }) {
                                Text(if (requirement == FocusSetupRequirement.CONTROLLED_APP) "管理受控 App" else "去设置")
                            }
                        }
                    )
                }
                LaunchedEffect(focusSnapshot, focusUiResumed) {
                    val session = focusSnapshot ?: return@LaunchedEffect
                    if (!focusUiResumed) return@LaunchedEffect
                    while (true) {
                        focusNowMillis = System.currentTimeMillis()
                        refreshPermissionStates()
                        if (focusNowMillis >= session.endsAtMillis) {
                            cancelFocusEndConfirmation()
                            if (!focusEnding) {
                                focusSnapshot = null
                                // One read settles expiry and obtains the recorded outcome.
                                loadFocusSession(session)
                            }
                            break
                        }
                        ensureFocusMonitoring()
                        delay(1_000L)
                    }
                }
                val confirmation = focusEndConfirmation
                LaunchedEffect(confirmation, focusUiResumed) {
                    if (confirmation == null || !focusUiResumed) return@LaunchedEffect
                    while (true) {
                        val remaining = (confirmation.readyAtElapsedMillis -
                            SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                        focusConfirmWaitSeconds = (remaining + 999L) / 1_000L
                        if (remaining == 0L) break
                        delay(minOf(remaining, 200L))
                    }
                }
                if (confirmation != null && focusUiResumed) {
                    AlertDialog(
                        onDismissRequest = { cancelFocusEndConfirmation() },
                        title = { Text("提前结束专注？") },
                        text = {
                            val seconds = ((confirmation.session.endsAtMillis - focusNowMillis)
                                .coerceAtLeast(0L) + 999L) / 1_000L
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("剩余时间：${seconds / 60L}分${seconds % 60L}秒")
                                Text("确认提前结束会记入专注历史。等待期间专注会继续倒计时；自然到期将记录为完成。")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { cancelFocusEndConfirmation() }) {
                                Text("继续专注")
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { confirmFocusEnd(confirmation) },
                                enabled = focusConfirmWaitSeconds == 0L && !focusEnding && !focusLoading
                            ) {
                                Text(if (focusConfirmWaitSeconds > 0L)
                                    "确认提前结束（${focusConfirmWaitSeconds}秒）" else "确认提前结束")
                            }
                        }
                    )
                }
                if (showReports) {
                    ReportsDialog(
                        loading = reportsHistoryLoading,
                        history = reportsHistory,
                        error = reportsHistoryError,
                        onRetry = { loadReportsHistory() },
                        onDismiss = { showReports = false }
                    )
                }
                if (showAppManager) {
                    AlertDialog(
                        onDismissRequest = { if (!configSaving) showAppManager = false },
                        title = { Text("管理受控 App") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (launcherAppsLoading) "正在加载…" else appManagerMessage)
                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                                    items(launcherApps, key = { it.packageName }) { app ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(app.displayName)
                                                Text(app.packageName)
                                            }
                                            Checkbox(
                                                checked = app.enabled,
                                                enabled = !launcherAppsLoading && !configSaving,
                                                onCheckedChange = { saveControlledApp(app, it) }
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAppManager = false }, enabled = !configSaving) {
                                Text("关闭")
                            }
                        }
                    )
                }
                LaunchedEffect(currentPage) {
                    if (currentPage == Page.CONTROLLED_APP_MANAGER) loadControlledApps()
                }
                // Keep Focus effects and dialogs above the page branch so navigation cannot cancel them.
                if (currentPage == Page.CONTROLLED_APP_MANAGER) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(onClick = { currentPage = Page.HOME }) {
                            Text("返回首页")
                        }
                        Text("受控App管理", style = MaterialTheme.typography.headlineMedium)
                        Text("管理长期限制规则", style = MaterialTheme.typography.bodyMedium)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("实时监控", style = MaterialTheme.typography.titleLarge)
                                Text("监控状态：${when {
                                    MonitorService.isMonitorEffective -> "可用"
                                    MonitorService.isRunning -> "服务运行中，监控尚不可用"
                                    else -> "已停止"
                                }}")
                                Button(
                                    onClick = { requestStartMonitorService() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("启动实时监控")
                                }
                                Button(
                                    onClick = { stopService(Intent(this@MainActivity, MonitorService::class.java)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("停止实时监控")
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        PermissionSection(
                            title = "使用情况访问",
                            granted = usageAccessGranted,
                            onSettingsClick = {
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        PermissionSection(
                            title = "悬浮窗权限",
                            granted = overlayGranted,
                            onSettingsClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("受控 App 列表", style = MaterialTheme.typography.titleLarge)
                        if (controlledAppsResult.isNotEmpty()) Text(controlledAppsResult)
                        if (quotaDataLoading) Text("正在读取额度数据…")
                        quotaDataUpdatedAtMillis?.let {
                            Text("上次更新：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        quotaDataError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (!quotaDataLoading && quotaDataError == null &&
                            controlledApps.any { (todayUsageMillis[it.packageName] ?: 0L) <= 0L }) {
                            Text("部分应用暂无可确认的使用数据，请稍后刷新。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        controlledApps.forEach { app ->
                            var editing by rememberSaveable(app.packageName) { mutableStateOf(false) }
                            // The reader also returns zero on some failures; do not treat it as confirmed usage.
                            val usageMillis = todayUsageMillis[app.packageName]?.takeIf {
                                !quotaDataLoading && quotaDataError == null && usageAccessGranted && it > 0L
                            }
                            val extraMinutes = extraMinutesByPackage[app.packageName] ?: 0
                            val effectiveQuotaMinutes = calculateEffectiveQuotaMinutes(app.dailyQuotaMinutes, extraMinutes)
                            val quotaDetails = if (usageMillis == null) {
                                "剩余时间：数据不可用"
                            } else if (app.dailyQuotaMinutes == null) {
                                "剩余时间：未设置额度"
                            } else {
                                val usagePercent = calculateQuotaUsagePercent(usageMillis, effectiveQuotaMinutes)
                                val remainingMillis = checkNotNull(
                                    calculateRemainingQuotaMillis(usageMillis, effectiveQuotaMinutes)
                                )
                                val remainingText = when {
                                    remainingMillis == 0L -> "0分钟"
                                    remainingMillis < 60_000L -> "不足1分钟"
                                    else -> "${remainingMillis / 60_000L}分钟"
                                }
                                "剩余时间：$remainingText\n" +
                                    "额度使用：$usagePercent%"
                            }
                            val quotaStateText = if (usageMillis == null) "数据不可用" else when (calculateDailyQuotaState(
                                todayUsageMillis = usageMillis,
                                dailyQuotaMinutes = effectiveQuotaMinutes
                            )) {
                                DailyQuotaState.NOT_SET -> "未设置"
                                DailyQuotaState.WITHIN_QUOTA -> "额度内"
                                DailyQuotaState.EXHAUSTED -> "已达到额度"
                            }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(app.displayName, style = MaterialTheme.typography.titleLarge)
                                    Text("控制状态：${if (app.enabled && app.intentGateEnabled) "启用" else "停用"}")
                                    Text("今日使用：${usageMillis?.let { if (it < 60_000L) "不足1分钟" else "${it / 60_000L} 分钟" } ?: "数据不可用"}",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("每日有效额度：${effectiveQuotaMinutes?.let { "$it 分钟" } ?: "未设置"}",
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("当前模式：${if (parseQuotaEnforcementMode(app.quotaEnforcementMode) == QuotaEnforcementMode.STRICT) "严格模式" else "中度模式"}")
                                    if (extraMinutes > 0) {
                                        Text("基础额度：${app.dailyQuotaMinutes?.let { "$it 分钟" } ?: "未设置"}\n" +
                                            "额外时间：$extraMinutes 分钟",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(quotaDetails)
                                    Text("额度状态：$quotaStateText", style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { editing = !editing }) {
                                        Text(if (editing) "收起编辑" else "编辑规则")
                                    }
                                    if (editing) {
                                        OutlinedTextField(
                                            value = quotaInputs[app.packageName] ?: app.dailyQuotaMinutes?.toString().orEmpty(),
                                            onValueChange = {
                                                quotaInputs = quotaInputs + (app.packageName to it)
                                                quotaErrors = quotaErrors - app.packageName
                                                quotaSaveMessages = quotaSaveMessages - app.packageName
                                            },
                                            label = { Text("每日额度（分钟）") },
                                            supportingText = { Text("范围 1～1440 分钟，留空并保存可取消额度。") },
                                            singleLine = true,
                                            enabled = app.packageName !in quotaSavingPackages,
                                            isError = app.packageName in quotaErrors,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        quotaErrors[app.packageName]?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                        quotaSaveMessages[app.packageName]?.let { Text(it) }
                                        Button(
                                            onClick = {
                                                saveDailyQuota(
                                                    app.packageName,
                                                    quotaInputs[app.packageName] ?: app.dailyQuotaMinutes?.toString().orEmpty()
                                                )
                                            },
                                            enabled = app.packageName !in quotaSavingPackages
                                        ) {
                                            Text(if (app.packageName in quotaSavingPackages) "正在保存…" else "保存额度")
                                        }
                                        val selectedMode = modeInputs[app.packageName]
                                            ?: parseQuotaEnforcementMode(app.quotaEnforcementMode)
                                        Text("额度限制模式：")
                                        listOf(QuotaEnforcementMode.MODERATE, QuotaEnforcementMode.STRICT).forEach { mode ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = selectedMode == mode,
                                                    onClick = {
                                                        modeInputs = modeInputs + (app.packageName to mode)
                                                        modeErrors = modeErrors - app.packageName
                                                    },
                                                    enabled = app.packageName !in modeSavingPackages
                                                )
                                                Column {
                                                    Text(if (mode == QuotaEnforcementMode.MODERATE) "中度模式" else "严格模式")
                                                    Text(
                                                        if (mode == QuotaEnforcementMode.MODERATE) "额度用完后，每个 App 每天可申请一次额外时间"
                                                        else "额度用完后不允许申请额外时间",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        }
                                        Text("你可以随时修改模式、每日额度，或在管理受控 App 中关闭限制。",
                                            style = MaterialTheme.typography.bodySmall)
                                        modeErrors[app.packageName]?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                        Button(
                                            onClick = { saveQuotaEnforcementMode(app.packageName, selectedMode) },
                                            enabled = app.packageName !in modeSavingPackages
                                        ) {
                                            Text(if (app.packageName in modeSavingPackages) "正在保存…" else "保存模式")
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                showAppManager = true
                                loadLauncherApps()
                            },
                            enabled = !launcherAppsLoading && !configSaving,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("添加 / 选择受控 App")
                        }
                    }
                } else Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(homeScrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("专注", style = MaterialTheme.typography.headlineMedium)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            val session = focusSnapshot
                            if (session == null) {
                                Text("留一段时间，专心做好眼前的事。",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("开启专注即开始本次专注计划。应用将在监控正常运行时限制已启用的受控 App 使用，请留意当前监控状态。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val minutes = focusDurationInput.toIntOrNull()
                                val durationValid = focusDurationInput.isNotEmpty() &&
                                    focusDurationInput.all { it in '0'..'9' } &&
                                    minutes != null && minutes in 1..1440
                                val canEditDuration = !focusLoading && !focusStarting && !focusEnding
                                Text("选择时长", style = MaterialTheme.typography.labelLarge)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(25, 45, 60).forEach { preset ->
                                        val selected = durationValid && minutes == preset
                                        FilterChip(
                                            selected = selected,
                                            onClick = {
                                                focusDurationInput = preset.toString()
                                                focusError = null
                                            },
                                            enabled = canEditDuration,
                                            label = { Text("${preset}分钟") },
                                            leadingIcon = if (selected) ({ Text("✓") }) else null,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = focusDurationInput,
                                    onValueChange = {
                                        focusDurationInput = it
                                        focusError = null
                                    },
                                    label = { Text("自定义时长（分钟）") },
                                    supportingText = {
                                        Text(if (durationValid) "可设置 1～1440 分钟"
                                            else "请输入 1～1440 的整数分钟数")
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    enabled = canEditDuration,
                                    isError = !durationValid,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = { startFocusSession() },
                                    enabled = focusUiResumed && canEditDuration && durationValid,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(when {
                                        focusLoading -> "正在恢复专注状态…"
                                        focusStarting -> "正在开始…"
                                        focusEnding -> "正在结束…"
                                        else -> "开始专注"
                                    })
                                }
                            } else {
                                val remainingMillis = (session.endsAtMillis - focusNowMillis).coerceAtLeast(0L)
                                // Round up so the UI never says 00:00 before the original deadline.
                                val seconds = (remainingMillis + 999L) / 1_000L
                                val minutesPart = ((seconds / 60L) % 60L).toString().padStart(2, '0')
                                val secondsPart = (seconds % 60L).toString().padStart(2, '0')
                                val countdown = if (seconds >= 3_600L) {
                                    "${seconds / 3_600L}:$minutesPart:$secondsPart"
                                } else "$minutesPart:$secondsPart"
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(if (remainingMillis > 0L) "专注进行中" else "正在更新专注状态…",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text("剩余时间", style = MaterialTheme.typography.labelLarge)
                                    Text(countdown, style = MaterialTheme.typography.displayMedium,
                                        fontFamily = FontFamily.Monospace)
                                    val plannedMinutes = (session.endsAtMillis - session.startedAtMillis) / 60_000L
                                    Text("本次计划 · $plannedMinutes 分钟",
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                                val serviceRunning = MonitorService.isRunning
                                val health = MonitorService.monitorHealth
                                val checkingSince = remember(serviceRunning, session) { SystemClock.elapsedRealtime() }
                                val monitorStatus = focusMonitorStatus(
                                    usageAccessGranted, overlayGranted, serviceRunning,
                                    MonitorService.isMonitorEffective, health,
                                    SystemClock.elapsedRealtime(), checkingSince
                                )
                                Text("监控：${when (monitorStatus) {
                                    FocusMonitorStatus.NORMAL -> "正常"
                                    FocusMonitorStatus.CHECKING -> "正在检查"
                                    FocusMonitorStatus.UNAVAILABLE -> "不可用"
                                    FocusMonitorStatus.NEEDS_SETTINGS -> "需要设置"
                                }}", style = MaterialTheme.typography.titleMedium)
                                Text(when (monitorStatus) {
                                    FocusMonitorStatus.NORMAL -> "当前监控检查正常。"
                                    FocusMonitorStatus.CHECKING -> "正在检查监控状态，尚未确认应用限制有效。"
                                    FocusMonitorStatus.UNAVAILABLE -> "专注计时仍继续，但当前无法确认应用限制有效。"
                                    FocusMonitorStatus.NEEDS_SETTINGS -> "请开启${missingAppPermissions(usageAccessGranted, overlayGranted)
                                        .joinToString("和") { if (it == AppSetupPermission.USAGE_ACCESS) "使用情况访问权限" else "悬浮窗权限" }}。专注计时仍继续。"
                                }, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (monitorStatus == FocusMonitorStatus.UNAVAILABLE ||
                                    monitorStatus == FocusMonitorStatus.NEEDS_SETTINGS) {
                                    Button(onClick = {
                                        refreshPermissionStates()
                                        val missing = missingAppPermissions(usageAccessGranted, overlayGranted).firstOrNull()
                                        if (missing != null) openPermissionGuideSettings(missing)
                                        else startMonitorService()
                                    }, enabled = focusUiResumed && remainingMillis > 0L && !focusEnding) {
                                        Text(if (monitorStatus == FocusMonitorStatus.NEEDS_SETTINGS) "去设置" else "尝试恢复监控")
                                    }
                                }
                                TextButton(onClick = { showBackgroundHelp = true }) { Text("后台运行说明") }
                                TextButton(
                                    onClick = { requestFocusEndConfirmation() },
                                    enabled = remainingMillis > 0L && focusUiResumed &&
                                        !focusLoading && !focusStarting && !focusEnding,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (focusEnding) "正在结束…" else "提前结束专注")
                                }
                            }
                            focusError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            // Keep existing messages; no result screen is introduced here.
                            if (focusMessage.isNotEmpty()) {
                                Text(focusMessage, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Button(
                        onClick = { currentPage = Page.CONTROLLED_APP_MANAGER },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("受控App管理")
                    }
                    Button(
                        onClick = {
                            loadReportsHistory()
                            showReports = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查看专注报告")
                    }
                    val latestResult = focusLatestResult
                    if (focusSnapshot == null && !focusLoading && !focusStarting && !focusEnding && latestResult != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("最近一次专注", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    when (latestResult.outcome) {
                                        FocusSessionOutcome.COMPLETED -> "专注完成"
                                        FocusSessionOutcome.EARLY_ENDED -> "本次专注提前结束"
                                    },
                                    style = MaterialTheme.typography.titleLarge
                                )
                                val plannedMinutes = (latestResult.endsAtMillis - latestResult.startedAtMillis) / 60_000L
                                val actualMinutes = (latestResult.endedAtMillis - latestResult.startedAtMillis)
                                    .coerceAtLeast(0L) / 60_000.0
                                Text("计划：$plannedMinutes 分钟")
                                Text("实际专注：${String.format(Locale.getDefault(), "%.2f", actualMinutes)} 分钟")
                            }
                        }
                    }

                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationEntry(intent)
    }

    private fun handleNotificationEntry(intent: Intent?) {
        if (intent?.action != ACTION_SHOW_FOCUS) return
        currentPage = Page.HOME
        showReports = false
        showAppManager = false
        focusNotificationEntry++
        // Consume navigation only; opening the notification never starts or ends Focus.
        intent.action = null
    }

    override fun onResume() {
        super.onResume()
        refreshEntryPermissionGuide()
        loadControlledApps()
        focusNowMillis = System.currentTimeMillis()
        focusUiResumed = true
        loadFocusSession()
    }

    override fun onPause() {
        focusUiResumed = false
        cancelFocusEndConfirmation()
        super.onPause()
    }

    private fun loadFocusSession(previousSession: FocusSessionSnapshot? = focusSnapshot) {
        val generation = ++focusReadGeneration
        focusLoading = true
        focusLatestResult = null
        try {
            AppDatabase.executor.execute {
                try {
                    val snapshot = focusSessionStore.getActiveSession()
                    val history = if (snapshot == null) focusSessionStore.getSessionHistory() else emptyList()
                    val outcome = if (snapshot == null && previousSession != null) {
                        history.lastOrNull {
                            it.startedAtMillis == previousSession.startedAtMillis && it.endsAtMillis == previousSession.endsAtMillis
                        }?.outcome
                    } else null
                    runOnUiThread {
                        if (!isDestroyed && generation == focusReadGeneration) {
                            focusNowMillis = System.currentTimeMillis()
                            focusSnapshot = snapshot
                            focusLatestResult = history.lastOrNull()
                            focusLoading = false
                            if (focusEndConfirmation?.session != snapshot) cancelFocusEndConfirmation()
                            if (outcome != null) focusMessage = focusOutcomeMessage(outcome)
                            ensureFocusMonitoring()
                        }
                    }
                } catch (error: Exception) {
                    Log.e("SELF_CONTROL_FOCUS", "Focus session load failed", error)
                    runOnUiThread {
                        if (!isDestroyed && generation == focusReadGeneration) {
                            focusLoading = false
                            focusError = "专注状态读取失败，请重新进入页面重试"
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("SELF_CONTROL_FOCUS", "Focus session load enqueue failed", error)
            focusLoading = false
            focusError = "专注状态读取失败，请重新进入页面重试"
        }
    }

    private fun requestFocusEndConfirmation() {
        val session = focusSnapshot ?: return
        if (!focusUiResumed || focusLoading || focusStarting || focusEnding) return
        focusNowMillis = System.currentTimeMillis()
        if (focusNowMillis >= session.endsAtMillis) {
            loadFocusSession(session)
            return
        }
        focusConfirmWaitSeconds = 5L
        focusEndConfirmation = FocusEndConfirmation(session, SystemClock.elapsedRealtime() + 5_000L)
    }

    private fun cancelFocusEndConfirmation() {
        focusEndConfirmation = null
        focusConfirmWaitSeconds = 5L
    }

    private fun focusOutcomeMessage(outcome: FocusSessionOutcome): String = when (outcome) {
        FocusSessionOutcome.COMPLETED -> "专注已自然完成，已记入历史"
        FocusSessionOutcome.EARLY_ENDED -> "专注已提前结束，已记入历史"
    }

    private fun confirmFocusEnd(confirmation: FocusEndConfirmation) {
        // Recheck in the event handler as well as disabling the button. A stale click
        // after dismissal, pause, or a previous confirmation must not enqueue a write.
        if (focusEndConfirmation !== confirmation || !focusUiResumed || focusEnding ||
            focusLoading || SystemClock.elapsedRealtime() < confirmation.readyAtElapsedMillis) return
        cancelFocusEndConfirmation()
        focusEnding = true
        ++focusReadGeneration
        focusError = null
        focusMessage = "正在确认专注结果…"
        try {
            AppDatabase.executor.execute {
                try {
                    // This queue also runs starts. Do not end a replacement session if
                    // this Activity's confirmed request was delayed in the queue.
                    val current = focusSessionStore.getActiveSession()
                    val result = if (current == null || current == confirmation.session) {
                        focusSessionStore.endFocusSessionEarly()
                    } else null
                    val snapshot = focusSessionStore.getActiveSession()
                    val latestResult = if (snapshot != null || result?.status == FocusSessionEndStatus.STORAGE_ERROR) {
                        null
                    } else result?.historyEntry ?: focusSessionStore.getSessionHistory().lastOrNull()
                    val outcome = when (result?.status) {
                        FocusSessionEndStatus.ENDED -> result.historyEntry?.outcome
                        FocusSessionEndStatus.NO_ACTIVE_SESSION -> latestResult?.takeIf {
                            it.startedAtMillis == confirmation.session.startedAtMillis &&
                                it.endsAtMillis == confirmation.session.endsAtMillis
                        }?.outcome
                        else -> null
                    }
                    runOnUiThread {
                        if (!isDestroyed) {
                            focusEnding = false
                            focusNowMillis = System.currentTimeMillis()
                            focusSnapshot = snapshot
                            focusLatestResult = latestResult
                            focusMessage = outcome?.let { focusOutcomeMessage(it) }
                                ?: if (result?.status == FocusSessionEndStatus.STORAGE_ERROR) ""
                                else "专注状态已变化，已刷新当前状态"
                            if (result?.status == FocusSessionEndStatus.STORAGE_ERROR) {
                                focusError = "专注结束结果保存失败，请重新进入页面核对"
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.e("SELF_CONTROL_FOCUS", "Focus session end failed", error)
                    runOnUiThread {
                        if (!isDestroyed) {
                            focusEnding = false
                            focusMessage = ""
                            focusError = "专注结束结果读取失败，请重新进入页面核对"
                            loadFocusSession(confirmation.session)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("SELF_CONTROL_FOCUS", "Focus session end enqueue failed", error)
            focusEnding = false
            focusMessage = ""
            focusError = "提前结束请求未提交，请重试"
        }
    }

    private fun startFocusSession() {
        if (focusStarting || focusLoading || focusEnding || focusSnapshot != null) return
        refreshPermissionStates()
        focusSetupRequirement = when {
            !usageAccessGranted -> FocusSetupRequirement.USAGE_ACCESS
            !overlayGranted -> FocusSetupRequirement.OVERLAY
            else -> null
        }
        if (focusSetupRequirement != null) return
        val minutes = focusDurationInput.toIntOrNull()
        if (focusDurationInput.isEmpty() || focusDurationInput.any { it !in '0'..'9' } ||
            minutes == null || minutes !in 1..1440
        ) {
            focusError = "请输入 1～1440 的整数分钟数"
            return
        }
        focusStarting = true
        ++focusReadGeneration
        focusError = null
        focusMessage = ""
        try {
            AppDatabase.executor.execute {
                val result = try {
                    // Read current persisted flags, not a possibly stale home-page snapshot.
                    val hasControlledApp = AppDatabase.getInstance(applicationContext)
                        .controlledAppConfigDao().getAllControlledApps()
                        .any { it.enabled && it.intentGateEnabled }
                    val missingRequirement = when {
                        !hasUsageAccessPermission(applicationContext) -> FocusSetupRequirement.USAGE_ACCESS
                        !Settings.canDrawOverlays(applicationContext) -> FocusSetupRequirement.OVERLAY
                        !hasControlledApp -> FocusSetupRequirement.CONTROLLED_APP
                        else -> null
                    }
                    if (missingRequirement != null) {
                        runOnUiThread {
                            if (!isDestroyed) {
                                focusStarting = false
                                focusSetupRequirement = missingRequirement
                            }
                        }
                        return@execute
                    }
                    focusSessionStore.startFocusSession(minutes)
                } catch (error: Exception) {
                    Log.e("SELF_CONTROL_FOCUS", "Focus setup check or start failed", error)
                    runOnUiThread {
                        if (!isDestroyed) {
                            focusStarting = false
                            focusError = "无法确认专注启动条件或保存专注，请重试"
                        }
                    }
                    return@execute
                }
                runOnUiThread {
                    // The saved Focus still needs recovery if this Activity left during the write.
                    if (result.status == FocusSessionStartStatus.STARTED ||
                        result.status == FocusSessionStartStatus.ALREADY_ACTIVE) {
                        MonitorService.scheduleFocusMonitoringRecovery(applicationContext)
                    }
                    if (!isDestroyed) {
                        focusStarting = false
                        focusNowMillis = System.currentTimeMillis()
                        when (result.status) {
                            FocusSessionStartStatus.STARTED -> {
                                focusLatestResult = null
                                focusSnapshot = result.snapshot
                                focusMessage = "专注已开始"
                                ensureFocusMonitoring()
                            }
                            FocusSessionStartStatus.ALREADY_ACTIVE -> {
                                focusLatestResult = null
                                focusSnapshot = result.snapshot
                                focusMessage = "已有正在进行的专注"
                                ensureFocusMonitoring()
                            }
                            FocusSessionStartStatus.INVALID_DURATION ->
                                focusError = "请输入 1～1440 的整数分钟数"
                            FocusSessionStartStatus.STORAGE_ERROR ->
                                focusError = "专注状态保存失败"
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("SELF_CONTROL_FOCUS", "Focus session start enqueue failed", error)
            focusStarting = false
            focusError = "专注状态保存失败"
        }
    }

    @Suppress("DEPRECATION")
    private fun loadLauncherApps() {
        launcherAppsLoading = true
        launcherApps = emptyList()
        appManagerMessage = ""
        val appContext = applicationContext
        try {
            AppDatabase.executor.execute {
                try {
                    val manager = appContext.packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val configs = AppDatabase.getInstance(appContext).controlledAppConfigDao()
                        .getAllControlledApps().associateBy { it.packageName }
                    val apps = manager.queryIntentActivities(intent, 0)
                        .mapNotNull { it.activityInfo }
                        .filter { it.exported && it.packageName != appContext.packageName }
                        .distinctBy { it.packageName }
                        .map {
                            LauncherApp(
                                packageName = it.packageName,
                                displayName = manager.getApplicationLabel(it.applicationInfo).toString(),
                                enabled = configs[it.packageName]?.enabled == true
                            )
                        }.sortedBy { it.displayName }
                    runOnUiThread {
                        if (!isDestroyed) {
                            launcherApps = apps
                            launcherAppsLoading = false
                            appManagerMessage = if (apps.isEmpty()) "未找到可启动的 App" else "勾选启用控制，取消勾选停用控制"
                        }
                    }
                } catch (error: Exception) {
                    Log.e(AppDatabase.CONFIG_LOG_TAG, "Launcher app query failed", error)
                    runOnUiThread {
                        if (!isDestroyed) {
                            launcherAppsLoading = false
                            appManagerMessage = "加载失败，请关闭后重试"
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.CONFIG_LOG_TAG, "Launcher app query enqueue failed", error)
            launcherAppsLoading = false
            appManagerMessage = "加载失败，请关闭后重试"
        }
    }

    private fun saveControlledApp(app: LauncherApp, enabled: Boolean) {
        if (configSaving || app.packageName == packageName) return
        configSaving = true
        appManagerMessage = "正在保存…"
        val appContext = applicationContext
        try {
            AppDatabase.executor.execute {
                try {
                    val dao = AppDatabase.getInstance(appContext).controlledAppConfigDao()
                    dao.setControlledAppEnabled(app.packageName, app.displayName, enabled)
                    if (!enabled) {
                        // Only explicit user disable revokes extra time; keep the daily grant limit.
                        QuotaExtraTimeStore(appContext).clearExtraMinutesPreservingGrant(app.packageName)
                    }
                } catch (error: Exception) {
                    Log.e(AppDatabase.CONFIG_LOG_TAG, "Controlled app save failed package=${app.packageName}", error)
                    runOnUiThread {
                        if (!isDestroyed) {
                            configSaving = false
                            appManagerMessage = "保存失败，配置未更改，请重试"
                        }
                    }
                    return@execute
                }
                // Dispatch even if the Activity was destroyed while the transaction completed.
                runOnUiThread {
                    var message = "已保存"
                    if (MonitorService.isRunning) {
                        try {
                            val service = appContext.startService(
                                Intent(appContext, MonitorService::class.java)
                                    .setAction(MonitorService.ACTION_REFRESH_CONTROLLED_APPS)
                            )
                            checkNotNull(service) { "MonitorService refresh was not dispatched" }
                        } catch (error: Exception) {
                            Log.e(AppDatabase.CONFIG_LOG_TAG, "Controlled app saved but refresh dispatch failed", error)
                            message = "已保存，但监控未能更新，请重试保存"
                        }
                    }
                    if (!isDestroyed) {
                        launcherApps = launcherApps.map {
                            if (it.packageName == app.packageName) it.copy(enabled = enabled) else it
                        }
                        configSaving = false
                        appManagerMessage = message
                        loadControlledApps()
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.CONFIG_LOG_TAG, "Controlled app save enqueue failed", error)
            configSaving = false
            appManagerMessage = "保存失败，配置未更改，请重试"
        }
    }

    private fun saveDailyQuota(targetPackageName: String, input: String) {
        if (targetPackageName in quotaSavingPackages) return
        quotaSaveMessages = quotaSaveMessages - targetPackageName
        val minutes = input.toIntOrNull()
        if (input.isNotEmpty() &&
            (input.any { it !in '0'..'9' } || minutes == null || minutes !in 1..1440)
        ) {
            quotaErrors = quotaErrors + (targetPackageName to "请输入 1～1440 的整数分钟数")
            return
        }
        quotaErrors = quotaErrors - targetPackageName
        quotaSavingPackages = quotaSavingPackages + targetPackageName
        val appContext = applicationContext
        try {
            AppDatabase.executor.execute {
                val errorMessage = try {
                    val dao = AppDatabase.getInstance(appContext).controlledAppConfigDao()
                    val existingConfig = checkNotNull(
                        dao.getAllControlledApps().firstOrNull { it.packageName == targetPackageName }
                    ) { "Controlled app config no longer exists" }
                    val oldDailyQuotaMinutes = existingConfig.dailyQuotaMinutes
                    // Update only this column on the existing row; never insert or replace a config.
                    val updated = dao.updateDailyQuotaMinutes(targetPackageName, minutes)
                    check(updated == 1) { "Controlled app config no longer exists" }
                    if (oldDailyQuotaMinutes != minutes) {
                        DailyQuotaThresholdStore(appContext).resetForPackage(targetPackageName)
                        Log.i("SELF_CONTROL_QUOTA", "THRESHOLD_RESET_FOR_QUOTA_CHANGE " +
                            "packageName=$targetPackageName oldDailyQuotaMinutes=$oldDailyQuotaMinutes " +
                            "newDailyQuotaMinutes=$minutes")
                    }
                    null
                } catch (error: Exception) {
                    Log.e(AppDatabase.CONFIG_LOG_TAG, "Daily quota save failed package=$targetPackageName", error)
                    "保存失败，请重试"
                }
                runOnUiThread {
                    // Refresh the running Service even if the Activity was destroyed during saving.
                    if (errorMessage == null && MonitorService.isRunning) {
                        try {
                            val service = appContext.startService(
                                Intent(appContext, MonitorService::class.java)
                                    .setAction(MonitorService.ACTION_REFRESH_CONTROLLED_APPS)
                            )
                            checkNotNull(service) { "MonitorService refresh was not dispatched" }
                            Log.i(AppDatabase.CONFIG_LOG_TAG, "CONFIG_REFRESH_REQUESTED " +
                                "packageName=$targetPackageName dailyQuotaMinutes=$minutes")
                        } catch (error: Exception) {
                            Log.e(AppDatabase.CONFIG_LOG_TAG, "CONFIG_REFRESH_REQUEST_FAILED " +
                                "packageName=$targetPackageName dailyQuotaMinutes=$minutes", error)
                            if (!isDestroyed) {
                                quotaErrors = quotaErrors + (targetPackageName to "已保存，但监控未能更新，请重试保存")
                            }
                        }
                    } else if (errorMessage == null) {
                        Log.i(AppDatabase.CONFIG_LOG_TAG, "CONFIG_REFRESH_SKIPPED serviceRunning=false " +
                            "packageName=$targetPackageName dailyQuotaMinutes=$minutes")
                    }
                    if (!isDestroyed) {
                        quotaSavingPackages = quotaSavingPackages - targetPackageName
                        if (errorMessage == null) {
                            quotaInputs = quotaInputs + (targetPackageName to minutes?.toString().orEmpty())
                            quotaSaveMessages = quotaSaveMessages + (targetPackageName to
                                if (minutes == null) "已取消每日额度" else "每日额度已保存为 $minutes 分钟")
                            loadControlledApps()
                        } else {
                            quotaErrors = quotaErrors + (targetPackageName to errorMessage)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.CONFIG_LOG_TAG, "Daily quota save enqueue failed package=$targetPackageName", error)
            quotaSavingPackages = quotaSavingPackages - targetPackageName
            quotaErrors = quotaErrors + (targetPackageName to "保存失败，请重试")
        }
    }

    private fun saveQuotaEnforcementMode(targetPackageName: String, mode: QuotaEnforcementMode) {
        if (targetPackageName in modeSavingPackages) return
        modeErrors = modeErrors - targetPackageName
        modeSavingPackages = modeSavingPackages + targetPackageName
        val appContext = applicationContext
        val storageValue = mode.name
        try {
            AppDatabase.executor.execute {
                var roomUpdated = false
                val errorMessage = try {
                    val dao = AppDatabase.getInstance(appContext).controlledAppConfigDao()
                    val updated = dao.updateQuotaEnforcementMode(targetPackageName, storageValue)
                    check(updated == 1) { "Controlled app config no longer exists" }
                    roomUpdated = true
                    null
                } catch (error: Exception) {
                    Log.e(AppDatabase.CONFIG_LOG_TAG, "Quota mode save failed package=$targetPackageName", error)
                    "模式保存失败，请重试"
                }
                runOnUiThread {
                    var displayError = errorMessage
                    if (roomUpdated && MonitorService.isRunning) {
                        try {
                            val service = appContext.startService(
                                Intent(appContext, MonitorService::class.java)
                                    .setAction(MonitorService.ACTION_REFRESH_CONTROLLED_APPS)
                            )
                            checkNotNull(service) { "MonitorService refresh was not dispatched" }
                            Log.i(AppDatabase.CONFIG_LOG_TAG, "CONFIG_REFRESH_REQUESTED " +
                                "packageName=$targetPackageName quotaEnforcementMode=$storageValue")
                        } catch (error: Exception) {
                            Log.e(AppDatabase.CONFIG_LOG_TAG, "CONFIG_REFRESH_REQUEST_FAILED " +
                                "packageName=$targetPackageName quotaEnforcementMode=$storageValue", error)
                            displayError = listOfNotNull(displayError, "已保存，但监控未能更新，请重试保存")
                                .joinToString("\n")
                        }
                    }
                    if (!isDestroyed) {
                        modeSavingPackages = modeSavingPackages - targetPackageName
                        if (roomUpdated) {
                            modeInputs = modeInputs + (targetPackageName to mode)
                            loadControlledApps()
                        }
                        displayError?.let { modeErrors = modeErrors + (targetPackageName to it) }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.CONFIG_LOG_TAG, "Quota mode save enqueue failed package=$targetPackageName", error)
            modeSavingPackages = modeSavingPackages - targetPackageName
            modeErrors = modeErrors + (targetPackageName to "模式保存失败，请重试")
        }
    }

    private fun loadControlledApps() {
        val appContext = applicationContext
        val generation = ++quotaReadGeneration
        quotaDataLoading = true
        quotaDataError = null
        try {
            AppDatabase.executor.execute {
                var configs: List<ControlledAppConfig>? = null
                var usage = emptyMap<String, Long>()
                var extraMinutes = emptyMap<String, Int>()
                var readError: String? = null
                val result = try {
                    configs = AppDatabase.getInstance(appContext).controlledAppConfigDao()
                        .getAllControlledApps()
                    val quotaExtraTimeStore = QuotaExtraTimeStore(appContext)
                    val nowMillis = System.currentTimeMillis()
                    extraMinutes = configs.associate { app ->
                        app.packageName to quotaExtraTimeStore.getExtraMinutes(app.packageName, nowMillis)
                    }
                    if (!hasUsageAccessPermission(appContext)) {
                        readError = "今日使用数据不可用，请开启使用情况访问权限后刷新。"
                    } else if (appContext.getSystemService(Context.USAGE_STATS_SERVICE) !is UsageStatsManager) {
                        readError = "今日使用数据不可用，请稍后刷新重试。"
                    } else {
                        usage = try {
                            TodayUsageStatsReader.getTodayUsageMillis(appContext, configs.map { it.packageName })
                        } catch (error: Exception) {
                            Log.e(AppDatabase.CONFIG_LOG_TAG, "Today usage query failed", error)
                            readError = "今日使用读取失败，请刷新重试。"
                            emptyMap()
                        }
                        if (!hasUsageAccessPermission(appContext)) {
                            readError = "今日使用数据不可用，请开启使用情况访问权限后刷新。"
                        }
                    }
                    if (configs.isEmpty()) "暂无受控 App 配置" else ""
                } catch (error: Exception) {
                    Log.e(AppDatabase.CONFIG_LOG_TAG, "Controlled app config query failed", error)
                    readError = "额度数据读取失败，请刷新重试。"
                    ""
                }
                val updatedAt = if (readError == null) System.currentTimeMillis() else null
                runOnUiThread {
                    if (!isDestroyed && generation == quotaReadGeneration) {
                        configs?.let { controlledApps = it }
                        todayUsageMillis = usage
                        extraMinutesByPackage = extraMinutes
                        controlledAppsResult = result
                        quotaDataError = readError
                        quotaDataLoading = false
                        if (updatedAt != null) quotaDataUpdatedAtMillis = updatedAt
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.CONFIG_LOG_TAG, "Controlled app config query enqueue failed", error)
            quotaDataLoading = false
            quotaDataError = "额度数据读取失败，请刷新重试。"
        }
    }

    // Called by the future Reports entry on the UI thread.
    private fun loadReportsHistory() {
        if (reportsHistoryLoading || isDestroyed) return
        reportsHistoryLoading = true
        reportsHistory = emptyList()
        reportsHistoryError = null
        try {
            AppDatabase.executor.execute {
                try {
                    val history = focusSessionStore.getSessionHistory()
                    runOnUiThread {
                        if (!isDestroyed) {
                            reportsHistory = history
                            reportsHistoryLoading = false
                        }
                    }
                } catch (error: Exception) {
                    Log.e(AppDatabase.LOG_TAG, "Reports history load failed", error)
                    runOnUiThread {
                        if (!isDestroyed) {
                            reportsHistoryError = "专注历史读取失败，请重试"
                            reportsHistoryLoading = false
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.LOG_TAG, "Reports history load enqueue failed", error)
            reportsHistoryError = "专注历史读取失败，请重试"
            reportsHistoryLoading = false
        }
    }

    private fun loadGateEvents() {
        gateEventsLoading = true
        gateEventsResult = "正在查询…"
        val appContext = applicationContext
        try {
            AppDatabase.executor.execute {
                val result = try {
                    val events = AppDatabase.getInstance(appContext).gateEventDao().getAllGateEvents()
                    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    "当前数据库记录总数：${events.size}\n\n" + events.take(10).joinToString("\n\n") {
                        "时间：${formatter.format(Date(it.eventTime))}\n" +
                            "packageName：${it.packageName}\nreason：${it.reason ?: "null"}\naction：${it.action}"
                    }.ifEmpty { "暂无记录" }
                } catch (error: Exception) {
                    Log.e(AppDatabase.LOG_TAG, "GateEvent query failed", error)
                    "查询失败，请重试"
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        gateEventsResult = result
                        gateEventsLoading = false
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(AppDatabase.LOG_TAG, "GateEvent query enqueue failed", error)
            gateEventsResult = "查询失败，请重试"
            gateEventsLoading = false
        }
    }

    private fun refreshPermissionStates() {
        usageAccessGranted = hasUsageAccessPermission(this)
        overlayGranted = Settings.canDrawOverlays(this)
    }

    private fun refreshEntryPermissionGuide() {
        // Only two permission-status queries; no usage history or database work.
        refreshPermissionStates()
        missingPermissions = missingAppPermissions(usageAccessGranted, overlayGranted)
        showPermissionGuide = missingPermissions.isNotEmpty()
    }

    private fun openPermissionGuideSettings(permission: AppSetupPermission) {
        val intent = when (permission) {
            AppSetupPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AppSetupPermission.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")
            )
        }
        try {
            startActivity(intent)
            showPermissionGuide = false
        } catch (error: RuntimeException) {
            Log.w("SELF_CONTROL_PERMISSIONS", "Permission settings unavailable", error)
            Toast.makeText(this, "无法打开权限页面，请前往系统设置手动开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensureFocusMonitoring() {
        val session = focusSnapshot ?: return
        if (!focusUiResumed || isDestroyed || isFinishing || focusEnding || focusLoading ||
            System.currentTimeMillis() >= session.endsAtMillis || notificationPermissionPending) return
        // Reuse the service start without opening a permission dialog on each resume.
        startMonitorService()
    }

    private fun requestStartMonitorService() {
        if (MonitorService.isMonitorEffective || notificationPermissionPending) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionPending = true
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (error: RuntimeException) {
                notificationPermissionPending = false
                Log.e("SELF_CONTROL_FOCUS", "Monitor notification permission request failed", error)
                Toast.makeText(this, "通知权限请求失败，请重试启动监控", Toast.LENGTH_LONG).show()
            }
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        try {
            MonitorService.startIfNeeded(this)
        } catch (error: RuntimeException) {
            Log.e("SELF_CONTROL_FOCUS", "Monitor service start failed", error)
            Toast.makeText(this, "监控启动失败，请重试启动监控", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun detectRecentForegroundApp() {
        usageAccessGranted = hasUsageAccessPermission(this)
        if (!usageAccessGranted) {
            foregroundResult = "请先开启使用情况访问权限"
            return
        }

        try {
            val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 5 * 60_000L
            val events = manager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            // These constants share value 1; use the name appropriate for the OS version.
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val records = mutableListOf<UsageEventRecord>()
            while (events != null && events.hasNextEvent()) {
                if (!events.getNextEvent(event)) break
                // Copy values because getNextEvent reuses the mutable Event instance.
                records.add(UsageEventRecord(event.timeStamp, event.packageName, event.eventType))
            }
            val newestFirst = records.sortedByDescending { it.time }
            val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            fun time(record: UsageEventRecord) = formatter.format(Date(record.time))
            rawEventsResult = newestFirst.take(40).joinToString("\n\n") {
                "${time(it)}\n${it.packageName ?: "(null)"}\n${eventTypeName(it.type)}"
            }.ifEmpty { "最近 5 分钟没有 UsageEvents 原始记录" }

            val douyinEvents = newestFirst.filter { it.packageName == "com.ss.android.ugc.aweme" }
            val latestDouyinResume = douyinEvents.firstOrNull { it.type == foregroundType }
            val douyinSummary = if (latestDouyinResume != null) {
                "最近检测到抖音进入前台：\n是\n最近一次时间：\n${time(latestDouyinResume)}"
            } else {
                "最近检测到抖音进入前台：\n否"
            }
            val douyinLog = douyinEvents.take(10).joinToString("\n\n") {
                "${time(it)}\n${eventTypeName(it.type)}"
            }.ifEmpty { "最近 5 分钟没有抖音事件" }
            douyinResult = "$douyinSummary\n\n$douyinLog"

            val excludedPackages = setOf(packageName, "com.selfcontrol.app", "com.huawei.android.launcher", "com.android.systemui")
            val candidate = newestFirst.firstOrNull {
                it.type == foregroundType && !it.packageName.isNullOrBlank() &&
                    it.packageName !in excludedPackages
            }
            foregroundResult = if (candidate == null) {
                "最近未检测到第三方应用候选"
            } else {
                "最近第三方应用候选：\n${candidate.packageName}\n\n事件：\n${eventTypeName(candidate.type)}\n\n时间：\n${time(candidate)}"
            }
        } catch (_: SecurityException) {
            usageAccessGranted = false
            foregroundResult = "请先开启使用情况访问权限"
        }
    }
}

private data class LauncherApp(val packageName: String, val displayName: String, val enabled: Boolean)

private data class UsageEventRecord(val time: Long, val packageName: String?, val type: Int)

private fun eventTypeName(type: Int): String {
    // Resolve public SDK event names, retaining the numeric value for unknown/vendor events.
    val name = when (type) {
        1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "ACTIVITY_RESUMED" else "MOVE_TO_FOREGROUND"
        2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "ACTIVITY_PAUSED" else "MOVE_TO_BACKGROUND"
        else -> UsageEvents.Event::class.java.fields.firstOrNull {
            it.type == Int::class.javaPrimitiveType &&
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.getInt(null) == type
        }?.name ?: "UNKNOWN"
    }
    return "$name ($type)"
}

@androidx.compose.runtime.Composable
private fun ReportsDialog(
    loading: Boolean,
    history: List<FocusSessionHistoryEntry>,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("专注报告") },
        text = {
            when {
                loading -> Text("正在加载专注记录…")
                error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                history.isEmpty() -> Text("暂无专注记录")
                else -> {
                    val completedCount = history.count { it.outcome == FocusSessionOutcome.COMPLETED }
                    val earlyEndedCount = history.count { it.outcome == FocusSessionOutcome.EARLY_ENDED }
                    val totalMillis = history.sumOf { (it.endedAtMillis - it.startedAtMillis).coerceAtLeast(0L) }
                    val formatMinutes: (Long) -> String = {
                        String.format(Locale.getDefault(), "%.2f 分钟", it / 60_000.0)
                    }
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("概览", style = MaterialTheme.typography.titleMedium)
                                Text("已记录专注次数：${history.size}")
                                Text("自然完成次数：$completedCount")
                                Text("提前结束次数：$earlyEndedCount")
                                Text("累计实际专注时间：${formatMinutes(totalMillis)}")
                            }
                        }
                        item { Text("最近记录", style = MaterialTheme.typography.titleMedium) }
                        // getSessionHistory returns oldest first; reverse before limiting.
                        items(history.asReversed().take(10), key = { it.sessionId }) { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(dateFormat.format(Date(entry.startedAtMillis)))
                                Text("计划时长：${formatMinutes(entry.endsAtMillis - entry.startedAtMillis)}")
                                Text("实际时长：${formatMinutes((entry.endedAtMillis - entry.startedAtMillis).coerceAtLeast(0L))}")
                                Text(when (entry.outcome) {
                                    FocusSessionOutcome.COMPLETED -> "自然完成"
                                    FocusSessionOutcome.EARLY_ENDED -> "提前结束"
                                })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@androidx.compose.runtime.Composable
private fun PermissionSection(title: String, granted: Boolean, onSettingsClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(text = "状态：${if (granted) "已开启" else "未开启"}")
        Button(onClick = onSettingsClick, modifier = Modifier.fillMaxWidth()) {
            Text("去设置")
        }
    }
}

internal fun hasUsageAccessPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
}

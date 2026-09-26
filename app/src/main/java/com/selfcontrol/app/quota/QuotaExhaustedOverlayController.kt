package com.selfcontrol.app.quota

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class ExtraTimeUnavailableReason {
    ALREADY_USED_TODAY,
    STRICT_MODE
}

class QuotaExhaustedOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager by lazy {
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: ScrollView? = null
    private var activePresentation: Any? = null
    private var actionHandled = false

    fun isShowing(): Boolean = overlayView != null

    fun show(
        event: QuotaExhaustedEvent,
        displayName: String,
        canRequestExtraTime: Boolean,
        unavailableReason: ExtraTimeUnavailableReason?,
        onAction: (QuotaExhaustedAction) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(event, displayName, canRequestExtraTime, unavailableReason, onAction) }
            return
        }
        var pendingView: View? = null
        try {
            if (!Settings.canDrawOverlays(appContext)) {
                Log.w(TAG, "QUOTA_OVERLAY_SHOW_SKIPPED reason=overlay_permission_missing")
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Log.w(TAG, "QUOTA_OVERLAY_SHOW_SKIPPED reason=requires_android_8")
                return
            }
            val presentation = Any()
            val content = createContent(event, displayName, canRequestExtraTime, unavailableReason) { action ->
                if (activePresentation === presentation && !actionHandled && isShowing()) {
                    actionHandled = true
                    // The caller controls dismissal so HOME can be requested while the window is visible.
                    try {
                        onAction(action)
                    } catch (error: Exception) {
                        Log.e(TAG, "QUOTA_OVERLAY_ACTION_FAILED packageName=${event.packageName} action=$action", error)
                        if (activePresentation === presentation) hide()
                    }
                }
            }
            val existing = overlayView
            if (existing != null) {
                existing.removeAllViews()
                existing.addView(content)
            } else {
                val view = ScrollView(content.context).apply {
                    setBackgroundColor(Color.rgb(18, 20, 26))
                    isFillViewport = true
                    isClickable = true
                    addView(content)
                }
                pendingView = view
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Match the existing focusable, touch-modal overlay window.
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setFitInsetsTypes(0)
                    }
                }
                windowManager.addView(view, params)
                overlayView = view
                pendingView = null
            }
            activePresentation = presentation
            actionHandled = false
            Log.i(TAG, "QUOTA_OVERLAY_SHOW packageName=${event.packageName} displayName=$displayName " +
                "todayUsageMinutes=${event.todayUsageMillis / 60_000L} dailyQuotaMinutes=${event.dailyQuotaMinutes} " +
                "canRequestExtraTime=$canRequestExtraTime")
        } catch (error: RuntimeException) {
            Log.e(TAG, "QUOTA_OVERLAY_SHOW_FAILED packageName=${event.packageName}", error)
            pendingView?.let { view ->
                // addView may fail after partially registering the window.
                try {
                    windowManager.removeViewImmediate(view)
                } catch (cleanupError: RuntimeException) {
                    Log.w(TAG, "QUOTA_OVERLAY_HIDE_FAILED stage=show_cleanup", cleanupError)
                }
            }
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        activePresentation = null
        actionHandled = true
        val view = overlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
            Log.i(TAG, "QUOTA_OVERLAY_HIDE")
        } catch (error: RuntimeException) {
            Log.w(TAG, "QUOTA_OVERLAY_HIDE_FAILED", error)
        } finally {
            overlayView = null
        }
    }

    private fun createContent(
        event: QuotaExhaustedEvent,
        displayName: String,
        canRequestExtraTime: Boolean,
        unavailableReason: ExtraTimeUnavailableReason?,
        onAction: (QuotaExhaustedAction) -> Unit
    ): LinearLayout {
        val context = ContextThemeWrapper(appContext, android.R.style.Theme_Material_NoActionBar)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        fun addText(value: String, size: Float, emphasized: Boolean = false) {
            content.addView(TextView(context).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(if (emphasized) Color.WHITE else Color.rgb(202, 207, 218))
                if (emphasized) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(16))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addText("今日额度已用完", 28f, true)
        addText(displayName, 24f, true)
        addText("今日已使用 ${event.todayUsageMillis / 60_000L} 分钟", 20f)
        addText("每日额度 ${event.dailyQuotaMinutes} 分钟", 20f)
        addText("今日使用时间已达到你设置的每日额度。", 16f)
        if (!canRequestExtraTime) {
            if (unavailableReason == ExtraTimeUnavailableReason.STRICT_MODE) {
                addText("严格模式已开启", 20f, true)
                addText("严格模式下，额度用完后不能申请额外使用时间。", 16f)
            } else {
                addText("今日额外时间已使用", 20f, true)
                addText("今天已使用过一次额外使用时间，当前无法再次申请。", 16f)
            }
        }
        fun addActionButton(label: String, action: QuotaExhaustedAction, color: Int) {
            content.addView(Button(context).apply {
                text = label
                textSize = 18f
                minHeight = dp(56)
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(color)
                setOnClickListener { onAction(action) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        addActionButton("结束使用", QuotaExhaustedAction.STOP_USING, Color.rgb(68, 91, 185))
        if (canRequestExtraTime) {
            addActionButton("申请额外时间", QuotaExhaustedAction.REQUEST_EXTRA_TIME, Color.rgb(48, 53, 68))
        }
        return content
    }

    private companion object {
        const val TAG = "SELF_CONTROL_QUOTA_UI"
    }
}

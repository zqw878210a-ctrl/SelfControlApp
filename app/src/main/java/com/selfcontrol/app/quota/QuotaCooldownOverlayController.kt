package com.selfcontrol.app.quota

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.CountDownTimer
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

class QuotaCooldownOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager by lazy {
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: ScrollView? = null
    private var timer: CountDownTimer? = null
    private var activePresentation: Any? = null
    private var activePackageName: String? = null
    private var completionHandled = false
    private var extraTimeActionHandled = false

    fun isShowing(): Boolean = overlayView != null

    fun show(
        packageName: String,
        displayName: String,
        durationSeconds: Int = 30,
        onCompleted: () -> Unit,
        onExtraTimeSelected: (QuotaExtraTimeAction) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(packageName, displayName, durationSeconds, onCompleted, onExtraTimeSelected) }
            return
        }
        // Invalidate the old presentation before replacing either its content or timer.
        activePresentation = null
        completionHandled = true
        extraTimeActionHandled = true
        timer?.cancel()
        timer = null
        var pendingView: View? = null
        try {
            if (!Settings.canDrawOverlays(appContext)) {
                Log.w(TAG, "QUOTA_COOLDOWN_SHOW_SKIPPED packageName=$packageName reason=overlay_permission_missing")
                hide()
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Log.w(TAG, "QUOTA_COOLDOWN_SHOW_SKIPPED packageName=$packageName reason=requires_android_8")
                hide()
                return
            }
            require(durationSeconds > 0) { "durationSeconds must be positive" }
            val presentation = Any()
            val (content, remainingSecondsView, titleView, descriptionView, secondsLabelView, actionsView) =
                createContent(displayName, durationSeconds) { action ->
                    if (activePresentation === presentation && completionHandled &&
                        !extraTimeActionHandled && isShowing()
                    ) {
                        extraTimeActionHandled = true
                        // Invalidate this presentation before handing the selection to the caller.
                        hide()
                        try {
                            onExtraTimeSelected(action)
                        } catch (error: Exception) {
                            Log.e(TAG, "QUOTA_COOLDOWN_CALLBACK_FAILED packageName=$packageName action=$action", error)
                        }
                    }
                }
            val existing = overlayView
            if (existing != null) {
                // Reuse the registered window so repeated show never stacks windows.
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
            activePackageName = packageName
            completionHandled = false
            extraTimeActionHandled = false
            timer = object : CountDownTimer(durationSeconds.toLong() * 1_000L, 1_000L) {
                override fun onTick(millisUntilFinished: Long) {
                    if (activePresentation !== presentation || completionHandled || !isShowing()) return
                    // Round up so the immediate first tick still shows 30, not 29.
                    remainingSecondsView.text = ((millisUntilFinished + 999L) / 1_000L).toString()
                }

                override fun onFinish() {
                    if (activePresentation !== presentation || completionHandled || !isShowing()) return
                    completionHandled = true
                    timer = null
                    remainingSecondsView.text = "0"
                    remainingSecondsView.visibility = View.GONE
                    secondsLabelView.visibility = View.GONE
                    titleView.text = "冷静时间结束"
                    descriptionView.text = "现在可以选择是否申请额外使用时间。"
                    actionsView.visibility = View.VISIBLE
                    // Keep this completed presentation visible until an explicit hide or replacement.
                    Log.i(TAG, "QUOTA_COOLDOWN_COMPLETED packageName=$packageName")
                    try {
                        onCompleted()
                    } catch (error: Exception) {
                        Log.e(TAG, "QUOTA_COOLDOWN_CALLBACK_FAILED packageName=$packageName", error)
                    }
                }
            }
            Log.i(TAG, "QUOTA_COOLDOWN_SHOW packageName=$packageName displayName=$displayName " +
                "durationSeconds=$durationSeconds")
            timer?.start()
        } catch (error: RuntimeException) {
            Log.e(TAG, "QUOTA_COOLDOWN_SHOW_FAILED packageName=$packageName", error)
            pendingView?.let { view ->
                // addView may fail after partially registering the window.
                try {
                    windowManager.removeViewImmediate(view)
                } catch (cleanupError: RuntimeException) {
                    Log.w(TAG, "QUOTA_COOLDOWN_HIDE_FAILED packageName=$packageName stage=show_cleanup", cleanupError)
                }
            }
            hide()
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        activePresentation = null
        completionHandled = true
        extraTimeActionHandled = true
        timer?.cancel()
        timer = null
        val packageName = activePackageName
        activePackageName = null
        val view = overlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
            Log.i(TAG, "QUOTA_COOLDOWN_HIDE packageName=$packageName")
        } catch (error: RuntimeException) {
            Log.w(TAG, "QUOTA_COOLDOWN_HIDE_FAILED packageName=$packageName", error)
        } finally {
            overlayView = null
        }
    }

    private data class CooldownContent(
        val root: LinearLayout,
        val remainingSecondsView: TextView,
        val titleView: TextView,
        val descriptionView: TextView,
        val secondsLabelView: TextView,
        val actionsView: LinearLayout
    )

    private fun createContent(
        displayName: String,
        durationSeconds: Int,
        onAction: (QuotaExtraTimeAction) -> Unit
    ): CooldownContent {
        val context = ContextThemeWrapper(appContext, android.R.style.Theme_Material_NoActionBar)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        fun addText(value: String, size: Float, emphasized: Boolean = false): TextView {
            val textView = TextView(context).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(if (emphasized) Color.WHITE else Color.rgb(202, 207, 218))
                if (emphasized) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(16))
            }
            content.addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            return textView
        }
        val titleView = addText("冷静一下", 28f, true)
        addText(displayName, 24f, true)
        addText("你已经用完今天设置的使用额度。", 18f)
        val descriptionView = addText("请等待冷静时间结束后，\n再决定是否申请额外使用时间。", 16f)
        val remainingSecondsView = addText(durationSeconds.toString(), 72f, true)
        val secondsLabelView = addText("秒", 20f)
        val actionsView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fun addActionButton(label: String, action: QuotaExtraTimeAction) {
            actionsView.addView(Button(context).apply {
                text = label
                textSize = 18f
                minHeight = dp(56)
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Color.rgb(68, 91, 185))
                setOnClickListener { onAction(action) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            })
        }
        addActionButton("额外使用 5 分钟", QuotaExtraTimeAction.ADD_5_MINUTES)
        addActionButton("额外使用 10 分钟", QuotaExtraTimeAction.ADD_10_MINUTES)
        content.addView(actionsView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return CooldownContent(content, remainingSecondsView, titleView, descriptionView, secondsLabelView, actionsView)
    }

    private companion object {
        const val TAG = "SELF_CONTROL_QUOTA_UI"
    }
}

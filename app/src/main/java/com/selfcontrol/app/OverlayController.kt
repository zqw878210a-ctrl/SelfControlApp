package com.selfcontrol.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class OverlayController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: View? = null

    fun isShowing(): Boolean = overlayView != null

    fun show(onContinue: ((String?, Long) -> Unit)? = null, onAbandon: ((String?, Long) -> Unit)? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(onContinue, onAbandon) }
            return
        }
        if (isShowing()) return
        if (!Settings.canDrawOverlays(appContext)) {
            Toast.makeText(appContext, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(appContext, "测试 Overlay 需要 Android 8.0 或以上", Toast.LENGTH_SHORT).show()
            return
        }
        val view = createContent(onContinue, onAbandon)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Keep the window focusable and touch-modal. Do not enable touch-through flags.
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
        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to show test overlay", error)
            // addView can fail after partially registering the view.
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: RuntimeException) {
                // No registered view remains in the usual permission/add failure case.
            }
            Toast.makeText(appContext, "显示测试 Overlay 失败，请检查悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hide() }
            return
        }
        val view = overlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
            overlayView = null
        } catch (error: IllegalArgumentException) {
            // Already removed or never attached; repeated hide remains safe.
            overlayView = null
            Log.w(TAG, "Test overlay was already detached", error)
        } catch (error: RuntimeException) {
            // Retain an attached view so a later hide can retry without stacking another.
            if (!view.isAttachedToWindow) overlayView = null
            Log.e(TAG, "Unable to remove test overlay", error)
        }
    }

    private fun createContent(onContinue: ((String?, Long) -> Unit)?, onAbandon: ((String?, Long) -> Unit)?): View {
        val context = ContextThemeWrapper(appContext, android.R.style.Theme_Material_Light_NoActionBar)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        fun addText(value: String, size: Float) {
            content.addView(TextView(context).apply {
                text = value
                textSize = size
                setTextColor(Color.BLACK)
                setPadding(0, dp(8), 0, dp(16))
            })
        }
        addText("打开应用之前", 28f)
        addText("这次使用是为了什么？", 22f)
        val reasons = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }
        val reasonValues = mutableMapOf<Int, String>()
        listOf(
            "SEARCH_CONTENT" to "查找具体内容",
            "VIEW_SHARED_CONTENT" to "查看别人发来的内容",
            "HANDLE_TASK" to "处理事情",
            "RELAX" to "放松一下",
            "BORED" to "无聊",
            "UNCONSCIOUS" to "下意识打开"
        ).forEach { (value, label) ->
            reasons.addView(RadioButton(context).apply {
                id = View.generateViewId()
                reasonValues[id] = value
                text = label
                textSize = 18f
                minHeight = dp(48)
            })
        }
        content.addView(reasons)
        val abandonButton = Button(context).apply {
            text = "放弃"
            setOnClickListener {
                try {
                    onAbandon?.invoke(reasonValues[reasons.checkedRadioButtonId], System.currentTimeMillis())
                } finally {
                    if (onAbandon == null) {
                        hide()
                    } else {
                        // Retain the window briefly after the synchronous HOME attempt.
                        val abandonedView = overlayView
                        val hideOverlay = Runnable {
                            if (overlayView === abandonedView) hide()
                            if (abandonedView?.isAttachedToWindow == true) {
                                Log.e(GATE_LOG_TAG, "overlay hide failed: abandoned window still attached")
                            } else {
                                Log.i(GATE_LOG_TAG, "overlay hidden")
                            }
                        }
                        Log.i(GATE_LOG_TAG, "overlay hide scheduled delayMs=200")
                        if (!mainHandler.postDelayed(hideOverlay, 200L)) {
                            hideOverlay.run()
                        }
                    }
                }
            }
        }
        val continueButton = Button(context).apply {
            text = "继续使用"
            isEnabled = false
            setOnClickListener {
                try {
                    onContinue?.invoke(reasonValues[reasons.checkedRadioButtonId], System.currentTimeMillis())
                } finally {
                    hide()
                }
            }
        }
        reasons.setOnCheckedChangeListener { _, checkedId ->
            continueButton.isEnabled = checkedId != View.NO_ID
        }
        content.addView(abandonButton)
        content.addView(continueButton)
        return ScrollView(context).apply {
            setBackgroundColor(Color.WHITE)
            isFillViewport = true
            isClickable = true
            addView(content)
        }
    }

    companion object {
        private const val TAG = "TestIntentGate"
        private const val GATE_LOG_TAG = "SELF_CONTROL_GATE"
        @Volatile private var instance: OverlayController? = null

        fun getInstance(context: Context): OverlayController = instance ?: synchronized(this) {
            instance ?: OverlayController(context).also { instance = it }
        }
    }
}

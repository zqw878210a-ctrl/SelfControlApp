package com.selfcontrol.app.focus

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

internal const val FOCUS_OVERLAY_ATTACH_GRACE_MS = 1_000L

internal fun canReuseFocusOverlayWindow(
    hasView: Boolean,
    isAttached: Boolean,
    hasWindowToken: Boolean,
    hasParent: Boolean,
    isVisible: Boolean,
    firstAttachAgeMillis: Long?
): Boolean = hasView && hasParent &&
    ((isAttached && hasWindowToken && isVisible) ||
        (!isAttached && firstAttachAgeMillis != null &&
            firstAttachAgeMillis in 0 until FOCUS_OVERLAY_ATTACH_GRACE_MS))

class FocusBlockedOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager by lazy {
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var overlayView: ScrollView? = null
    @Volatile private var awaitingAttachView: View? = null
    private var attachRequestedAtMillis = 0L
    @Volatile private var activePackageName: String? = null
    private var activeSession: FocusSessionSnapshot? = null
    private var activePresentation: Any? = null
    private var timer: CountDownTimer? = null
    private var actionHandled = false

    fun isShowing(): Boolean {
        val view = overlayView
        return canReuseFocusOverlayWindow(
            hasView = view != null,
            isAttached = view?.isAttachedToWindow == true,
            hasWindowToken = view?.windowToken != null,
            hasParent = view?.parent != null,
            isVisible = view?.isShown == true && view.windowVisibility == View.VISIBLE,
            firstAttachAgeMillis = if (view != null && awaitingAttachView === view) {
                SystemClock.elapsedRealtime() - attachRequestedAtMillis
            } else null
        )
    }

    fun getShowingPackageName(): String? = activePackageName

    // Health evidence must not treat the first-attach grace period as a visible window.
    fun isAttachedAndVisible(): Boolean = overlayView?.let { view ->
        view.isAttachedToWindow && view.windowToken != null && view.parent != null &&
            view.isShown && view.windowVisibility == View.VISIBLE
    } == true

    fun show(
        packageName: String,
        displayName: String,
        session: FocusSessionSnapshot,
        onStopUsing: () -> Unit
    ) {
        Log.i("RC001_OVERLAY_REQUEST", "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} " +
            "packageName=$packageName endsAtMillis=${session.endsAtMillis} " +
            "onMainThread=${Looper.myLooper() == Looper.getMainLooper()}")
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(packageName, displayName, session, onStopUsing) }
            return
        }
        // Invalidate callbacks and countdowns before replacing the presentation.
        activePresentation = null
        timer?.cancel()
        timer = null
        var pendingView: View? = null
        try {
            if (!Settings.canDrawOverlays(appContext)) {
                Log.w("RC001_OVERLAY_SHOW_FAILED", "packageName=$packageName reason=overlay_permission_missing")
                Log.w(TAG, "FOCUS_OVERLAY_SHOW_SKIPPED packageName=$packageName reason=overlay_permission_missing")
                hide()
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Log.w("RC001_OVERLAY_SHOW_FAILED", "packageName=$packageName reason=requires_android_8")
                Log.w(TAG, "FOCUS_OVERLAY_SHOW_SKIPPED packageName=$packageName reason=requires_android_8")
                hide()
                return
            }
            val staleView = overlayView
            if (staleView != null && !isShowing()) {
                Log.w(TAG, "FOCUS_OVERLAY_STALE_WINDOW packageName=$activePackageName " +
                    "attached=${staleView.isAttachedToWindow} token=${staleView.windowToken != null} " +
                    "parent=${staleView.parent != null} shown=${staleView.isShown} " +
                    "windowVisibility=${staleView.windowVisibility} " +
                    "awaitingAttach=${awaitingAttachView === staleView}")
                // hide clears references even if WindowManager has already lost the window.
                hide()
            }
            val presentation = Any()
            val (content, remainingView, finishedView) = createContent(displayName) {
                if (activePresentation === presentation && !actionHandled && isShowing()) {
                    actionHandled = true
                    Log.i(TAG, "FOCUS_OVERLAY_STOP_USING packageName=$packageName")
                    // Keep the window visible, even if the caller fails. Only the caller may dismiss it.
                    try {
                        onStopUsing()
                    } catch (error: Exception) {
                        Log.e(TAG, "FOCUS_OVERLAY_CALLBACK_FAILED packageName=$packageName", error)
                    }
                }
            }
            val existing = overlayView
            if (existing != null) {
                // Reuse one registered window for repeated shows and package/session changes.
                existing.removeAllViews()
                existing.addView(content)
                Log.i("RC001_OVERLAY_SHOW_SUCCESS", "packageName=$packageName operation=reuse_window")
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
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(attachedView: View) {
                        if (awaitingAttachView === attachedView) awaitingAttachView = null
                    }

                    override fun onViewDetachedFromWindow(detachedView: View) {
                        if (awaitingAttachView === detachedView) awaitingAttachView = null
                    }
                })
                // addView registers the parent before the first traversal attaches the view.
                // Allow a short first-attach grace period, never renew it when reusing the view.
                attachRequestedAtMillis = SystemClock.elapsedRealtime()
                awaitingAttachView = view
                windowManager.addView(view, params)
                Log.i("RC001_OVERLAY_SHOW_SUCCESS", "packageName=$packageName operation=window_manager_add_view")
                overlayView = view
                pendingView = null
            }
            activePackageName = packageName
            activeSession = session
            activePresentation = presentation
            actionHandled = false
            var countdownFinished = false
            fun updateRemainingText(remainingMillis: Long) {
                val seconds = remainingMillis.coerceAtLeast(0L) / 1_000L
                remainingView.text = "剩余专注时间：${seconds / 60L}分" +
                    "${(seconds % 60L).toString().padStart(2, '0')}秒"
            }
            fun finishCountdown() {
                if (activePresentation !== presentation || countdownFinished || !isShowing()) return
                countdownFinished = true
                timer?.cancel()
                timer = null
                updateRemainingText(0L)
                finishedView.visibility = View.VISIBLE
                Log.i(TAG, "FOCUS_OVERLAY_COUNTDOWN_FINISHED packageName=$packageName")
                // Expiration never hides the overlay or invokes the action callback.
            }
            Log.i(TAG, "FOCUS_OVERLAY_SHOWN packageName=$packageName endsAtMillis=${session.endsAtMillis}")
            val remainingMillis = (session.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            updateRemainingText(remainingMillis)
            if (remainingMillis == 0L) {
                finishCountdown()
            } else {
                timer = object : CountDownTimer(remainingMillis, 1_000L) {
                    override fun onTick(millisUntilFinished: Long) {
                        if (activePresentation !== presentation || countdownFinished || !isShowing()) return
                        // The supplied session's wall-clock end remains the source of display time.
                        val remaining = (session.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                        updateRemainingText(remaining)
                        if (remaining == 0L) finishCountdown()
                    }

                    override fun onFinish() {
                        finishCountdown()
                    }
                }
                timer?.start()
            }
        } catch (error: RuntimeException) {
            Log.e("RC001_OVERLAY_SHOW_FAILED", "packageName=$packageName", error)
            Log.e(TAG, "FOCUS_OVERLAY_SHOW_FAILED packageName=$packageName", error)
            awaitingAttachView = null
            pendingView?.let { view ->
                try {
                    windowManager.removeViewImmediate(view)
                    Log.i("RC001_OVERLAY_REMOVE", "packageName=$packageName stage=show_cleanup result=removed")
                } catch (cleanupError: RuntimeException) {
                    Log.w("RC001_OVERLAY_REMOVE", "packageName=$packageName stage=show_cleanup result=failed", cleanupError)
                    Log.w(TAG, "FOCUS_OVERLAY_HIDE_FAILED packageName=$packageName stage=show_cleanup", cleanupError)
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
        Log.i("RC001_OVERLAY_REMOVE", "packageName=$activePackageName stage=hide_request hasView=${overlayView != null}")
        activePresentation = null
        timer?.cancel()
        timer = null
        val packageName = activePackageName
        activePackageName = null
        activeSession = null
        actionHandled = false
        awaitingAttachView = null
        attachRequestedAtMillis = 0L
        val view = overlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
            Log.i("RC001_OVERLAY_REMOVE", "packageName=$packageName stage=hide result=removed")
            Log.i(TAG, "FOCUS_OVERLAY_HIDDEN packageName=$packageName")
        } catch (error: RuntimeException) {
            Log.w("RC001_OVERLAY_REMOVE", "packageName=$packageName stage=hide result=failed", error)
            Log.w(TAG, "FOCUS_OVERLAY_HIDE_FAILED packageName=$packageName", error)
        } finally {
            overlayView = null
        }
    }

    private data class FocusContent(
        val root: LinearLayout,
        val remainingView: TextView,
        val finishedView: TextView
    )

    private fun createContent(displayName: String, onStopUsing: () -> Unit): FocusContent {
        val context = ContextThemeWrapper(appContext, android.R.style.Theme_Material_NoActionBar)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        fun addText(value: String, size: Float, emphasized: Boolean = false): TextView {
            val view = TextView(context).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(if (emphasized) Color.WHITE else Color.rgb(202, 207, 218))
                if (emphasized) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(16))
            }
            content.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            return view
        }
        addText("专注进行中", 28f, true)
        addText(displayName, 24f, true)
        addText("当前处于专注时间，暂时无法使用此 App。", 16f)
        val remainingView = addText("剩余专注时间：0分00秒", 24f)
        val finishedView = addText("专注时间已结束，正在恢复使用规则。", 16f).apply {
            visibility = View.GONE
        }
        content.addView(Button(context).apply {
            text = "结束使用"
            textSize = 18f
            minHeight = dp(56)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(68, 91, 185))
            setOnClickListener { onStopUsing() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })
        return FocusContent(content, remainingView, finishedView)
    }

    private companion object {
        const val TAG = "SELF_CONTROL_FOCUS_UI"
    }
}

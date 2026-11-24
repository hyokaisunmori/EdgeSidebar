package com.example.edgesidebar

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingSidebarService : Service() {

    companion object {
        @Volatile
        var isRunning = false
        private const val CHANNEL_ID = "edge_sidebar_channel"
        private const val NOTIF_ID = 1001
        private const val TAG = "EdgeSidebar"
        private const val HANDLE_W_DP = 28
        private const val HANDLE_H_DP = 90
        private const val ANIM_MS = 200L
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    @Volatile private var panelPkgs: List<String> = emptyList()

    // Edge handle
    private var edgeView: View? = null
    private var edgePill: View? = null
    private lateinit var edgeParams: WindowManager.LayoutParams
    private var downRawX = 0f
    private var downRawY = 0f
    private var startY = 0
    private var dragging = false
    private var gestureOpened = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // Panel
    private var panelRoot: SidebarRootLayout? = null
    private var panelCard: View? = null
    private var closing = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_ALPHA -> applyAlpha()
            Prefs.KEY_APPS -> reloadApps()
        }
    }

    private val longPressRunnable = Runnable {
        dragging = true
        edgeView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        edgePill?.animate()?.scaleX(2f)?.alpha(1f)?.setDuration(120)?.start()
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.of(this)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        addEdgeHandle()
        reloadApps()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removePanel()
        edgeView?.let {
            edgeParams.y = clampY(edgeParams.y)
            try { wm.updateViewLayout(it, edgeParams) } catch (e: Exception) { Log.w(TAG, e) }
        }
    }

    override fun onDestroy() {
        isRunning = false
        ui.removeCallbacksAndMessages(null)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        removePanel()
        edgeView?.let { try { wm.removeViewImmediate(it) } catch (e: Exception) { Log.w(TAG, e) } }
        edgeView = null
        executor.shutdownNow()
        iconCache.clear()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Edge Sidebar", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_more)
            .setContentTitle("Edge Sidebar aktif")
            .setContentText("Usap garis di tepi kanan untuk membuka panel")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun clampY(y: Int): Int {
        val max = (resources.displayMetrics.heightPixels - dp(HANDLE_H_DP)).coerceAtLeast(0)
        return y.coerceIn(0, max)
    }

    private fun handleAlpha(): Float = Prefs.alphaPercent(this) / 100f

    private fun applyAlpha() {
        if (!dragging) edgePill?.alpha = handleAlpha()
    }

    // ------------------------------------------------------------ edge handle

    private fun addEdgeHandle() {
        val ctx = ContextThemeWrapper(this, R.style.Theme_EdgeSidebar)
        val v = LayoutInflater.from(ctx).inflate(R.layout.layout_edge_handle, null)
        val pill = v.findViewById<View>(R.id.edgePill)
        pill.alpha = handleAlpha()

        val h = dp(HANDLE_H_DP)
        val saved = Prefs.handleY(this)
        val initialY = if (saved >= 0) saved else (resources.displayMetrics.heightPixels - h) / 2

        edgeParams = WindowManager.LayoutParams(
            dp(HANDLE_W_DP), h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = clampY(initialY)
        }

        v.setOnTouchListener { _, e -> onEdgeTouch(e) }

        try {
            wm.addView(v, edgeParams)
        } catch (e: Exception) {
            Log.e(TAG, "addView handle gagal", e)
            stopSelf()
            return
        }
        edgeView = v
        edgePill = pill

        // Jangan biarkan gestur Back sistem merebut usapan dari tepi kanan (Android 10+).
        if (Build.VERSION.SDK_INT >= 29) {
            v.post { v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height)) }
        }
    }

    private fun onEdgeTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startY = edgeParams.y
                dragging = false
                gestureOpened = false
                ui.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (dragging) {
                    edgeParams.y = clampY((startY + dy).roundToInt())
                    edgeView?.let {
                        try { wm.updateViewLayout(it, edgeParams) } catch (ex: Exception) { Log.w(TAG, ex) }
                    }
                } else if (!gestureOpened) {
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) ui.removeCallbacks(longPressRunnable)
                    // GESTUR BUKA: usap dari tepi kanan ke kiri
                    if (dx < -dp(20) && abs(dx) > abs(dy)) {
                        gestureOpened = true
                        ui.removeCallbacks(longPressRunnable)
                        openPanel()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ui.removeCallbacks(longPressRunnable)
                if (dragging) {
                    dragging = false
                    Prefs.setHandleY(this, edgeParams.y)
                    edgePill?.animate()?.scaleX(1f)?.alpha(handleAlpha())?.setDuration(120)?.start()
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ icons / apps

    private fun reloadApps() {
        try {
            executor.execute {
                val pkgs = computePkgs()
                panelPkgs = pkgs
                pkgs.forEach { cachedIcon(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, e)
        }
    }

    private fun computePkgs(): List<String> {
        val pm = packageManager
        return Prefs.apps(this)
            .filter { pm.getLaunchIntentForPackage(it) != null }
            .sortedBy {
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString().lowercase()
                } catch (e: Exception) {
                    it
                }
            }
    }

    private fun cachedIcon(pkg: String): Drawable? {
        iconCache[pkg]?.let { return it }
        return try {
            packageManager.getApplicationIcon(pkg).also { iconCache[pkg] = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun newIcon(pkg: String): Drawable? {
        val d = cachedIcon(pkg) ?: return null
        return d.constantState?.newDrawable(resources)?.mutate() ?: d
    }

    // ------------------------------------------------------------ panel

    private fun openPanel() {
        if (panelRoot != null) return
        val pkgs = panelPkgs.ifEmpty { computePkgs() }
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "Pilih aplikasi dulu di pengaturan Edge Sidebar", Toast.LENGTH_SHORT).show()
            return
        }
        closing = false

        val ctx = ContextThemeWrapper(this, R.style.Theme_EdgeSidebar)
        val root = LayoutInflater.from(ctx).inflate(R.layout.layout_sidebar_panel, null) as SidebarRootLayout
        val card = root.findViewById<View>(R.id.panelCard)
        val scroll = root.findViewById<ScrollView>(R.id.panelScroll)
        val container = root.findViewById<LinearLayout>(R.id.iconContainer)
        card.clipToOutline = true

        var count = 0
        for (pkg in pkgs) {
            val icon = newIcon(pkg) ?: continue
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    if (count > 0) topMargin = dp(16)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(icon)
                setOnClickListener {
                    launchFreeform(pkg)
                    closePanel()
                }
            }
            container.addView(iv)
            count++
        }
        if (count == 0) return

        // Batasi tinggi panel maksimal 70% layar; sisanya bisa di-scroll.
        val maxH = (resources.displayMetrics.heightPixels * 0.7f).roundToInt()
        val contentH = dp(32) + count * dp(48) + (count - 1) * dp(16)
        scroll.layoutParams = (scroll.layoutParams as FrameLayout.LayoutParams).apply {
            height = minOf(contentH, maxH)
        }

        root.panel = card
        root.onDismiss = { closePanel() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // GESTUR BUKA: slide-in dari kanan, Decelerate 200ms
        card.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                card.viewTreeObserver.removeOnPreDrawListener(this)
                card.translationX = (card.width + dp(24)).toFloat()
                card.animate()
                    .translationX(0f)
                    .setDuration(ANIM_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .start()
                return true
            }
        })

        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView panel gagal", e)
            return
        }
        panelRoot = root
        panelCard = card
        edgePill?.visibility = View.INVISIBLE
    }

    private fun closePanel() {
        val card = panelCard ?: return
        if (panelRoot == null || closing) return
        closing = true
        card.animate().cancel()
        card.animate()
            .translationX((card.width + dp(24)).toFloat())
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withLayer()
            .withEndAction { removePanel() }
            .start()
    }

    private fun removePanel() {
        panelRoot?.let {
            try { wm.removeViewImmediate(it) } catch (e: Exception) { Log.w(TAG, e) }
        }
        panelRoot = null
        panelCard = null
        closing = false
        edgePill?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------ freeform launch

    private fun launchFreeform(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, "Aplikasi tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val bw = (w * 0.75f).roundToInt()
        val bh = (h * 0.55f).roundToInt()
        val left = (w - bw) / 2
        val top = (h - bh) / 2
        val bounds = Rect(left, top, left + bw, top + bh)

        // Urutan percobaan: (freeform + bounds) -> (bounds saja) -> (buka biasa)
        val attempts = listOf(true to true, false to true, false to false)
        for ((useMode, useBounds) in attempts) {
            try {
                val opts = ActivityOptions.makeBasic()
                if (useMode) setWindowingMode(opts, 5) // WINDOWING_MODE_FREEFORM
                if (useBounds) opts.setLaunchBounds(bounds)
                startActivity(intent, opts.toBundle())
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Launch attempt (mode=$useMode, bounds=$useBounds) gagal: ${t.message}")
            }
        }
        Toast.makeText(this, "Gagal membuka aplikasi", Toast.LENGTH_SHORT).show()
    }

    private fun setWindowingMode(opts: ActivityOptions, mode: Int) {
        val m = ActivityOptions::class.java.getMethod(
            "setLaunchWindowingMode", Int::class.javaPrimitiveType
        )
        m.invoke(opts, mode)
    }
}

/**
 * Root layar-penuh dari panel.
 * - Sentuh di luar panel  -> onDismiss
 * - Usap panel ke kanan   -> panel mengikuti jari, lepas jauh -> onDismiss, kalau tidak kembali
 * Usapan horizontal di-intercept sehingga tidak bentrok dengan scroll vertikal & klik ikon.
 */
class SidebarRootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var panel: View? = null
    var onDismiss: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val closeDistance = 48 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var startedInPanel = false
    private var swiping = false

    private fun hitPanel(x: Float, y: Float): Boolean {
        val p = panel ?: return false
        val l = p.left + p.translationX
        return x >= l && x <= l + p.width && y >= p.top && y <= p.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startedInPanel = hitPanel(ev.x, ev.y)
                swiping = false
            }
            MotionEvent.ACTION_MOVE -> if (startedInPanel && !swiping) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (dx > slop && dx > abs(dy) * 1.5f) swiping = true
            }
        }
        return swiping
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                startedInPanel = hitPanel(ev.x, ev.y)
                if (!startedInPanel) onDismiss?.invoke() // sentuh area luar panel
            }
            MotionEvent.ACTION_MOVE -> if (swiping) {
                panel?.translationX = (ev.x - downX - slop).coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                swiping = false
                val dx = ev.x - downX
                if (ev.actionMasked == MotionEvent.ACTION_UP && dx > closeDistance) {
                    onDismiss?.invoke()
                } else {
                    panel?.animate()?.translationX(0f)?.setDuration(150)?.start()
                }
            }
        }
        return true
    }
}

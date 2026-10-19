package com.zev3nz.flowcenter

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class TriggerService : AccessibilityService() {

    companion object {
        var instance: TriggerService? = null
    }

    private var strip: View? = null
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var lastShow = 0L
    private var lastCapture = 0L
    private val worker = Executors.newSingleThreadExecutor() // blur work never touches the UI thread

    // Very cheap phones (Android marks them "low RAM") skip the screenshot completely:
    // it needs a big temporary picture in memory, and the panel looks fine without the blur.
    private val lowRam by lazy {
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }

    // While set, we do NOT hijack the system panel (used when the user
    // deliberately opens the system control center from our button)
    @Volatile private var suppressUntil = 0L
    @Volatile private var suppressSince = 0L

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled") updateStrip()
    }

    private fun enabled() = prefs.getBoolean("enabled", false)

    override fun onServiceConnected() {
        instance = this
        prefs = getSharedPreferences("cc", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        updateStrip()
    }

    private fun updateStrip() {
        if (enabled()) addStrip() else removeStrip()
    }

    private fun addStrip() {
        if (strip != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (40 * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.y = 0

        val v = View(this)
        var startY = 0f
        var fired = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    fired = false
                    captureBackdrop() // ready by the time the swipe is finished
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!fired && e.rawY - startY > 15 * d) {
                        fired = true
                        suppressUntil = 0L // a real top swipe always shows OUR panel
                        showControlCenter()
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        strip = v
    }

    private fun removeStrip() {
        strip?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {}
        }
        strip = null
    }

    private fun showControlCenter() {
        val now = System.currentTimeMillis()
        if (now - lastShow < 400) return
        lastShow = now
        startForegroundService(
            Intent(this, OverlayService::class.java).setAction("SHOW")
        )
    }

    // ───────────── Blurred picture of the screen (for the frosted buttons) ─────────────
    // Takes a screenshot (needs canTakeScreenshot in the accessibility config), shrinks it to
    // about 1/8 size, blurs that tiny picture and hands it to the panel. The screenshot is
    // never saved anywhere: only the tiny blurred picture is kept, in memory, for a moment.
    // All the work happens on a background thread, so it can not make the UI stutter.

    private fun captureBackdrop() {
        if (Build.VERSION.SDK_INT < 30) return
        if (lowRam) return
        if (!prefs.getBoolean("blur", true)) return // switch for a "blur off" option later
        if (OverlayService.instance?.isPanelOpen() == true) return // would capture our own panel
        val now = SystemClock.uptimeMillis()
        if (now - lastCapture < 600) return // Android allows only a few screenshots per second
        lastCapture = now
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                worker,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val buffer = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val tiny = if (hw != null) makeBackdrop(hw) else null
                            buffer.close()
                            if (tiny != null) handler.post { publishBackdrop(tiny) }
                        } catch (e: Throwable) {
                            // no blur this time; the buttons just show the real screen
                        }
                    }

                    override fun onFailure(errorCode: Int) {}
                }
            )
        } catch (e: Throwable) {
        }
    }

    private fun publishBackdrop(b: OverlayService.Backdrop) {
        OverlayService.backdrop = b
        OverlayService.backdropAt = SystemClock.uptimeMillis()
        OverlayService.instance?.onNewBackdrop(b)
    }

    private fun makeBackdrop(hw: Bitmap): OverlayService.Backdrop? {
        val soft = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        hw.recycle()

        // Halve it again and again (smooth, no jagged edges) until it is about 1/8 size
        var cur = soft
        val targetW = max(1, soft.width / 8)
        while (cur.width / 2 >= targetW && cur.height / 2 >= 1) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (next !== cur) cur.recycle()
            cur = next
        }

        val w = cur.width
        val h = cur.height
        val a = IntArray(w * h)
        cur.getPixels(a, 0, w, 0, 0, w, h)
        cur.recycle()

        // Two rounds of box blur on a tiny picture: about 25 px of blur at full size
        val b = IntArray(w * h)
        repeat(2) {
            boxPass(a, b, w, h, 3, true)
            boxPass(b, a, w, h, 3, false)
        }

        // A little extra colour (the vibrant look), baked into the tiny picture once,
        // so the GPU does not have to do it on every frame
        saturate(a, 1.5f)
        val frost = Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)

        // The same picture with the light panel colour mixed over it, also once
        val tint = OverlayService.PANEL_TINT_RGB
        val ta = OverlayService.PANEL_TINT_ALPHA
        val tr = (tint shr 16) and 255
        val tg = (tint shr 8) and 255
        val tb = tint and 255
        val p = IntArray(w * h)
        for (i in p.indices) {
            val c = a[i]
            val r = (((c shr 16) and 255) * (1f - ta) + tr * ta).toInt()
            val g = (((c shr 8) and 255) * (1f - ta) + tg * ta).toInt()
            val bl = ((c and 255) * (1f - ta) + tb * ta).toInt()
            p[i] = (255 shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val panel = Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888)
        return OverlayService.Backdrop(frost, panel)
    }

    private fun saturate(px: IntArray, s: Float) {
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = (gray + (r - gray) * s).toInt().coerceIn(0, 255)
            val ng = (gray + (g - gray) * s).toInt().coerceIn(0, 255)
            val nb = (gray + (b - gray) * s).toInt().coerceIn(0, 255)
            px[i] = (255 shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val c = src[base + min(max(i, 0), len - 1) * step]
                sr += (c shr 16) and 255
                sg += (c shr 8) and 255
                sb += c and 255
            }
            for (i in 0 until len) {
                dst[base + i * step] =
                    (255 shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[base + max(i - r, 0) * step]
                val inn = src[base + min(i + r + 1, len - 1) * step]
                sr += ((inn shr 16) and 255) - ((out shr 16) and 255)
                sg += ((inn shr 8) and 255) - ((out shr 8) and 255)
                sb += (inn and 255) - (out and 255)
            }
        }
    }

    private fun dismissSystemPanel() {
        if (Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }, 150)
        }
    }

    // ───────────── Used by the buttons in the panel ─────────────

    fun openSystemQuickSettings() {
        val now = System.currentTimeMillis()
        suppressSince = now
        suppressUntil = now + 15000
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    fun openPowerMenu() {
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    // ───────────── Back key closes the panel (needs key-event filtering in the config) ─────────────

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val overlay = OverlayService.instance ?: return false
        if (!overlay.isPanelOpen()) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) overlay.handleBack()
            return true
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!enabled()) return
        val pkg = event.packageName?.toString() ?: return
        val name = event.className?.toString() ?: ""
        val fromSystemUi = pkg == "com.android.systemui"
        val now = System.currentTimeMillis()

        // 1) The user went to an app / launcher: stop suppressing, and close our panel
        //    if it is open (Home, Recents, switching apps).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != packageName
        ) {
            if (!fromSystemUi && now - suppressSince > 2000) suppressUntil = 0L

            val overlay = OverlayService.instance
            if (overlay != null && overlay.isPanelOpen() &&
                now - overlay.openedAtMs > 700 &&
                (!fromSystemUi || name.contains("Recents", true))
            ) {
                overlay.closeFromOutside()
                return
            }
        }

        // 2) Replace the system panel with ours
        if (!fromSystemUi) return
        if (now < suppressUntil) return

        val isIgnored = name.contains("Volume", true) ||
            name.contains("Toast", true) ||
            name.contains("Keyguard", true) ||
            name.contains("Navigation", true) ||
            name.contains("Recents", true)

        val isPanel = name.contains("Shade", true) ||
            name.contains("Panel", true) ||
            name.contains("QuickSettings", true) ||
            name.contains("Expanded", true) ||
            name.contains("QS", false) ||
            name.contains("Notification", true)

        if (isPanel && !isIgnored) {
            dismissSystemPanel()
            showControlCenter()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(listener)
        removeStrip()
        worker.shutdown()
        super.onDestroy()
    }
}

package com.zev3nz.flowcenter

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        // Lets TriggerService (accessibility) close the panel on Back / Home / Recents
        @Volatile var instance: OverlayService? = null

        // A tiny blurred picture of the screen, made by TriggerService just before the panel opens
        @Volatile var backdrop: Backdrop? = null
        @Volatile var backdropAt = 0L

        // What kind of animation an item gets (see applyProgress)
        val DARK_BTN = 0xAB000000.toInt() // the black of every dark button: black at 67 % opacity (see-through, so the blurred screen shows in it)

        // The light panel: with a blurred screenshot behind it, this tint is mixed over the blur
        // (a lower PANEL_TINT_ALPHA = more blur visible, a higher one = more white-grey)
        const val PANEL_TINT_RGB = 0xE8E8EC
        const val PANEL_TINT_ALPHA = 0.74f
        // Without a screenshot (old phone, secure screen, blur off) the panel is this flat colour
        val FLAT_TINT = 0xF2E8E8EC.toInt()
        // ... and the dark buttons sit on this instead of on the blur
        val HOLE_BASE = 0xFF4A4A50.toInt()
        // Colour of a round toggle that is switched ON (iOS blue), fully opaque
        val TOGGLE_ON = 0xFF007AFF.toInt()
        // Other ON colours a tile can use: makeTile(kind, desc, onColor)
        val TOGGLE_RED = 0xFFFF3B30.toInt()
        val TOGGLE_YELLOW = 0xFFFF9F0A.toInt() // the amber of the airplane button in iOS

        // Icons for the round toggle tiles (drawn in TileView)
        const val ICON_BOX = 0.58f // PNG icon size as a share of the tile (smaller number = smaller icon; the toggle button itself is not affected)
        // EXPERIMENT switches for the expand animation (set to false to go back)
        const val EXPAND_SCALE_X = true     // the black buttons also scale along X (around their own centre)
        const val EXPAND_ICON_SCALES = true // the PNG icon scales together with its button (X and Y)
        const val EXPAND_MIN_SCALE = 0.25f  // size a button starts at when the panel expands (0.25 = 25 %, 1 = no scaling)
        const val EXPAND_SHIFT_DP = 18     // while expanded, the whole panel (clock, buttons, favourites, rows...) sits this much lower
        const val EXPAND_FAV_DROP_DP = 16  // ...and the favourite row (with everything under it) goes this much lower still, leaving a gap under the clock / buttons
        // Fade-in of the buttons. Original quick fade: LEN 0.5 / MIX 0. Long fade: LEN 0.9 / MIX 1.
        const val EXPAND_FADE_LEN = 0.7f    // how much of the fade timeline the button fade-in uses (bigger = longer fade)
        const val EXPAND_FADE_MIX = 0.5f    // 0 = fade follows the fast movement curve, 1 = fade runs evenly over the whole animation
        const val LABEL_H_DP = 16   // height of the name label under a tile
        const val LABEL_GAP_DP = 6  // gap between the round tile and its name label
        const val T_FLASH = 0
        const val T_NET = 1
        const val T_BT = 2
        const val T_SETTINGS = 3
        const val T_ROTATE = 4
        const val T_DND = 5
        const val T_SOUND = 6
        const val T_AIRPLANE = 7
        const val T_LOCATION = 8
        const val T_DISPLAY = 9
        const val T_BATTERY = 10
        const val T_SCREENSHOT = 11

        // Customisable toggles: how many there are, where the user's layout is saved, and the
        // order a fresh install starts with.
        // The grid has GRID_ROWS rows: the favourite row (FAV_COLS cells, always visible) and
        // GRID_ROWS - 1 more rows of GRID_COLS cells that appear when the control center is expanded.
        // Every cell (a "slot") holds one toggle or nothing (EMPTY).
        const val TILE_COUNT = 12   // how many different toggles exist
        const val GRID_ROWS = 8
        const val FAV_COLS = 5
        const val GRID_COLS = 4
        const val SLOT_COUNT = FAV_COLS + (GRID_ROWS - 1) * GRID_COLS // 33 cells
        const val EMPTY = -1
        const val COVERED = -2 // a cell that lies under a bigger toggle (a slider): never shown, never a drop target. A covered cell stores COVERED - kind of the slider that covers it
        const val PREF_DISPLAY_MODE = "display_mode"
        const val PREF_SOUND_MODE = "sound_mode"
        val SOUND_BLUE = 0xFF007AFF.toInt() // the bell inside the Sound slider
        // The Display toggle is either a round button or a brightness slider (edit mode: drag its corner handle)
        const val MODE_CIRCLE = 0 // round toggle, 1 cell
        const val MODE_WIDE = 1   // slider, 2 cells wide x 1 high (2:1)
        const val MODE_TALL = 2   // slider, 1 cell wide x 2 high (1:2)
        const val SLIDER_RADIUS = 0.36f // corner radius of the slider, as a share of its short side
        val SLIDER_FILL = 0xF2FFFFFF.toInt() // the light "filled" part of the slider (like iOS)
        const val PREF_TILE_ORDER = "tile_order" // old format: just the order of the 12 toggles
        const val PREF_TILE_SLOTS = "tile_slots" // new format: SLOT_COUNT numbers, -1 = empty cell
        // What a NEW user sees (until the pencil / edit mode is used). Cells 0-4 = favourite row, then 4
        // per row: Settings, DND, Display slider (tall), Sound slider (tall) / Airplane, Battery saver
        // (under the two sliders) / Screenshot. EMPTY = nothing (the cells under a slider are added by placeBlock).
        val DEFAULT_SLOTS = IntArray(SLOT_COUNT) { EMPTY }.also {
            it[0] = T_NET; it[1] = T_BT; it[2] = T_ROTATE; it[3] = T_LOCATION; it[4] = T_FLASH
            it[5] = T_SETTINGS; it[6] = T_DND; it[7] = T_DISPLAY; it[8] = T_SOUND
            it[9] = T_AIRPLANE; it[10] = T_BATTERY
            it[13] = T_SCREENSHOT
        }
        val DEFAULT_TILE_ORDER = intArrayOf(
            T_FLASH, T_NET, T_BT, T_SETTINGS, T_AIRPLANE,
            T_ROTATE, T_DND, T_SOUND, T_LOCATION,
            T_DISPLAY, T_BATTERY, T_SCREENSHOT
        )

        const val K_TOP_TEXT = 0      // clock + date: slide + slow text fade, no scale
        const val K_TOP_BUTTON = 1    // the 3 round buttons at the top right: slide + fade, no scale
        const val K_SHAPE_TEXT = 2    // shape with its own text (round tiles, footer pills)
        const val K_SHAPE_CHILDREN = 3 // shape holding other views (notification cards)
        const val K_SHAPE_VIEW = 4    // pure shape (sliders, handle): the view itself scales
        const val K_TEXT = 5          // plain text: slide + slow fade, no scale
    }

    // Two tiny pictures made from one screenshot (both about 1/8 of the screen size):
    //  frost = the blurred screen, a bit more colourful (shows inside the dark buttons)
    //  panel = frost with the light panel tint already mixed in (the panel background)
    class Backdrop(val frost: Bitmap, val panel: Bitmap)

    var openedAtMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var confirmView: View? = null

    private lateinit var wm: WindowManager
    private lateinit var cam: CameraManager
    private var cameraId: String? = null
    private var torchOn = false
    private var trigger: View? = null
    private var panel: View? = null
    private var panelWm: WindowManager? = null
    private var swiping = false

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SHOW") showPanel()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        cam = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        startAsForeground()
        addTrigger()
    }

    override fun onDestroy() {
        closePanel(false)
        if (instance === this) instance = null
        trigger?.let { wm.removeView(it) }
        trigger = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "control_center"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Flow Center", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("Flow Center is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // The saved layout: SLOT_COUNT numbers (which toggle sits in which cell, EMPTY = nothing).
    // Anything missing, unknown or duplicated is repaired, so a bad or old value (for example
    // after an update adds a toggle) can never break the panel. A layout saved by the old
    // version (only the order of the 12 toggles) moves over to the first 12 cells, which is
    // exactly where it was shown before.
    private fun loadTileOrder(): IntArray {
        val prefs = getSharedPreferences("cc", Context.MODE_PRIVATE)
        val result = IntArray(SLOT_COUNT) { EMPTY }
        val placed = HashSet<Int>()

        val slots = prefs.getString(PREF_TILE_SLOTS, null)
        if (slots == null && prefs.getString(PREF_TILE_ORDER, null) == null) {
            // nothing saved yet (fresh install): the default arrangement
            for (i in 0 until SLOT_COUNT) {
                val k = DEFAULT_SLOTS[i]
                if (k >= 0 && placed.add(k)) result[i] = k
            }
        } else if (slots != null) {
            val parts = slots.split(",")
            for (i in 0 until min(parts.size, SLOT_COUNT)) {
                val k = parts[i].trim().toIntOrNull() ?: continue
                if (k in 0 until TILE_COUNT && placed.add(k)) result[i] = k
            }
        } else {
            var slot = 0
            prefs.getString(PREF_TILE_ORDER, null)?.split(",")?.forEach { part ->
                val k = part.trim().toIntOrNull()
                if (k != null && k in 0 until TILE_COUNT && slot < SLOT_COUNT && placed.add(k)) {
                    result[slot++] = k
                }
            }
        }
        // Toggles that are still missing take the first free cell
        for (k in DEFAULT_TILE_ORDER) {
            if (k in placed) continue
            val free = result.indexOf(EMPTY)
            if (free >= 0) {
                result[free] = k
                placed.add(k)
            }
        }
        return result
    }

    private fun saveTileOrder(order: IntArray) {
        getSharedPreferences("cc", Context.MODE_PRIVATE).edit()
            .putString(PREF_TILE_SLOTS, order.joinToString(",")).apply()
    }

    // The toggles that can become a slider (edit mode: drag the corner handle): Display and Sound
    private fun isBlockKind(k: Int) = k == T_DISPLAY || k == T_SOUND
    private fun modePref(kind: Int) = if (kind == T_SOUND) PREF_SOUND_MODE else PREF_DISPLAY_MODE

    // What a cell holds when it lies under the slider of toggle `kind`
    private fun coverMark(kind: Int) = COVERED - kind
    private fun isCovered(v: Int) = v <= COVERED

    private fun loadBlockMode(kind: Int): Int {
        val prefs = getSharedPreferences("cc", Context.MODE_PRIVATE)
        // fresh install: both Display and Sound start as tall sliders (the default arrangement)
        val fresh = !prefs.contains(PREF_TILE_SLOTS) && !prefs.contains(PREF_TILE_ORDER)
        val m = prefs.getInt(modePref(kind), if (fresh) MODE_TALL else MODE_CIRCLE)
        return if (m == MODE_WIDE || m == MODE_TALL) m else MODE_CIRCLE
    }

    private fun saveBlockMode(kind: Int, mode: Int) {
        getSharedPreferences("cc", Context.MODE_PRIVATE).edit().putInt(modePref(kind), mode).apply()
    }

    // ── Where a Display slider can sit. Only the 4-column rows under the favourites can hold one.
    private fun gridCol(slot: Int) = (slot - FAV_COLS) % GRID_COLS

    // The second cell of a slider whose top-left cell is `anchor` (-1 = it does not fit there)
    private fun coveredOf(anchor: Int, mode: Int): Int {
        if (anchor < FAV_COLS || anchor >= SLOT_COUNT) return -1
        return when (mode) {
            MODE_WIDE -> if (gridCol(anchor) + 1 < GRID_COLS) anchor + 1 else -1
            MODE_TALL -> if (anchor + GRID_COLS < SLOT_COUNT) anchor + GRID_COLS else -1
            else -> -1
        }
    }

    // The top-left cell of a slider of `mode` that includes `slot`. At the right / bottom edge it
    // grows to the left / upwards instead. -1 = not possible (for example in the favourite row).
    private fun anchorFor(slot: Int, mode: Int): Int {
        if (mode == MODE_CIRCLE) return slot
        if (slot < FAV_COLS) return -1
        if (coveredOf(slot, mode) >= 0) return slot
        val back = if (mode == MODE_WIDE) slot - 1 else slot - GRID_COLS
        return if (coveredOf(back, mode) >= 0) back else -1
    }

    // Puts the toggle `kind` (Display or Sound, as a slider of `mode`, or round) with its top-left
    // cell at `anchor` and returns the new layout. The cells it used before become free. A toggle
    // that was in the way moves into one of those freed cells, or else into the nearest empty cell.
    private fun placeBlock(order: IntArray, kind: Int, mode: Int, anchor: Int): IntArray {
        val out = order.copyOf()
        val oldCells = (0 until SLOT_COUNT).filter { out[it] == kind || out[it] == coverMark(kind) }
        val covered = coveredOf(anchor, mode)
        val newCells = if (covered >= 0) listOf(anchor, covered) else listOf(anchor)
        for (c in oldCells) out[c] = EMPTY

        val displaced = ArrayList<Int>() // toggles that must make room
        val homes = ArrayList<Int>()     // where they were
        for (c in newCells) {
            if (out[c] >= 0) {
                displaced.add(out[c])
                homes.add(c)
                out[c] = EMPTY
            }
        }
        val freed = oldCells.filter { it !in newCells }.toMutableList()
        for (i in displaced.indices) {
            var target = if (freed.isNotEmpty()) freed.removeAt(0) else -1
            if (target < 0) {
                var bestD = Int.MAX_VALUE
                for (sIdx in 0 until SLOT_COUNT) {
                    if (out[sIdx] != EMPTY || sIdx in newCells) continue
                    val d = abs(sIdx - homes[i])
                    if (d < bestD) {
                        bestD = d
                        target = sIdx
                    }
                }
            }
            if (target >= 0) out[target] = displaced[i]
        }
        out[anchor] = kind
        if (covered >= 0) out[covered] = coverMark(kind)
        return out
    }

    // Thin invisible strip at the top-right, just under the status bar.
    private fun addTrigger() {
        val lp = WindowManager.LayoutParams(
            resources.displayMetrics.widthPixels / 2,
            dp(20),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END

        val v = View(this)
        var startY = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> startY = e.rawY
                MotionEvent.ACTION_MOVE -> if (e.rawY - startY > dp(40)) showPanel()
            }
            true
        }
        wm.addView(v, lp)
        trigger = v
    }

    private fun showPanel() {
        if (panel != null) return

        val a11y = TriggerService.instance
        openedAtMs = System.currentTimeMillis()

        // Dimmed full-screen root. Handles Back and closes when it loses focus (Home/Recents).
        val root = PanelRoot(this)
        root.setOnClickListener { if (!root.editing) closePanel() }

        // A blurred picture of the screen taken a moment ago (when the swipe started)?
        val pic = backdrop
        if (pic != null && SystemClock.uptimeMillis() - backdropAt < 1500) {
            root.backdropPainter.setPicture(pic, false)
        }
        backdrop = null

        // ───────────── One scroll view holds BOTH parts ─────────────
        // Bottom edge fades out so the floating buttons never sit on top of an item.
        // Swiping up after reaching the bottom closes the panel.
        val scroll = PanelScroll(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.isVerticalFadingEdgeEnabled = true
        scroll.setFadingEdgeLength(dp(120))
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        root.scrollView = scroll // the root brings the scroll position up to date before it draws the backdrop
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(44), dp(12), 0)
        // Always at least screen tall so a tap on empty space closes the panel
        content.minimumHeight = resources.displayMetrics.heightPixels
        content.setOnClickListener { if (!root.editing) closePanel() }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))

        // ───────────── Top row: clock + date (left), small buttons (right) ─────────────
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.TOP
        topRow.setPadding(dp(16), 0, dp(16), 0) // side margin for clock, date and small buttons

        val timeCol = LinearLayout(this)
        timeCol.orientation = LinearLayout.VERTICAL

        val clock = TextClock(this)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.textSize = 34f
        clock.setTextColor(Color.BLACK)
        clock.typeface = Typeface.DEFAULT_BOLD
        clock.includeFontPadding = false
        timeCol.addView(clock)

        val dateText = TextClock(this)
        dateText.format12Hour = "EEE d MMM"
        dateText.format24Hour = "EEE d MMM"
        dateText.textSize = 11f
        dateText.setTextColor(Color.BLACK)
        dateText.setPadding(dp(2), dp(2), 0, 0)
        timeCol.addView(dateText)
        topRow.addView(timeCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        var editRequest: (() -> Unit)? = null // filled in further down, once the edit mode exists
        val buttons = listOf(
            makeIconButton(3, "Edit toggles") { editRequest?.invoke() },
            makeIconButton(0, "Turn off Flow Center") { showStopConfirm(root) },
            makeIconButton(1, "Show system control center") { openSystemControlCenter() },
            makeIconButton(2, "Show power menu") { openPowerMenu() }
        )
        buttons.forEach {
            val lp = LinearLayout.LayoutParams(dp(36), dp(36))
            lp.leftMargin = dp(8)
            btnRow.addView(it, lp)
        }
        topRow.addView(btnRow, LinearLayout.LayoutParams(WRAP, WRAP))
        val topLp = LinearLayout.LayoutParams(MATCH, WRAP)
        topLp.bottomMargin = dp(10)
        content.addView(topRow, topLp)

        // ───────────── PART 1: CONTROL CENTER (scrolls with the list) ─────────────
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.setPadding(dp(12), 0, dp(12), 0) // no background; side margin for the circles and sliders
        val cardLp = LinearLayout.LayoutParams(MATCH, WRAP)
        cardLp.bottomMargin = dp(12)
        content.addView(card, cardLp)

        // ───────────── Toggle definitions ─────────────
        // What each toggle is and what it does. The round tiles on screen are only SLOTS: which
        // toggle sits in which slot is decided by `order` (saved in the preferences), and the
        // pencil button lets the user change it. A slot never changes its views, only what it
        // shows, so every open / expand / close animation keeps working whatever the order is.
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifMgr = getSystemService(NotificationManager::class.java)

        class TileDef(
            val kind: Int,
            val desc: String,
            val label: String,
            val onColor: Int,
            val isOn: (() -> Boolean)?,
            val click: (TextView) -> Unit
        )
        val defs = HashMap<Int, TileDef>()
        fun def(
            kind: Int, desc: String, onColor: Int = TOGGLE_ON, label: String = desc,
            isOn: (() -> Boolean)? = null, click: (TextView) -> Unit
        ) {
            defs[kind] = TileDef(kind, desc, label, onColor, isOn, click)
        }

        def(T_FLASH, "Flashlight", TOGGLE_YELLOW, isOn = { torchOn }) { t ->
            val id = cameraId
            if (id == null) {
                toast("No flashlight found")
            } else {
                torchOn = !torchOn
                cam.setTorchMode(id, torchOn)
                setTileColor(t, torchOn)
            }
        }

        def(T_NET, "Internet") {
            closePanel()
            val action = if (Build.VERSION.SDK_INT >= 29)
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            openSettings(action)
        }

        def(T_BT, "Bluetooth") {
            closePanel()
            openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        }

        def(T_SETTINGS, "Settings") {
            closePanel()
            openSettings(Settings.ACTION_SETTINGS)
        }

        def(T_AIRPLANE, "Airplane mode", TOGGLE_YELLOW, "Airplane") {
            closePanel()
            openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        }

        fun rotationLocked() = try {
            Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 0
        } catch (e: Exception) { false }
        def(T_ROTATE, "Rotation lock", TOGGLE_RED, isOn = { rotationLocked() }) { t ->
            if (!Settings.System.canWrite(this)) {
                toast("Allow system settings permission in the app")
            } else {
                val lock = !rotationLocked()
                Settings.System.putInt(
                    contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (lock) 0 else 1
                )
                setTileColor(t, lock)
                root.backdropPainter.invalidate()
            }
        }

        fun dndOn() = notifMgr.currentInterruptionFilter in
            NotificationManager.INTERRUPTION_FILTER_PRIORITY..NotificationManager.INTERRUPTION_FILTER_ALARMS
        def(T_DND, "Do not disturb", isOn = { dndOn() }) { t ->
            if (!notifMgr.isNotificationPolicyAccessGranted) {
                closePanel()
                openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            } else {
                val turnOn = !dndOn()
                notifMgr.setInterruptionFilter(
                    if (turnOn) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                setTileColor(t, turnOn)
                root.backdropPainter.invalidate()
            }
        }

        // Sound toggle: Sound (blue) -> Mute (dark) -> Vibrate (yellow) -> Sound ...
        fun ringerState(): Int = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> 0 // sound
            AudioManager.RINGER_MODE_SILENT -> 1 // mute
            else -> 2                            // vibrate
        }
        fun soundLabel() = when (ringerState()) { 0 -> "Sound"; 1 -> "Mute"; else -> "Vibrate" }
        // Colour + bell icon for the current state
        fun soundLook(t: TextView) {
            val tv = t as? TileView ?: return
            val st = ringerState()
            tv.muted = st == 1
            tv.solidOn = st != 1
            tv.onColor = if (st == 2) TOGGLE_YELLOW else TOGGLE_ON
            setTileColor(t, st != 1)
        }
        var relabel: () -> Unit = {} // refreshes the name label of the Sound toggle (filled in once the cells exist)
        def(T_SOUND, "Sound", isOn = { ringerState() != 1 }) { t ->
            val next = when (ringerState()) {
                0 -> AudioManager.RINGER_MODE_SILENT
                1 -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            try {
                audio.ringerMode = next
                soundLook(t)
                relabel()
                root.backdropPainter.invalidate()
            } catch (e: SecurityException) {
                if (!notifMgr.isNotificationPolicyAccessGranted) {
                    toast("Allow Do Not Disturb access to use Mute")
                    closePanel()
                    openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                } else {
                    toast("Can't change sound mode right now")
                }
            }
        }

        def(T_LOCATION, "Location") {
            closePanel()
            openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }

        def(T_DISPLAY, "Display") {
            closePanel()
            openSettings(Settings.ACTION_DISPLAY_SETTINGS)
        }

        def(T_BATTERY, "Battery saver") {
            closePanel()
            openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }

        def(T_SCREENSHOT, "Screenshot") { takeScreenshot() }

        // The cells, in the saved layout: cells 0-4 = favourite row, then 4 per row. A cell that
        // holds no toggle still gets a (hidden) tile, so a toggle can be dragged into it.
        var order = loadTileOrder()
        // The shape of the two toggles that can be sliders (Display, Sound): round / wide / tall
        val blockMode = IntArray(TILE_COUNT) { MODE_CIRCLE }
        fun modeOf(k: Int) = if (k >= 0 && isBlockKind(k)) blockMode[k] else MODE_CIRCLE
        // Would the slider `kind` (top-left cell `anchor`) run into another slider?
        fun overlapsOtherBlock(kind: Int, anchor: Int, mode: Int): Boolean {
            val cov = coveredOf(anchor, mode)
            for (c in listOf(anchor, cov)) {
                if (c < 0) continue
                val v = order[c]
                if (v == kind || v == coverMark(kind)) continue
                if (isCovered(v)) return true
                if (v >= 0 && modeOf(v) != MODE_CIRCLE) return true
            }
            return false
        }
        // a saved slider that no longer fits (or has no such toggle) goes back to a round toggle
        for (k in intArrayOf(T_DISPLAY, T_SOUND)) {
            blockMode[k] = loadBlockMode(k)
            val savedSlot = order.indexOf(k)
            val savedAnchor =
                if (blockMode[k] != MODE_CIRCLE && savedSlot >= 0) anchorFor(savedSlot, blockMode[k]) else -1
            if (savedAnchor < 0 || overlapsOtherBlock(k, savedAnchor, blockMode[k])) {
                blockMode[k] = MODE_CIRCLE
            } else {
                order = placeBlock(order, k, blockMode[k], savedAnchor)
            }
        }
        val slotTiles: Array<TextView> = Array(SLOT_COUNT) { slot ->
            val d = defs.getValue(if (order[slot] >= 0) order[slot] else T_FLASH)
            makeTile(d.kind, d.desc, d.onColor, d.label)
        }

        // Favourite slots: 5 per row. Their name labels only appear when the panel is expanded.
        val favRow = makeTileRow(FAV_COLS, true, *slotTiles.sliceArray(0 until FAV_COLS))
        // Empty space above the favourites: 0 high normally, grows while expanding
        val favGap = Space(this)
        card.addView(favGap, LinearLayout.LayoutParams(MATCH, 0))
        card.addView(favRow.row)

        // Extra toggle rows: hidden until expanded, sit between this row and Brightness
        val topHost = FrameLayout(this)
        topHost.visibility = View.GONE
        card.addView(topHost, LinearLayout.LayoutParams(MATCH, WRAP))
        val topArea = LinearLayout(this)
        topArea.orientation = LinearLayout.VERTICAL
        topHost.addView(topArea, FrameLayout.LayoutParams(MATCH, WRAP))

        // The Display toggle (when it is a slider) shows the same brightness as the slider below;
        // the Sound toggle (when it is a slider) shows the media volume
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        var brightLevel = 0.5f
        var volLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat()
        fun refreshLevels() {
            for (t in slotTiles) (t as? TileView)?.let { it.level = if (it.kind == T_SOUND) volLevel else brightLevel }
        }
        fun setSliderLevels(level: Float) {
            brightLevel = level
            refreshLevels()
        }
        fun applyBrightness(p: Int): Boolean {
            if (!Settings.System.canWrite(this)) return false
            Settings.System.putInt(
                contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, p.coerceIn(1, 255))
            return true
        }

        // Brightness slider
        // (label + slider sit in brightHost: it is only there while the panel is NOT expanded)
        val brightHost = LinearLayout(this)
        brightHost.orientation = LinearLayout.VERTICAL
        card.addView(brightHost, LinearLayout.LayoutParams(MATCH, WRAP))
        val brightLabel = makeLabel("Brightness")
        brightHost.addView(brightLabel)
        val bright = SeekBar(this)
        bright.max = 255
        bright.progress = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { 128 }
        bright.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (!applyBrightness(p)) {
                    toast("Allow system settings permission in the app")
                    return
                }
                setSliderLevels(p.coerceAtLeast(1) / 255f)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        brightHost.addView(bright)
        setSliderLevels(bright.progress / 255f)

        // Notification list + floating footer: declared here because expanding hides them
        val notifList = LinearLayout(this)
        notifList.orientation = LinearLayout.VERTICAL
        val footer = FrameLayout(this)

        // ───────────── Expandable area (hidden until expanded) ─────────────
        // Swipe down on the panel (or tap the handle) to expand, swipe up on the card to collapse.
        // expandedHost is the part whose HEIGHT animates; expandedArea inside it always keeps
        // its full size, so its rows are never squashed while the card grows.
        val expandedHost = FrameLayout(this)
        expandedHost.visibility = View.GONE
        card.addView(expandedHost, LinearLayout.LayoutParams(MATCH, WRAP))
        val expandedArea = LinearLayout(this)
        expandedArea.orientation = LinearLayout.VERTICAL
        expandedHost.addView(expandedArea, FrameLayout.LayoutParams(MATCH, WRAP))

        // The two expandable parts: the extra toggle rows (top) and the volume slider (below Brightness)
        class ExpandPart(val host: FrameLayout, val area: LinearLayout) {
            var full = 0
            var tops = IntArray(0)
        }
        val parts = listOf(ExpandPart(topHost, topArea), ExpandPart(expandedHost, expandedArea))

        // The round tiles inside the expandable area. Each one is animated on its own
        // (the rows holding them are left alone), so the black button, the PNG icon and the
        // name label can each behave differently.
        class ExpTile(val tile: TextView, val label: TextView, val bg: YScaleDrawable, val rowIdx: Int) {
            var tileTop = 0f  // where the tile belongs, measured from the top of the expandable area
            var labelTop = 0f
        }
        val expTiles = mutableListOf<ExpTile>()
        val tileRowViews = mutableListOf<View>()

        var isExpanded = false
        var expandE = 0f // 0 = collapsed, 1 = expanded
        var expandFadeE = 0f // same idea, but on its own (slower) curve: only used for fading
        var expandAnim: ValueAnimator? = null

        // Two numbers (0..1) drive everything, like the opening animation:
        //   e  = movement: the layout unfolds like an accordion from the top; fast, then settles
        //   fe = fading: a calmer curve, so the fade-in lasts almost the whole animation
        // Every round tile behaves like a notification card in the opening animation:
        //   black button -> scales (EXPAND_MIN_SCALE -> 1, X and Y) + slides + slow fade in
        //   PNG icon     -> slides + scales with its button (EXPAND_ICON_SCALES) + slow fade in
        //   name label   -> slides only + fades in
        // The notifications fade out.
        //
        // Why the buttons never overlap: a tile's position AND its size both use the same
        // number g (EXPAND_MIN_SCALE -> 1). The distance between two rows is always bigger than
        // one tile, so rows that are squeezed by g can't touch each other.
        var brightFull = 0 // full height of the brightness label + slider
        fun applyExpand(e: Float, fe: Float) {
            expandE = e

            // The brightness slider fades away and its space closes while the panel expands
            // (and comes back when it collapses)
            val bOut = smooth(ramp(e, 0f, 0.6f))
            bright.alpha = 1f - bOut
            brightLabel.alpha = 1f - smooth(ramp(e, 0f, 0.35f))
            val bh = (brightFull * (1f - e)).toInt()
            val blp = brightHost.layoutParams
            if (blp.height != bh) {
                blp.height = bh
                brightHost.layoutParams = blp
            }
            val g = EXPAND_MIN_SCALE + (1f - EXPAND_MIN_SCALE) * e

            // Name labels under the always-visible (favourite) tiles: their slot grows, then the text fades in
            val labelFade = smooth(ramp(fe, 0.3f, 1f))
            val slotH = (dp(LABEL_H_DP) * e).toInt()
            for (c in favRow.cells) {
                val slp = c.slot.layoutParams
                if (slp.height != slotH) {
                    slp.height = slotH
                    c.slot.layoutParams = slp
                }
                c.label.alpha = labelFade
            }

            // 1. The whole panel moves down a little (clock, date, top buttons, favourites, rows).
            content.translationY = dp(EXPAND_SHIFT_DP) * e
            // 2. The favourites (and everything under them) move down a little further:
            //    real extra space above them, so nothing can overlap.
            val gapH = (dp(EXPAND_FAV_DROP_DP) * e).toInt()
            val glp = favGap.layoutParams
            if (glp.height != gapH) {
                glp.height = gapH
                favGap.layoutParams = glp
            }

            for (part in parts) {
                for (i in 0 until part.area.childCount) {
                    val c = part.area.getChildAt(i)
                    if (c in tileRowViews) continue // its tiles are animated below
                    val d = min(i, 4) * 0.06f
                    val shapeFade = ramp(fe, d, d + EXPAND_FADE_LEN)
                    val textFade = smooth(ramp(fe, 0.3f + d, 1f))
                    val top = if (i < part.tops.size) part.tops[i] else 0
                    c.translationY = -(1f - e) * top
                    if (c is TextView && c.background == null) {
                        c.alpha = textFade
                    } else {
                        c.pivotY = 0f
                        c.scaleY = e
                        c.alpha = shapeFade
                    }
                }
            }

            for (t in expTiles) {
                val d = min(t.rowIdx, 4) * 0.06f
                val shapeFade = ramp(fe, d, d + EXPAND_FADE_LEN)
                val textFade = smooth(ramp(fe, 0.28f + d, 1f))
                t.tile.translationY = -(1f - g) * t.tileTop // slide (moves the button AND the icon)
                t.bg.set(g, shapeFade, if (EXPAND_SCALE_X) g else 1f) // the black button scales (Y, and X around its centre)
                setTextFade(t.tile, textFade)                // the PNG icon follows this alpha: fade in
                                                             // (it scales with the button when EXPAND_ICON_SCALES is on)
                t.label.translationY = -(1f - g) * t.labelTop
                t.label.alpha = textFade
            }

            val fadeOut = 1f - ramp(e, 0f, 0.5f)
            notifList.alpha = fadeOut
            footer.alpha = fadeOut
            root.backdropPainter.invalidate() // the footer buttons fade out, so their holes must too
        }

        fun resetExpandRows(expanded: Boolean) {
            // brightness slider: only there while collapsed
            bright.alpha = 1f
            brightLabel.alpha = 1f
            val blp = brightHost.layoutParams
            blp.height = WRAP
            brightHost.layoutParams = blp
            brightHost.visibility = if (expanded) View.GONE else View.VISIBLE
            for (part in parts) {
                for (i in 0 until part.area.childCount) {
                    val c = part.area.getChildAt(i)
                    c.translationY = 0f
                    c.scaleY = 1f
                    c.alpha = 1f
                }
            }
            for (t in expTiles) {
                t.tile.translationY = 0f
                t.bg.set(1f, 1f)
                setTextFade(t.tile, 1f)
                t.label.translationY = 0f
                t.label.alpha = 1f
            }
            val slotH = if (expanded) dp(LABEL_H_DP) else 0
            for (c in favRow.cells) {
                val slp = c.slot.layoutParams
                slp.height = slotH
                c.slot.layoutParams = slp
                c.label.alpha = if (expanded) 1f else 0f
            }
            // the shifts stay while expanded, and are gone when collapsed
            content.translationY = if (expanded) dp(EXPAND_SHIFT_DP).toFloat() else 0f
            val glp = favGap.layoutParams
            glp.height = if (expanded) dp(EXPAND_FAV_DROP_DP) else 0
            favGap.layoutParams = glp
            notifList.alpha = 1f
            footer.alpha = 1f
        }

        // The tiles slide out of their parents' edges while animating, so the expandable
        // area (host, rows, cells) must not clip. Otherwise the tile is cut off but the frosted
        // patch behind it (painted by the backdrop painter) would not be.
        fun unclipTree(v: View) {
            if (v is ViewGroup) {
                v.clipChildren = false
                v.clipToPadding = false
                for (i in 0 until v.childCount) unclipTree(v.getChildAt(i))
            }
        }

        // Edit mode needs the control center expanded first. startEdit() sets pendingEdit, and
        // onExpandSettled (filled in below) begins the edit as soon as the expand animation is done.
        var pendingEdit = false
        var onExpandSettled: (() -> Unit)? = null

        // Expanded = control center takes the whole screen: notifications and the
        // Manage / Clear all buttons fade away. Collapsing brings them back.
        fun setExpanded(target: Boolean) {
            if (target == isExpanded) return
            isExpanded = target
            if (!target) pendingEdit = false
            expandAnim?.cancel()
            val w = card.width - card.paddingLeft - card.paddingRight
            if (w <= 0) { // not laid out yet: no animation
                for (part in parts) {
                    part.host.visibility = if (target) View.VISIBLE else View.GONE
                    val lp = part.host.layoutParams
                    lp.height = WRAP
                    part.host.layoutParams = lp
                }
                notifList.visibility = if (target) View.GONE else View.VISIBLE
                footer.visibility = if (target) View.GONE else View.VISIBLE
                expandE = if (target) 1f else 0f
                expandFadeE = expandE
                resetExpandRows(target)
                if (target) onExpandSettled?.invoke()
                return
            }

            for (part in parts) unclipTree(part.host)

            // Measure the rows at full size and remember where each one belongs
            for (part in parts) {
                val areaLp = part.area.layoutParams
                areaLp.height = WRAP
                part.area.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                part.full = part.area.measuredHeight
                part.area.layout(0, 0, w, part.full)
                part.tops = IntArray(part.area.childCount) { part.area.getChildAt(it).top }
                areaLp.height = part.full // keep the rows at full size while the host grows
                part.area.layoutParams = areaLp
            }
            for (t in expTiles) {
                t.tileTop = topIn(t.tile, topArea)
                t.labelTop = topIn(t.label, topArea)
            }
            brightHost.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            brightFull = brightHost.measuredHeight

            if (target) {
                scroll.smoothScrollTo(0, 0)
                for (part in parts) {
                    if (part.host.visibility != View.VISIBLE) {
                        val lp = part.host.layoutParams
                        lp.height = 0
                        part.host.visibility = View.VISIBLE
                        part.host.layoutParams = lp
                    }
                }
            } else {
                notifList.visibility = View.VISIBLE
                footer.visibility = View.VISIBLE
                brightHost.visibility = View.VISIBLE // comes back while collapsing
            }

            val end = if (target) 1f else 0f
            // The animator itself runs evenly (0 -> 1 over time); movement and fade each get
            // their own curve from it. Starts from wherever a half-finished animation was.
            val e0 = expandE
            val f0 = expandFadeE
            val moveEase = PathInterpolator(0.2f, 0f, 0f, 1f) // fast, then settles (as before)
            // Expanding: an almost even fade, so it lasts the whole animation.
            // Collapsing: the fade just follows the movement, so nothing is left as a small dot.
            val evenEase = PathInterpolator(0.4f, 0f, 0.6f, 1f)
            val fadeEase = if (target) {
                TimeInterpolator { x ->
                    (1f - EXPAND_FADE_MIX) * moveEase.getInterpolation(x) +
                        EXPAND_FADE_MIX * evenEase.getInterpolation(x)
                }
            } else moveEase
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.duration = 420
            anim.interpolator = LinearInterpolator()
            anim.addUpdateListener {
                val t = it.animatedValue as Float
                val e = e0 + (end - e0) * moveEase.getInterpolation(t)
                val fe = f0 + (end - f0) * fadeEase.getInterpolation(t)
                expandFadeE = fe
                for (part in parts) {
                    val lp = part.host.layoutParams
                    lp.height = (part.full * e).toInt()
                    part.host.layoutParams = lp
                }
                applyExpand(e, fe)
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    expandE = end
                    expandFadeE = end
                    for (part in parts) {
                        val hostLp = part.host.layoutParams
                        val areaLp = part.area.layoutParams
                        hostLp.height = WRAP
                        areaLp.height = WRAP
                        part.host.layoutParams = hostLp
                        part.area.layoutParams = areaLp
                        if (!target) part.host.visibility = View.GONE
                    }
                    resetExpandRows(target)
                    if (target) {
                        notifList.visibility = View.GONE
                        footer.visibility = View.GONE
                        onExpandSettled?.invoke()
                    }
                }
            })
            expandAnim = anim
            anim.start()
        }

        // Expanded rows: 4 toggles per row. Together with the favourite row on top that makes
        // GRID_ROWS (8) rows in total. Cells that hold no toggle are simply empty.
        val gridRows = ArrayList<TileRowParts>()
        for (r in 0 until GRID_ROWS - 1) {
            val from = FAV_COLS + r * GRID_COLS
            val rp = makeTileRow(GRID_COLS, false, *slotTiles.sliceArray(from until from + GRID_COLS))
            gridRows.add(rp)
            topArea.addView(rp.row)
        }

        // The dark tiles are see-through: cut the light panel out behind each of them.
        // Their background is wrapped right away (YScaleDrawable) so ONLY the black button
        // scales while the panel expands, and the hole behind it scales with it.
        gridRows.forEachIndexed { rowIdx, rp ->
            tileRowViews.add(rp.row)
            rp.cells.forEach { c ->
                val bg = wrapScale(c.tile)
                (c.tile as? TileView)?.iconScales = EXPAND_ICON_SCALES
                root.backdropPainter.holes.add(Hole(c.tile, bg, true, 0f, false))
                expTiles.add(ExpTile(c.tile, c.label, bg, rowIdx))
            }
        }

        // ───────────── Customising the toggles (pencil button) ─────────────
        // Tap the pencil: the control center expands (if it is not already), thin corner brackets
        // show the grid around every cell, the clock and the small buttons step aside, and
        // Cancel / Done rise from the bottom. Drag a toggle to another cell (empty ones too):
        // the others slide out of the way. Done saves the new layout, Cancel slides everything back.
        val slotCells: List<TileCellParts> = favRow.cells + gridRows.flatMap { it.cells }

        // The grid brackets live in the foreground of every cell, so they stay put while toggles move
        val frames = slotCells.map { BracketFrame(resources.displayMetrics.density) }
        slotCells.forEachIndexed { i, c -> c.col.foreground = frames[i] }

        var morphNext = false // true only while a resize is applied: the Display toggle then glides to its new shape

        // The Display slider covers 2 cells: its first cell gets one big bracket, the second none
        fun refreshFrames() {
            for (i in 0 until SLOT_COUNT) {
                val f = frames[i]
                val md = modeOf(order[i])
                f.setSpan(
                    if (md == MODE_WIDE) 2f else 1f,
                    if (md == MODE_TALL) 2f else 1f,
                    morphNext
                )
                f.hidden = isCovered(order[i])
            }
        }
        refreshFrames()

        var savedOrder = order.copyOf()  // the layout when edit mode began (what Cancel goes back to)
        var savedModes = blockMode.copyOf() // ...and the shape of the Display / Sound toggles at that time
        // resizing the Display / Sound toggle (dragging its corner handle)
        var resizing = false
        var resizeKind = -1              // which toggle is being resized
        var resizeHome = -1              // the cell the resize started from (the slider's top-left cell)
        var resizeRefX = 0f              // where the finger was when the shape last changed (or when it went down)
        var resizeRefY = 0f
        var dragSlot = -1                // the slot of the toggle that is being dragged (or landing)
        val cx = FloatArray(SLOT_COUNT)  // centre of every slot
        val cy = FloatArray(SLOT_COUNT)
        var rootX = 0f
        var rootY = 0f
        var reflowAnim: ValueAnimator? = null
        var landingAnim: ValueAnimator? = null
        var ghost: TextView? = null
        var closed = false

        // drag state
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var gestureOwned = false // this finger went down on a toggle while arranging: it is ours
        var dragging = false
        var downSlot = -1
        var downRawX = 0f
        var downRawY = 0f
        var grabDx = 0f // finger -> toggle centre, so the toggle does not jump under the finger
        var grabDy = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var autoScrollDir = 0     // -1 = scrolling up, 1 = down (while a toggle is held near the screen edge)
        var autoScrolling = false

        // Where a slot's tile belongs (its centre, in the root's coordinates). The tile's own slide
        // is left out on purpose, so the answer is the same while things are moving.
        val tmpPt = FloatArray(2)
        fun measureCenters() {
            val rl = IntArray(2)
            root.getLocationOnScreen(rl)
            rootX = rl[0].toFloat()
            rootY = rl[1].toFloat()
            for (i in 0 until SLOT_COUNT) {
                val t = slotCells[i].tile
                // centre of the one-cell tile; the Display slider is bigger than that, but a
                // cell's centre must not depend on what is in it
                val cellV = t.parent as View
                val tlp = t.layoutParams as ViewGroup.MarginLayoutParams
                var x = (cellV.width - tlp.leftMargin - tlp.rightMargin) / 2f
                var y = (cellV.height - tlp.topMargin - tlp.bottomMargin) / 2f
                var cur: View = t
                while (true) {
                    if (cur !== t && !cur.matrix.isIdentity) { // parents: panel shift, row squash...
                        tmpPt[0] = x
                        tmpPt[1] = y
                        cur.matrix.mapPoints(tmpPt)
                        x = tmpPt[0]
                        y = tmpPt[1]
                    }
                    x += cur.left
                    y += cur.top
                    val parent = cur.parent as? View ?: break
                    if (parent === root) break
                    x -= parent.scrollX
                    y -= parent.scrollY
                    cur = parent
                }
                cx[i] = x
                cy[i] = y
            }
        }

        // Centre of what a slot's toggle covers: the cell itself, or the middle of the Display slider
        fun blockCx(slot: Int): Float {
            val c = coveredOf(slot, modeOf(order[slot]))
            return if (c >= 0) (cx[slot] + cx[c]) / 2f else cx[slot]
        }
        fun blockCy(slot: Int): Float {
            val c = coveredOf(slot, modeOf(order[slot]))
            return if (c >= 0) (cy[slot] + cy[c]) / 2f else cy[slot]
        }

        // Which toggle is under this point (root coordinates)? -1 = none. Empty cells never count.
        fun slotAt(x: Float, y: Float): Int {
            for (i in 0 until SLOT_COUNT) {
                if (order[i] < 0) continue
                val t = slotCells[i].tile
                if (modeOf(order[i]) != MODE_CIRCLE) {
                    // the slider: its whole rectangle (both cells)
                    if (abs(x - blockCx(i)) <= t.width / 2f + dp(4) &&
                        abs(y - blockCy(i)) <= t.height / 2f + dp(4)
                    ) return i
                    continue
                }
                val halfW = t.width / 2f + dp(4)
                val halfH = t.height / 2f
                if (abs(x - cx[i]) <= halfW &&
                    y >= cy[i] - halfH - dp(4) &&
                    y <= cy[i] + halfH + dp(LABEL_H_DP + 6)
                ) return i
            }
            return -1
        }

        // Fades the button of a slot (the button, its frosted patch and its icon). 0 = gone, 1 = normal.
        fun setTileFade(slot: Int, f: Float) {
            val cell = slotCells[slot]
            (cell.tile.background as? YScaleDrawable)?.set(1f, f, 1f)
            setTextFade(cell.tile, f)
            root.backdropPainter.invalidate()
        }

        // Same, and the name label too
        fun setSlotFade(slot: Int, f: Float) {
            setTileFade(slot, f)
            slotCells[slot].label.alpha = f
        }

        // Makes a slot show the toggle that `order` says belongs there (or nothing, if it is empty or
        // hidden under the Display slider)
        fun bindSlot(slot: Int) {
            val cell = slotCells[slot]
            val tv = cell.tile as TileView
            val kind = order[slot]
            tv.canResize = slot >= FAV_COLS // the resize handle only exists in the expanded grid
            if (kind < 0) {
                tv.animateMorph = false
                tv.sliderMode = MODE_CIRCLE
                tv.visibility = View.INVISIBLE
                cell.label.visibility = View.INVISIBLE
                tv.setOnClickListener(null)
                tv.isClickable = false
                tv.contentDescription = null
                tv.tag = false
                cell.label.text = ""
                return
            }
            val d = defs.getValue(kind)
            tv.animateMorph = morphNext && isBlockKind(kind)
            tv.sliderMode = modeOf(kind)
            tv.animateMorph = false
            tv.level = if (kind == T_SOUND) volLevel else brightLevel
            tv.visibility = View.VISIBLE
            cell.label.visibility = if (tv.sliderMode == MODE_CIRCLE) View.VISIBLE else View.INVISIBLE // a slider has no name label
            tv.rebind(d.kind, d.onColor, d.label)
            tv.contentDescription = d.desc
            tv.setOnClickListener { d.click(tv) }
            if (kind == T_SOUND) {
                cell.label.text = soundLabel() // shows the current state
                soundLook(tv)
            } else {
                cell.label.text = d.label
                setTileColor(tv, d.isOn?.invoke() ?: false)
            }
        }
        for (i in 0 until SLOT_COUNT) bindSlot(i)
        relabel = {
            for (i in 0 until SLOT_COUNT) if (order[i] == T_SOUND) slotCells[i].label.text = soundLabel()
        }

        // Shows a new layout (and, optionally, a new shape of the Display toggle). Every toggle that
        // changes slot starts where it visibly was and glides to its new place. If it is called
        // again while things are still moving, everything simply carries on from where it is now.
        fun applyOrder(newOrder: IntArray, animate: Boolean, newModes: IntArray = blockMode) {
            measureCenters()
            val old = order
            val oldModes = blockMode.copyOf()
            val tx = FloatArray(SLOT_COUNT) { slotCells[it].tile.translationX }
            val ty = FloatArray(SLOT_COUNT) { slotCells[it].tile.translationY }
            val fx = FloatArray(SLOT_COUNT)
            val fy = FloatArray(SLOT_COUNT)
            for (i in 0 until SLOT_COUNT) {
                if (i == dragSlot) continue // the dragged toggle is hidden; its slot is just a gap
                if (newOrder[i] < 0) continue
                if (newOrder[i] == old[i]) {
                    fx[i] = tx[i]
                    fy[i] = ty[i]
                } else {
                    val a = old.indexOf(newOrder[i])
                    if (a >= 0) {
                        fx[i] = cx[a] + tx[a] - cx[i]
                        fy[i] = cy[a] + ty[a] - cy[i]
                    }
                }
            }
            order = newOrder
            for (k in 0 until TILE_COUNT) blockMode[k] = newModes[k]
            refreshFrames()
            for (i in 0 until SLOT_COUNT) {
                val nk = newOrder[i]
                val shapeChanged = nk >= 0 && oldModes[nk] != blockMode[nk]
                if (newOrder[i] != old[i] || shapeChanged) {
                    bindSlot(i)
                    if (i != dragSlot && newOrder[i] >= 0) setSlotFade(i, 1f)
                }
            }

            reflowAnim?.cancel()
            fun place(k: Float) { // k = 1: at the start offsets, k = 0: home
                for (i in 0 until SLOT_COUNT) {
                    val c = slotCells[i]
                    c.tile.translationX = fx[i] * k
                    c.tile.translationY = fy[i] * k
                    c.label.translationX = fx[i] * k
                    c.label.translationY = fy[i] * k
                }
                root.backdropPainter.invalidate()
            }
            place(if (animate) 1f else 0f)
            if (!animate) return
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 280
            a.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            a.addUpdateListener { place(1f - (it.animatedValue as Float)) }
            reflowAnim = a
            a.start()
        }

        // The Display toggle becomes a round button / a wide slider / a tall slider. Whatever sat in
        // the cells the slider now needs moves out of the way (see placeBlock).
        fun resizeTo(newMode: Int) {
            val k = resizeKind
            if (k < 0 || !isBlockKind(k) || newMode == modeOf(k) || resizeHome < 0) return
            val anchor = anchorFor(resizeHome, newMode)
            if (anchor < 0 || overlapsOtherBlock(k, anchor, newMode)) return // no room (or another slider is there)
            val newModes = blockMode.copyOf()
            newModes[k] = newMode
            morphNext = true
            applyOrder(placeBlock(order, k, newMode, anchor), true, newModes)
            morphNext = false
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        // ── Cancel / Done (same look as Manage / Clear all, they take their place while arranging)
        val cancelPill = makePill("Cancel")
        val cancelBg = YScaleDrawable(cancelPill.background, false)
        cancelPill.background = cancelBg
        val donePill = makePill("Done", TOGGLE_ON)
        root.backdropPainter.holes.add(Hole(cancelPill, cancelBg, false, dp(22).toFloat(), false))
        val editFooter = FrameLayout(this)
        editFooter.visibility = View.INVISIBLE
        editFooter.addView(cancelPill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
        editFooter.addView(donePill, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        val editFooterLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        editFooterLp.setMargins(dp(24), 0, dp(24), dp(24))
        root.addView(editFooter, editFooterLp)

        val riseEase = PathInterpolator(0.2f, 0f, 0f, 1f)

        // ── Everything that changes when edit mode starts / ends is driven by ONE number:
        //    v = 0: normal panel, v = 1: arranging.
        //      clock + small buttons: fade out (v up)       Cancel / Done: rise + fade in
        //      grid brackets: fade in
        // The frosted patch behind Cancel is painted by the root, which does NOT redraw by itself
        // when only a child's position / alpha changes. That is why every frame ends with an
        // invalidate: the patch now moves and fades together with the button.
        var editUi = 0f
        var editUiAnim: ValueAnimator? = null
        val footerRise = dp(28).toFloat()
        fun applyEditUi(v: Float) {
            editUi = v
            topRow.alpha = 1f - v
            editFooter.alpha = v
            editFooter.translationY = (1f - v) * footerRise
            for (f in frames) f.amount = v
            for (c in slotCells) (c.tile as? TileView)?.handleAmount = v // the Display toggle's resize handle
            root.backdropPainter.invalidate()
        }
        applyEditUi(0f)

        fun animateEditUi(target: Float, duration: Long) {
            editUiAnim?.cancel()
            applyEditUi(editUi)
            if (target < 1f) topRow.visibility = View.VISIBLE   // visible while it fades back in
            if (target > 0f) editFooter.visibility = View.VISIBLE
            val a = ValueAnimator.ofFloat(editUi, target)
            a.duration = duration
            a.interpolator = riseEase
            a.addUpdateListener { applyEditUi(it.animatedValue as Float) }
            a.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    // fully hidden: take them out of the touch system too
                    if (target >= 1f) topRow.visibility = View.INVISIBLE
                    if (target <= 0f) editFooter.visibility = View.INVISIBLE
                }
            })
            editUiAnim = a
            a.start()
        }

        // ── dragging

        // The toggle is picked up: a floating copy follows the finger (scaled up, with a shadow);
        // its slot becomes an empty gap. The copy looks exactly like the real button (same frosted
        // glass), so picking up and putting down never shows a change of look.
        fun startDrag() {
            val slot = downSlot
            val tile = slotCells[slot].tile as TileView
            val d = defs.getValue(order[slot])
            val on = tile.tag == true
            unclipTree(card) // the others slide across rows; nothing may cut them off
            measureCenters()

            val big = tile.sliderMode != MODE_CIRCLE // the Display slider is lifted as one piece
            val g = makeTile(d.kind, d.desc, tile.onColor, d.label) as TileView
            g.muted = tile.muted // the Sound toggle's bell
            if (big) {
                g.autoSpan = false // its size is set by hand below
                g.sliderMode = tile.sliderMode
                g.level = tile.level
            }
            g.tag = on
            g.background = when {
                big -> GhostGlassDrawable(g, root, root.backdropPainter.frostBitmap(), SLIDER_RADIUS)
                on -> GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tile.onColor)
                }
                else -> GhostGlassDrawable(g, root, root.backdropPainter.frostBitmap())
            }
            root.addView(g, FrameLayout.LayoutParams(tile.width, tile.height))
            g.translationX = blockCx(slot) - tile.width / 2f
            g.translationY = blockCy(slot) - tile.height / 2f
            ghost = g
            grabDx = blockCx(slot) - (downRawX - rootX)
            grabDy = blockCy(slot) - (downRawY - rootY)

            dragSlot = slot
            dragging = true
            val c = slotCells[slot]
            c.tile.translationX = 0f
            c.tile.translationY = 0f
            c.label.translationX = 0f
            c.label.translationY = 0f
            setTileFade(slot, 0f) // the copy sits exactly on top of it: nothing visibly changes

            g.animate()
                .scaleX(1.15f).scaleY(1.15f)
                .translationZ(dp(14).toFloat())
                .setDuration(180)
                .setInterpolator(riseEase)
                .setUpdateListener {
                    g.invalidate() // the frosted glass is painted for the current size / place
                    if (dragSlot == slot) c.label.alpha = 1f - it.animatedFraction
                }
                .start()
            g.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        // The finger moved over another cell: the dragged toggle's gap moves there.
        //  - an empty cell: only the dragged toggle moves, its old cell becomes empty
        //  - a cell with a toggle: the toggles in between shift by one place
        fun moveDrag(target: Int) {
            val prev = dragSlot
            val k = order[prev]
            val newOrder: IntArray
            if (modeOf(k) != MODE_CIRCLE) {
                // the slider travels as one piece (both of its cells)
                if (coveredOf(target, modeOf(k)) < 0 || overlapsOtherBlock(k, target, modeOf(k))) return
                newOrder = placeBlock(order, k, modeOf(k), target)
            } else {
                // a normal toggle: the cells under a slider stay where they are, the other toggles flow around them
                val free = (0 until SLOT_COUNT).filter {
                    !isCovered(order[it]) && modeOf(order[it]) == MODE_CIRCLE
                }
                val pi = free.indexOf(prev)
                val ti = free.indexOf(target)
                if (pi < 0 || ti < 0) return
                val list = free.map { order[it] }.toMutableList()
                if (list[ti] == EMPTY) {
                    list[pi] = EMPTY
                    list[ti] = k
                } else {
                    list.removeAt(pi)
                    list.add(ti, k)
                }
                val res = order.copyOf()
                for (j in free.indices) res[free[j]] = list[j]
                newOrder = res
            }
            dragSlot = target
            applyOrder(newOrder, true)
            setSlotFade(prev, 1f)   // this slot now shows another toggle (or nothing), which glides in
            setSlotFade(target, 0f) // the gap
        }

        // Which cell is the held toggle over? (x, y = centre of the held toggle, root coordinates)
        fun retarget(x: Float, y: Float) {
            var best = -1
            var bestD = Float.MAX_VALUE
            val dragKind = if (dragSlot >= 0) order[dragSlot] else -1
            val blockDrag = dragKind >= 0 && modeOf(dragKind) != MODE_CIRCLE
            for (i in 0 until SLOT_COUNT) {
                var px = cx[i]
                var py = cy[i]
                if (blockDrag) {
                    // candidates: every cell the slider fits at (compared by the middle of the slider)
                    val c = coveredOf(i, modeOf(dragKind))
                    if (c < 0 || overlapsOtherBlock(dragKind, i, modeOf(dragKind))) continue
                    px = (cx[i] + cx[c]) / 2f
                    py = (cy[i] + cy[c]) / 2f
                } else if (isCovered(order[i]) || modeOf(order[i]) != MODE_CIRCLE) {
                    continue // the sliders' cells stay put; other toggles flow around them
                }
                val dx = x - px
                val dy = y - py
                val dd = dx * dx + dy * dy
                if (dd < bestD) {
                    bestD = dd
                    best = i
                }
            }
            val reach = dp(72).toFloat() // further away than this (e.g. over a slider): stay put
            if (best >= 0 && bestD <= reach * reach && best != dragSlot) moveDrag(best)
        }

        // With 8 rows the grid is taller than the screen: holding a toggle near the top / bottom
        // edge scrolls the panel, the cells under the finger change as it scrolls.
        val autoScrollTick = object : Runnable {
            override fun run() {
                if (!dragging || autoScrollDir == 0 || closed) {
                    autoScrolling = false
                    return
                }
                val before = scroll.scrollY
                scroll.scrollBy(0, autoScrollDir * dp(9))
                if (scroll.scrollY != before) {
                    measureCenters() // the cells moved with the content
                    retarget(lastRawX - rootX + grabDx, lastRawY - rootY + grabDy)
                }
                root.postOnAnimation(this)
            }
        }

        fun updateDrag(rawX: Float, rawY: Float) {
            val g = ghost ?: return
            lastRawX = rawX
            lastRawY = rawY
            val x = rawX - rootX + grabDx
            val y = rawY - rootY + grabDy
            g.translationX = x - g.width / 2f
            g.translationY = y - g.height / 2f
            g.invalidate()

            retarget(x, y)

            val fingerY = rawY - rootY
            autoScrollDir = when {
                fingerY > root.height - dp(110) -> 1
                fingerY < dp(110) -> -1
                else -> 0
            }
            if (autoScrollDir != 0 && !autoScrolling) {
                autoScrolling = true
                root.postOnAnimation(autoScrollTick)
            }
        }

        // The finger lifted: the floating copy settles into the gap and hands over to the real tile.
        // The copy is the same picture as the real button, so the hand-over is an instant swap
        // (no cross-fade): only the shadow and the size have already settled by then.
        fun endDrag() {
            val g = ghost
            val slot = dragSlot
            dragging = false
            autoScrollDir = 0
            if (g == null || slot < 0) return
            g.animate().cancel()
            measureCenters() // the panel may have scrolled while holding
            val tile = slotCells[slot].tile
            val label = slotCells[slot].label
            val fromX = g.translationX
            val fromY = g.translationY
            val fromS = g.scaleX
            val fromZ = g.translationZ
            val toX = blockCx(slot) - g.width / 2f
            val toY = blockCy(slot) - g.height / 2f
            val toS = tile.width.toFloat() / g.width

            var finished = false
            fun finish() {
                if (finished) return
                finished = true
                if (!closed) {
                    setSlotFade(slot, 1f)
                    try { root.removeView(g) } catch (e: Exception) {}
                }
                if (dragSlot == slot) dragSlot = -1
                if (ghost === g) ghost = null
            }
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 240
            a.interpolator = riseEase
            a.addUpdateListener {
                val t = it.animatedValue as Float
                g.translationX = fromX + (toX - fromX) * t
                g.translationY = fromY + (toY - fromY) * t
                g.scaleX = fromS + (toS - fromS) * t
                g.scaleY = g.scaleX
                g.translationZ = fromZ * (1f - t)
                label.alpha = ramp(t, 0.35f, 1f) // the name label fades back in while it lands
                g.invalidate()
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finish() }
            })
            landingAnim = a
            a.start()
        }

        // A toggle still in the air (Back pressed while dragging): put it down at once
        fun dropNow() {
            autoScrollDir = 0
            landingAnim?.end()
            val g = ghost
            if (g != null) {
                g.animate().cancel()
                try { root.removeView(g) } catch (e: Exception) {}
                ghost = null
            }
            if (dragSlot >= 0) {
                setSlotFade(dragSlot, 1f)
                dragSlot = -1
            }
            dragging = false
        }

        // ── The Display slider in normal use: touching it sets the screen brightness.
        // Like the arranging below, it takes the whole touch (so the panel never scrolls / collapses
        // while you slide).
        var sliderGesture: TileView? = null
        var sliderToasted = false

        fun sliderTileAt(rawX: Float, rawY: Float): TileView? {
            if (root.editing || root.leaving || !isExpanded || expandAnim?.isRunning == true) return null
            val loc = IntArray(2)
            for (i in FAV_COLS until SLOT_COUNT) {
                if (modeOf(order[i]) == MODE_CIRCLE) continue
                val t = slotCells[i].tile as TileView
                if (!t.isShown) return null
                t.getLocationOnScreen(loc)
                if (rawX >= loc[0] && rawX <= loc[0] + t.width && rawY >= loc[1] && rawY <= loc[1] + t.height) return t
            }
            return null
        }

        fun updateSlider(t: TileView, e: MotionEvent) {
            val loc = IntArray(2)
            t.getLocationOnScreen(loc)
            val l = if (t.sliderMode == MODE_WIDE) (e.rawX - loc[0]) / t.width
                    else 1f - (e.rawY - loc[1]) / t.height
            if (t.kind == T_SOUND) {
                // the Sound slider sets the media volume
                val v = Math.round(l.coerceIn(0f, 1f) * maxVol).coerceIn(0, maxVol)
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    volLevel = v / maxVol.toFloat()
                    refreshLevels()
                } catch (e: SecurityException) {
                    if (!sliderToasted) {
                        sliderToasted = true
                        toast("Can't change the volume right now")
                    }
                }
                return
            }
            val v = (l.coerceIn(0f, 1f) * 255f).toInt().coerceIn(1, 255)
            if (applyBrightness(v)) {
                setSliderLevels(v / 255f)
                bright.progress = v
            } else if (!sliderToasted) {
                sliderToasted = true
                toast("Allow system settings permission in the app")
            }
        }

        fun handleSliderTouch(e: MotionEvent): Boolean {
            val action = e.actionMasked
            val t = sliderGesture
            if (t == null) {
                if (action != MotionEvent.ACTION_DOWN) return false
                val hit = sliderTileAt(e.rawX, e.rawY) ?: return false
                sliderGesture = hit
                sliderToasted = false
                updateSlider(hit, e)
                return true
            }
            when (action) {
                MotionEvent.ACTION_MOVE -> updateSlider(t, e)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sliderGesture = null
            }
            return true
        }

        // ── Edit mode: the resize handle in the corner of the Display toggle.
        // Which toggle's handle is under this point (root coordinates)? -1 = none
        fun handleSlotAt(x: Float, y: Float): Int {
            for (i in FAV_COLS until SLOT_COUNT) {
                if (order[i] < 0 || !isBlockKind(order[i])) continue
                val t = slotCells[i].tile as TileView
                if (!t.isShown || !t.canResize) continue
                val hx = blockCx(i) - t.width / 2f + t.handleX()
                val hy = blockCy(i) - t.height / 2f + t.handleY()
                if (hypot(x - hx, y - hy) <= dp(28)) return i
            }
            return -1
        }

        // The finger is dragging the handle. What counts is how far it has moved since the shape
        // last changed (or since it went down), so the handle never jumps under the finger:
        //   round -> drag right = wide, drag down = tall
        //   wide  -> drag left = round, drag down = tall
        //   tall  -> drag up = round, drag right = wide
        // Against the right / bottom edge there is little room, so a shorter drag is enough there
        // (the slider then grows to the left / upwards, see anchorFor).
        fun updateResize(rawX: Float, rawY: Float) {
            val dx = rawX - resizeRefX
            val dy = rawY - resizeRefY
            val far = dp(24).toFloat()
            val farX = if (coveredOf(resizeHome, MODE_WIDE) >= 0) far else dp(14).toFloat()
            val farY = if (coveredOf(resizeHome, MODE_TALL) >= 0) far else dp(14).toFloat()
            val curMode = modeOf(resizeKind)
            val m = when (curMode) {
                MODE_CIRCLE -> when {
                    dx > farX && dx >= dy -> MODE_WIDE
                    dy > farY && dy > dx -> MODE_TALL
                    else -> MODE_CIRCLE
                }
                MODE_WIDE -> when {
                    dy > farY && dy > abs(dx) -> MODE_TALL
                    dx < -far -> MODE_CIRCLE
                    else -> MODE_WIDE
                }
                else -> when {
                    dx > farX && dx > abs(dy) -> MODE_WIDE
                    dy < -far -> MODE_CIRCLE
                    else -> MODE_TALL
                }
            }
            if (m != curMode) {
                resizeTo(m)
                resizeRefX = rawX
                resizeRefY = rawY
            }
        }

        // Every touch comes through here first. While arranging, a finger that goes down on a toggle
        // belongs to us (taps do nothing, so a toggle is never switched by accident); everything else
        // (sliders, scrolling, the Cancel / Done buttons) behaves as usual.
        fun handleEditTouch(e: MotionEvent): Boolean {
            val action = e.actionMasked
            if (!gestureOwned) {
                if (!root.editing || action != MotionEvent.ACTION_DOWN) return false
                landingAnim?.end() // a toggle that is still settling: finish it first
                measureCenters()
                val hs = handleSlotAt(e.rawX - rootX, e.rawY - rootY)
                if (hs >= 0) { // the resize handle of the Display / Sound toggle
                    gestureOwned = true
                    dragging = false
                    resizing = true
                    resizeKind = order[hs]
                    resizeHome = hs
                    resizeRefX = e.rawX
                    resizeRefY = e.rawY
                    return true
                }
                val slot = slotAt(e.rawX - rootX, e.rawY - rootY)
                if (slot < 0) return false
                gestureOwned = true
                dragging = false
                downSlot = slot
                downRawX = e.rawX
                downRawY = e.rawY
                return true
            }
            if (resizing) {
                when (action) {
                    MotionEvent.ACTION_MOVE -> if (root.editing) updateResize(e.rawX, e.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resizing = false
                        gestureOwned = false
                    }
                }
                return true
            }
            when (action) {
                MotionEvent.ACTION_MOVE -> if (root.editing) {
                    if (!dragging && hypot(e.rawX - downRawX, e.rawY - downRawY) > touchSlop) startDrag()
                    if (dragging) updateDrag(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) endDrag()
                    gestureOwned = false
                    dragging = false
                }
            }
            return true
        }
        scroll.editTouch = { e -> handleSliderTouch(e) || handleEditTouch(e) }

        // ── begin / end
        fun beginEdit() {
            if (root.editing || root.leaving) return
            root.editing = true
            savedOrder = order.copyOf()
            savedModes = blockMode.copyOf()
            scroll.blockGestures = true
            unclipTree(card)
            animateEditUi(1f, 320)
        }

        fun finishEdit(save: Boolean) {
            if (!root.editing) return
            dropNow()
            resizing = false
            if (save) {
                saveTileOrder(order)
                saveBlockMode(T_DISPLAY, blockMode[T_DISPLAY])
                saveBlockMode(T_SOUND, blockMode[T_SOUND])
            } else if (!order.contentEquals(savedOrder) || !blockMode.contentEquals(savedModes)) {
                morphNext = true // the sliders glide back to their old shape
                applyOrder(savedOrder.copyOf(), true, savedModes.copyOf())
                morphNext = false
            }
            root.editing = false
            scroll.blockGestures = false
            animateEditUi(0f, 240)
        }

        cancelPill.setOnClickListener { finishEdit(false) }
        donePill.setOnClickListener { finishEdit(true) }
        root.cancelEdit = { finishEdit(false) }

        // The pencil: expand first if needed, then begin
        onExpandSettled = {
            if (pendingEdit) {
                pendingEdit = false
                beginEdit()
            }
        }
        editRequest = {
            if (!root.editing && !pendingEdit) {
                if (isExpanded && expandAnim?.isRunning != true) {
                    beginEdit()
                } else {
                    pendingEdit = true
                    setExpanded(true)
                }
            }
        }

        // Panel closing (any way): stop everything that floats
        root.onClose = {
            closed = true
            resizing = false
            autoScrollDir = 0
            editUiAnim?.cancel()
            reflowAnim?.cancel()
            landingAnim?.cancel()
            if (root.editing) editFooter.animate().alpha(0f).setDuration(150).start()
        }

        // Small handle at the bottom of the card: tap to expand / collapse
        val handleBox = FrameLayout(this)
        handleBox.setPadding(0, dp(12), 0, 0)
        val bar = View(this)
        bar.background = GradientDrawable().apply {
            setColor(0xFF7A7A7A.toInt())
            cornerRadius = dp(2).toFloat()
        }
        handleBox.addView(bar, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER_HORIZONTAL))
        handleBox.setOnClickListener { if (!root.editing) setExpanded(!isExpanded) }
        card.addView(handleBox, LinearLayout.LayoutParams(MATCH, dp(28)))

        // ───────────── PART 2: NOTIFICATIONS ─────────────
        content.addView(notifList, LinearLayout.LayoutParams(MATCH, WRAP))

        // Empty space at the end so the last item can scroll above the floating buttons
        content.addView(Space(this), LinearLayout.LayoutParams(MATCH, dp(100)))

        // The list is never rebuilt while a finger is down (or a card is animating),
        // otherwise a notification that updates itself would cancel your swipe.
        var touching = false
        var pending = false
        var shownSig = "init"

        fun refresh() {
            val listener = NotificationListener.instance
            val items: List<StatusBarNotification>? = if (listener == null) null else {
                try {
                    listener.activeNotifications
                        .filter { isShowable(it) }
                        .sortedByDescending { it.postTime }
                } catch (e: Exception) { emptyList() }
            }
            val sig = if (items == null) "noaccess"
            else items.joinToString(",") { it.key + "@" + it.postTime }
            if (sig == shownSig) return
            shownSig = sig

            notifList.removeAllViews()
            if (items == null) {
                val msg = makeMessage("Tap to allow notification access")
                msg.setOnClickListener {
                    closePanel()
                    openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                notifList.addView(msg, LinearLayout.LayoutParams(MATCH, WRAP))
            } else if (items.isEmpty()) {
                notifList.addView(makeMessage("No notifications"), LinearLayout.LayoutParams(MATCH, WRAP))
            } else {
                items.forEach { sbn ->
                    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                    lp.bottomMargin = dp(4)
                    notifList.addView(makeNotifCard(sbn) {
                        if (pending && !touching && !swiping) {
                            pending = false
                            refresh()
                        }
                    }, lp)
                }
            }
        }

        fun flush() {
            if (pending && !touching && !swiping) {
                pending = false
                refresh()
            }
        }

        scroll.onTouching = { t ->
            touching = t
            if (!t) scroll.post { flush() }
        }
        scroll.onPullUp = {
            if (isExpanded) setExpanded(false) else closePanel()
        }
        scroll.onExpandSwipe = { expand ->
            if (expand) {
                setExpanded(true)
                true
            } else if (isExpanded) {
                setExpanded(false)
                true
            } else {
                false
            }
        }
        scroll.isOverCard = { x, y ->
            val loc = IntArray(2)
            card.getLocationOnScreen(loc)
            x >= loc[0] && x <= loc[0] + card.width && y >= loc[1] && y <= loc[1] + card.height
        }

        // ───────────── Floating footer: always visible ─────────────
        val manage = makePill("Manage")
        manage.setOnClickListener {
            closePanel()
            openSettings("android.settings.NOTIFICATION_SETTINGS")
        }
        val clearAll = makePill("Clear all")
        clearAll.setOnClickListener {
            NotificationListener.instance?.cancelAllNotifications()
        }
        footer.addView(manage, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START))
        footer.addView(clearAll, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        val footerLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        footerLp.setMargins(dp(24), 0, dp(24), dp(24))
        root.addView(footer, footerLp)

        refresh()
        // Live updates while the panel is open
        NotificationListener.onChange = {
            if (touching || swiping) {
                pending = true
            } else {
                refresh()
            }
        }

        // ───────────── Add the panel window ─────────────
        val type = if (a11y != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // Focusable (so it receives Back and notices Home/Recents), but it does not touch the keyboard.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        if (Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val manager = if (a11y != null)
            a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        else wm
        // Pixel-style open animation: ONE eased value (0 -> 1) drives everything, see applyProgress().
        // Register everything that animates (see applyProgress for what each kind does)
        registerItem(root, timeCol, K_TOP_TEXT)
        buttons.forEach { registerItem(root, it, K_TOP_BUTTON) }
        favRow.cells.forEach { registerItem(root, it.tile, K_SHAPE_TEXT) }
        registerItem(root, brightLabel, K_TEXT)
        registerItem(root, bright, K_SHAPE_VIEW)
        registerItem(root, handleBox, K_SHAPE_VIEW)
        root.notifList = notifList
        root.expandedArea = expandedArea
        root.topArea = topArea
        root.topHost = topHost
        root.expandedHost = expandedHost
        root.favRows = listOf(favRow)
        registerNotifItems(root)
        registerItem(root, manage, K_SHAPE_TEXT, footer = true)
        registerItem(root, clearAll, K_SHAPE_TEXT, footer = true)
        applyProgress(root, 0f)
        manager.addView(root, lp)
        panelWm = manager
        panel = root
        startOpenAnimation(root)
    }

    // The animation must not start until the window has really been drawn on screen,
    // otherwise its first (fastest) part plays while the window is still being created.
    // Now: wait for the first draw, measure where every item belongs, wait ONE frame, then go.
    private fun startOpenAnimation(r: PanelRoot) {
        var started = false

        fun go() {
            if (started || panel !== r) return
            started = true
            animateProgress(r, 1f, 560, PathInterpolator(0.2f, 0f, 0f, 1f)) { restoreClipping(r) }
        }

        r.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                r.viewTreeObserver.removeOnPreDrawListener(this)

                // Items move outside their parents' edges while animating, so stop clipping
                unclip(r, r)

                // Remember each item's final position (its top edge, from the top of the screen)
                (r.items + r.footerItems).forEach {
                    it.view.translationY = 0f
                    it.view.scaleY = 1f
                }
                r.items.forEach { measureY(r, it) }
                applyProgress(r, r.progress)

                Choreographer.getInstance().postFrameCallback { go() }
                return true
            }
        })
        handler.postDelayed({ go() }, 400) // safety net
    }

    // Android cuts a view off at its parent's edge in two ways: clipChildren, and
    // clipToPadding (any parent with padding, like the top row and the control center
    // card, also cuts at its padding). Both are switched off while the panel opens,
    // otherwise items that start above their parent get sliced by a straight line.
    private fun unclip(v: View, r: PanelRoot) {
        if (v is ViewGroup) {
            r.clipState.add(Triple(v, v.clipChildren, v.clipToPadding))
            v.clipChildren = false
            v.clipToPadding = false
            for (i in 0 until v.childCount) unclip(v.getChildAt(i), r)
        }
    }

    private fun restoreClipping(r: PanelRoot) {
        r.clipState.forEach { (g, children, padding) ->
            g.clipChildren = children
            g.clipToPadding = padding
        }
        r.clipState.clear()
    }

    private fun measureY(r: PanelRoot, item: AnimItem) {
        val rect = Rect(0, 0, item.view.width, item.view.height)
        r.offsetDescendantRectToMyCoords(item.view, rect)
        item.y = rect.top.toFloat()
    }

    // Adds one view to the animation. Shapes get their background wrapped so ONLY the
    // background shape is scaled; text and other content are never stretched.
    private fun registerItem(
        r: PanelRoot, v: View, kind: Int, footer: Boolean = false, notif: Boolean = false
    ): AnimItem {
        var bg: YScaleDrawable? = null
        if (kind == K_SHAPE_TEXT || kind == K_SHAPE_CHILDREN) {
            val b = v.background
            if (b is YScaleDrawable) {
                bg = b
            } else if (b != null) {
                bg = YScaleDrawable(b, footer)
                v.background = bg
            }
        }
        if (kind == K_SHAPE_VIEW) v.pivotY = 0f
        // The dark buttons are see-through, so the light panel is cut out behind each of them.
        // That way they sit on the real screen (like iOS) instead of on a light layer.
        val hasHole = r.backdropPainter.holes.any { it.view === v } // expanded tiles got theirs when they were built
        when {
            hasHole -> {}
            kind == K_TOP_BUTTON ->
                r.backdropPainter.holes.add(Hole(v, null, true, 0f, false))
            kind == K_SHAPE_TEXT && !footer ->
                r.backdropPainter.holes.add(Hole(v, bg, true, 0f, false))
            kind == K_SHAPE_TEXT && footer ->
                r.backdropPainter.holes.add(Hole(v, bg, false, dp(22).toFloat(), true))
        }
        val item = AnimItem(v, kind, bg, notif)
        if (footer) r.footerItems.add(item) else r.items.add(item)
        return item
    }

    // (Re)registers the notification cards that are in the list right now
    private fun registerNotifItems(r: PanelRoot) {
        val list = r.notifList ?: return
        r.items.removeAll { it.notif }
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            val kind = if (v is TextView && v.background == null) K_TEXT else K_SHAPE_CHILDREN
            registerItem(r, v, kind, notif = true)
        }
    }

    // When the control center is expanded, the extra rows (volume etc.) are visible too
    private fun registerExpandedItems(r: PanelRoot) {
        for ((areaView, hostView) in listOf(r.topArea to r.topHost, r.expandedArea to r.expandedHost)) {
            val area = areaView as? ViewGroup ?: continue
            val host = hostView ?: continue
            if (host.visibility != View.VISIBLE || host.height == 0) continue
            for (i in 0 until area.childCount) {
                val v = area.getChildAt(i)
                val rowParts = v.tag as? TileRowParts
                if (rowParts != null) {
                    // round tiles: the black button scales, the icon only slides + fades, the label slides + fades
                    rowParts.cells.forEach { c ->
                        registerItem(r, c.tile, K_SHAPE_TEXT)
                        registerItem(r, c.label, K_TEXT)
                    }
                } else {
                    val kind = if (v is TextView && v.background == null) K_TEXT else K_SHAPE_VIEW
                    registerItem(r, v, kind)
                }
            }
        }
        // The name labels under the favourite tiles are only visible while expanded
        r.favRows.forEach { fr ->
            fr.cells.forEach { c -> if (c.slot.height > 0) registerItem(r, c.label, K_TEXT) }
        }
    }

    // Called when the blurred picture arrives after the panel has already started opening
    fun onNewBackdrop(b: Backdrop) {
        val r = panel as? PanelRoot ?: return
        if (!r.backdropPainter.hasPicture()) {
            backdrop = null
            r.backdropPainter.setPicture(b, true)
        }
    }

    private fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    private fun setTextFade(v: View, a: Float) {
        val tv = v as? TextView ?: return
        val alpha = (a.coerceIn(0f, 1f) * 255).toInt()
        tv.setTextColor((tv.currentTextColor and 0x00FFFFFF) or (alpha shl 24))
    }

    private fun ramp(p: Float, from: Float, to: Float): Float =
        ((p - from) / (to - from)).coerceIn(0f, 1f)

    // p = 0: panel hidden, p = 1: fully open.
    //
    // Position and size use ONE shared number (grow), so the layout behaves like a single
    // picture that is squeezed together at the top and then stretched to its real size.
    // That is why items never overlap, however far along the animation is:
    //   grow      = 0.45 -> 1   (size of every shape AND spacing between items)
    //   position  = each item's real position * grow, minus an extra slide that fades out
    //
    // Fading is separate: shapes fade in quicker (top items first), text fades in slowly
    // afterwards, so text never piles up while the shapes are still close together.
    private fun applyProgress(r: PanelRoot, p: Float) {
        val squeeze = 0.55f          // how tightly packed everything starts
        val slide = r.slide // extra distance the whole group slides down (longer when opening)
        val grow = 1f - squeeze * (1f - p)
        val tileGrow = 1f - squeeze * 0.7f * (1f - p) // round tiles squash a bit less

        r.backdropPainter.alpha = ramp(p, 0f, 0.6f)

        r.items.forEachIndexed { i, item ->
            val v = item.view
            val d = min(i, 9) * 0.03f
            val shapeFade = ramp(p, d, d + 0.45f)
            val textFade = smooth(ramp(p, 0.25f + d, 1f))

            v.translationY = -(1f - p) * (item.y.coerceAtLeast(0f) * squeeze + slide)

            when (item.kind) {
                K_TOP_TEXT, K_TEXT -> v.alpha = textFade
                K_TOP_BUTTON -> v.alpha = shapeFade
                K_SHAPE_VIEW -> {
                    v.scaleY = grow
                    v.alpha = shapeFade
                }
                K_SHAPE_TEXT -> {
                    item.bg?.set(tileGrow, shapeFade)
                    setTextFade(v, textFade)
                }
                K_SHAPE_CHILDREN -> {
                    item.bg?.set(grow, shapeFade)
                    val g = v as? ViewGroup
                    if (g != null) {
                        for (j in 0 until g.childCount) g.getChildAt(j).alpha = textFade
                    }
                }
                else -> {}
            }
        }

        // Manage / Clear all rise from the bottom
        r.backdropPainter.invalidate() // the holes follow the buttons
        val f = ramp(p, 0.3f, 0.9f)
        val tf = smooth(ramp(p, 0.4f, 1f))
        r.footerItems.forEach {
            it.view.translationY = (1f - f) * dp(36)
            it.bg?.set(0.5f + 0.5f * f, f)
            setTextFade(it.view, tf)
        }
    }

    // Animates the panel's progress
    private fun animateProgress(
        r: PanelRoot,
        to: Float,
        duration: Long,
        interpolator: TimeInterpolator,
        onEnd: (() -> Unit)? = null
    ) {
        r.progressAnim?.cancel()

        val anim = ValueAnimator.ofFloat(r.progress, to)
        anim.duration = duration
        anim.interpolator = interpolator
        anim.addUpdateListener {
            r.progress = it.animatedValue as Float
            applyProgress(r, r.progress)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onEnd?.invoke()
            }
        })
        r.progressAnim = anim
        anim.start()
    }

    // ───────────── Closing: Back, Home/Recents, pull up ─────────────

    fun isPanelOpen(): Boolean = panel != null

    // Back: first closes the confirm dialog if it is showing, otherwise the panel
    fun handleBack() {
        val c = confirmView
        if (c != null) {
            (c.parent as? ViewGroup)?.removeView(c)
            confirmView = null
        } else {
            val r = panel as? PanelRoot
            if (r != null && r.editing) r.cancelEdit?.invoke() else closePanel()
        }
    }

    fun closeFromOutside() = closePanel()

    // Closes with a quick reverse animation (fade out + controls slide back up).
    // animate = false removes the window immediately (used when the service stops).
    private fun closePanel(animate: Boolean = true) {
        val v = panel ?: return
        val mgr = panelWm ?: wm
        panel = null
        panelWm = null
        NotificationListener.onChange = null
        swiping = false
        confirmView = null

        fun remove() {
            try { mgr.removeView(v) } catch (e: Exception) {}
        }

        val r = v as? PanelRoot
        if (!animate || r == null) {
            remove()
            return
        }

        r.leaving = true // swallow touches while it closes
        r.onClose?.invoke() // edit mode: drop a dragged toggle, fade out Cancel / Done
        if (r.progress >= 0.999f) r.slide = dp(50).toFloat() // the closing animation keeps its original short slide

        // The panel may have changed since it opened (scrolled, expanded, new notifications),
        // so collect everything that is on screen right now and measure it again.
        // Clipping is switched off again too, otherwise items are sliced while they slide up.
        unclip(r, r)
        val before = r.items.toSet()
        registerNotifItems(r)
        registerExpandedItems(r)
        val fresh = r.items.filter { it !in before }
        (if (r.progress >= 0.999f) r.items else fresh).forEach { measureY(r, it) }
        // emphasized accelerate: starts gently, leaves quickly
        animateProgress(r, 0f, 280, PathInterpolator(0.3f, 0f, 0.8f, 0.15f)) { remove() }
        handler.postDelayed({ remove() }, 600) // safety net
    }

    // One tile cell = the round tile + the name label under it (the label sits in a "slot"
    // whose height can grow from 0, used by the favourite row when the panel expands)
    private class TileCellParts(val tile: TextView, val slot: FrameLayout, val label: TextView, val col: View)
    private class TileRowParts(val row: LinearLayout, val cells: List<TileCellParts>)

    // Distance from the top of `anc` to the top of `v` (both laid out, no animation offsets)
    private fun topIn(v: View, anc: View): Float {
        var y = 0f
        var c: View = v
        while (c !== anc) {
            y += c.top
            c = c.parent as? View ?: break
        }
        return y
    }

    // Wraps a tile's background so that only the black button can be scaled along Y
    private fun wrapScale(t: TextView): YScaleDrawable {
        val b = t.background
        if (b is YScaleDrawable) return b
        val w = YScaleDrawable(b, false)
        t.background = w
        return w
    }

    private class AnimItem(
        val view: View,
        val kind: Int,
        val bg: YScaleDrawable?,
        val notif: Boolean
    ) {
        var y = 0f
    }

    // Draws a background shape scaled along Y (from its top edge, or bottom edge for
    // the footer buttons) and faded, without touching anything drawn on top of it.
    private class YScaleDrawable(
        private var inner: Drawable,
        private val pivotBottom: Boolean
    ) : Drawable() {
        private var sy = 1f
        private var sx = 1f // optional sideways scale, around the shape's own centre
        private var fade = 1f
        val scaleNow: Float get() = sy
        val scaleXNow: Float get() = sx
        val fadeNow: Float get() = fade

        fun setInner(d: Drawable) {
            inner = d
            d.bounds = bounds
            invalidateSelf()
        }

        fun set(scaleY: Float, alpha: Float, scaleX: Float = 1f) {
            if (scaleY != sy || alpha != fade || scaleX != sx) {
                sy = scaleY
                sx = scaleX
                fade = alpha
                invalidateSelf()
            }
        }

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            inner.bounds = bounds
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || fade <= 0f) return
            inner.alpha = (fade * 255).toInt()
            val py = if (pivotBottom) b.bottom.toFloat() else b.top.toFloat()
            canvas.save()
            canvas.scale(sx, sy, b.exactCenterX(), py)
            inner.draw(canvas)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // The flat finish of every dark button: ONE colour (DARK_BTN), nothing else.
    private class GlassDrawable(
        private val oval: Boolean,
        private val radius: Float,
        private val radiusFrac: Float = 0f, // > 0: corner radius = this x the short side (the Display slider)
        private val liveFrac: (() -> Float)? = null, // the Display / Sound toggle: roundness that changes while it morphs
        private val liveSolid: (() -> Int)? = null   // the Sound toggle: its ON colour (alpha 0 = none)
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private var mul = 1f

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            box.set(b)
            paint.color = DARK_BTN
            paint.alpha = (Color.alpha(DARK_BTN) * mul).toInt()
            val live = liveFrac?.invoke()
            if (live != null) {
                val round = abs(box.width() - box.height()) < 1.5f && live >= 0.499f
                val rad = live * min(box.width(), box.height())
                if (round) c.drawOval(box, paint) else c.drawRoundRect(box, rad, rad, paint)
                val solid = liveSolid?.invoke() ?: 0
                if (Color.alpha(solid) > 0) {
                    paint.color = solid
                    paint.alpha = (Color.alpha(solid) * mul).toInt()
                    if (round) c.drawOval(box, paint) else c.drawRoundRect(box, rad, rad, paint)
                }
            } else if (oval) {
                c.drawOval(box, paint)
            } else {
                val rad = if (radiusFrac > 0f) radiusFrac * min(box.width(), box.height())
                          else min(radius, min(box.width(), box.height()) / 2f)
                c.drawRoundRect(box, rad, rad, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            mul = alpha / 255f
            invalidateSelf()
        }
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // Thin rounded corner brackets (like a camera viewfinder) drawn around a grid cell while the
    // toggles are being arranged. amount: 0 = invisible, 1 = fully shown (it settles inwards).
    private class BracketFrame(private val density: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = density * 1.1f // thin
            color = Color.WHITE
        }
        private val path = Path()

        var amount = 0f
            set(value) {
                if (value != field) {
                    field = value
                    invalidateSelf()
                }
            }
        // The Display slider covers 2 cells: its first cell draws ONE bracket around both
        var spanX = 1f
            private set
        var spanY = 1f
            private set
        private var spanAnim: ValueAnimator? = null
        // Sets how many cells the bracket spans; with animate = true it grows / shrinks smoothly
        fun setSpan(x: Float, y: Float, animate: Boolean) {
            if (x == spanX && y == spanY && spanAnim == null) return
            spanAnim?.cancel()
            spanAnim = null
            if (!animate) {
                spanX = x
                spanY = y
                invalidateSelf()
                return
            }
            val x0 = spanX
            val y0 = spanY
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 380
            a.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            a.addUpdateListener {
                val k = it.animatedValue as Float
                spanX = x0 + (x - x0) * k
                spanY = y0 + (y - y0) * k
                invalidateSelf()
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (spanAnim === animation) spanAnim = null
                }
            })
            spanAnim = a
            a.start()
        }
        var hidden = false // the covered second cell draws nothing
            set(value) { if (value != field) { field = value; invalidateSelf() } }

        override fun draw(c: Canvas) {
            val b = bounds
            val a = amount
            if (b.isEmpty || a <= 0f || hidden) return
            val grow = (1f - a) * density * 5f // starts a little wider and settles in
            val l = b.left + density * 2f - grow
            val t = b.top + density * 2f - grow
            val r = b.right + (spanX - 1) * b.width() - density * 2f + grow
            val bt = b.bottom + (spanY - 1) * b.height() - density * 2f + grow
            val arm = min(density * 18f, min(r - l, bt - t) * 0.3f) // length of each bracket arm
            val rad = min(density * 7f, arm * 0.6f)                // roundness of the outer corner
            p.alpha = (a * 64).toInt()                              // white at 25 %

            path.reset()
            // top left
            path.moveTo(l, t + arm)
            path.lineTo(l, t + rad)
            path.quadTo(l, t, l + rad, t)
            path.lineTo(l + arm, t)
            // top right
            path.moveTo(r - arm, t)
            path.lineTo(r - rad, t)
            path.quadTo(r, t, r, t + rad)
            path.lineTo(r, t + arm)
            // bottom right
            path.moveTo(r, bt - arm)
            path.lineTo(r, bt - rad)
            path.quadTo(r, bt, r - rad, bt)
            path.lineTo(r - arm, bt)
            // bottom left
            path.moveTo(l + arm, bt)
            path.lineTo(l + rad, bt)
            path.quadTo(l, bt, l, bt - rad)
            path.lineTo(l, bt - arm)
            c.drawPath(path, p)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // The look of a dark button while it is held in the air: the blurred screen behind it (clipped
    // to the circle) with the dark glass on top, so it is opaque (a shadow reads well, other
    // buttons do not shine through) and looks exactly like the real button once it lands.
    // The picture stays fixed to the screen while the button moves and scales, so this must be
    // redrawn (view.invalidate()) whenever the button moves.
    private class GhostGlassDrawable(
        private val view: View,
        private val root: View,
        private val frost: Bitmap?,
        private val rectFrac: Float = 0f // > 0: a rounded rectangle (the Display slider) instead of a circle
    ) : Drawable() {
        private val shader = frost?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HOLE_BASE }
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DARK_BTN }
        private val box = RectF()
        private val m = Matrix()

        private fun shape(c: Canvas, p: Paint) {
            if (rectFrac > 0f) {
                val r = rectFrac * min(box.width(), box.height())
                c.drawRoundRect(box, r, r, p)
            } else {
                c.drawOval(box, p)
            }
        }

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            box.set(b)
            val bmp = frost
            val sh = shader
            if (bmp != null && sh != null && root.width > 0 && root.height > 0) {
                // bitmap -> screen, then undo this view's own move and scale
                val s = view.scaleX
                m.setScale(root.width / bmp.width.toFloat(), root.height / bmp.height.toFloat())
                m.postTranslate(-(view.left + view.translationX), -(view.top + view.translationY))
                m.postScale(1f / s, 1f / s, view.pivotX, view.pivotY)
                sh.setLocalMatrix(m)
                frostPaint.shader = sh
                shape(c, frostPaint)
            } else {
                shape(c, basePaint)
            }
            shape(c, glassPaint)
        }

        override fun getOutline(outline: Outline) {
            if (rectFrac > 0f) {
                outline.setRoundRect(bounds, rectFrac * min(bounds.width(), bounds.height()))
            } else {
                outline.setOval(bounds)
            }
            outline.alpha = 1f
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    // A place in the light panel where the background is cut out, so the dark button on top
    // of it sits directly on the real screen behind the panel instead of on the light layer.
    private class Hole(
        val view: View,
        val bg: YScaleDrawable?,
        val oval: Boolean,
        val radius: Float,
        val pivotBottom: Boolean
    )

    // Paints the panel background BEHIND the real buttons. It is called by PanelRoot.dispatchDraw,
    // in the same draw pass as the buttons, so it can never be a frame behind them.
    //
    //  1. the panel: ONE picture over the whole screen = the blurred screenshot with the light
    //     tint already mixed in (done once, on the tiny picture, in TriggerService)
    //  2. a patch behind every dark button: the same blurred screenshot WITHOUT the tint, so the
    //     see-through dark buttons look frosted instead of grey
    //
    // No offscreen layer and no erasing: that is the cheapest way to draw this on a GPU.
    // If there is no screenshot, the panel is a flat colour and the patches are a flat dark colour.
    private inner class ScrimPainter(private val root: PanelRoot) {
        val holes = mutableListOf<Hole>()

        var alpha = 0f
            set(value) {
                if (value != field) {
                    field = value
                    root.invalidate()
                }
            }

        private val flat = Paint()
        private val panelPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HOLE_BASE }
        private val box = RectF()
        private val full = RectF()
        private val m = Matrix()
        private var panelBmp: Bitmap? = null
        private var frostBmp: Bitmap? = null
        private var frostShader: BitmapShader? = null
        private var pictureAlpha = 0f

        fun invalidate() = root.invalidate()

        fun hasPicture() = frostBmp != null

        // The blurred screen picture (null = none): the dragged toggle paints its own glass from it
        fun frostBitmap(): Bitmap? = frostBmp

        fun setPicture(b: Backdrop, fadeIn: Boolean) {
            panelBmp = b.panel
            frostBmp = b.frost
            frostShader = BitmapShader(b.frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (!fadeIn) {
                pictureAlpha = 1f
                root.invalidate()
                return
            }
            // Fade the blur in over the flat colour, so it never pops
            pictureAlpha = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener {
                    pictureAlpha = it.animatedValue as Float
                    root.invalidate()
                }
                start()
            }
        }

        fun draw(canvas: Canvas) {
            val a = alpha
            if (a <= 0f) return
            val w = root.width
            val h = root.height
            if (w == 0 || h == 0) return

            val pa = pictureAlpha
            val panel = panelBmp
            val frost = frostBmp
            val shader = frostShader
            val usable = panel != null && frost != null && shader != null && pa > 0f
            val flatShare = if (usable) 1f - pa else 1f

            // 1. the panel background
            if (flatShare > 0f) {
                flat.color = FLAT_TINT
                flat.alpha = (Color.alpha(FLAT_TINT) * a * flatShare).toInt()
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), flat)
            }
            if (usable) {
                full.set(0f, 0f, w.toFloat(), h.toFloat())
                panelPaint.alpha = (a * pa * 255).toInt()
                canvas.drawBitmap(panel!!, null, full, panelPaint)
            }

            // 2. a patch behind every dark button
            for (hole in holes) {
                val v = hole.view
                if (!v.isShown || v.width == 0 || v.height == 0) continue
                if (v.tag == true && (v as? TileView)?.shaped != true) continue // a toggle that is ON is solid blue, nothing to show behind it

                // Where is this button right now? (float precise, includes scrolling and sliding)
                var x = 0f
                var y = 0f
                var anc = 1f
                var hs = 1f // height scale of the parents (the expanding rows squash their tiles)
                var cur: View = v
                while (cur !== root) {
                    x += cur.left + cur.translationX
                    y += cur.top + cur.translationY
                    val parent = cur.parent as? View ?: break
                    if (parent !== root) {
                        x -= parent.scrollX
                        y -= parent.scrollY
                        anc *= parent.alpha
                        if (parent.scaleY != 1f) {
                            y = parent.pivotY + (y - parent.pivotY) * parent.scaleY
                            hs *= parent.scaleY
                        }
                    }
                    cur = parent
                }

                val fade = ((if (hole.bg != null) hole.bg.fadeNow else v.alpha) * anc).coerceIn(0f, 1f)
                if (fade <= 0f) continue
                val sy = hole.bg?.scaleNow ?: 1f
                val sx = hole.bg?.scaleXNow ?: 1f

                box.set(x, y, x + v.width, y + v.height * hs)
                val py = if (hole.pivotBottom) box.bottom else box.top
                val ha = fade * a

                canvas.save()
                canvas.scale(sx, sy, box.centerX(), py)
                if (usable && sy > 0.05f && sx > 0.05f) {
                    // the canvas is squashed along Y (and X) while the button animates; undo that
                    // for the picture so it is never stretched
                    m.setScale(w / frost!!.width.toFloat(), h / frost!!.height.toFloat())
                    m.postScale(1f / sx, 1f / sy, box.centerX(), py)
                    shader!!.setLocalMatrix(m)
                    frostPaint.shader = shader
                    frostPaint.alpha = (ha * pa * 255).toInt()
                    drawShape(canvas, hole, frostPaint)
                }
                if (flatShare > 0f) {
                    basePaint.alpha = (ha * flatShare * 255).toInt()
                    drawShape(canvas, hole, basePaint)
                }
                canvas.restore()
            }
        }

        private fun drawShape(canvas: Canvas, hole: Hole, paint: Paint) {
            val tv = hole.view as? TileView
            if (tv != null && isBlockKind(tv.kind) && (tv.cornerFrac < 0.499f || abs(box.width() - box.height()) > 1.5f)) {
                val r = tv.cornerFrac * min(box.width(), box.height())
                canvas.drawRoundRect(box, r, r, paint)
            } else if (tv != null && tv.sliderMode != MODE_CIRCLE) {
                val r = SLIDER_RADIUS * min(box.width(), box.height())
                canvas.drawRoundRect(box, r, r, paint)
            } else if (hole.oval) {
                canvas.drawOval(box, paint)
            } else {
                val r = min(hole.radius, min(box.width(), box.height()) / 2f)
                canvas.drawRoundRect(box, r, r, paint)
            }
        }
    }

    // Root of the panel window
    private inner class PanelRoot(ctx: Context) : FrameLayout(ctx) {
        val backdropPainter = ScrimPainter(this)
        var scrollView: ScrollView? = null
        val items = mutableListOf<AnimItem>()
        val footerItems = mutableListOf<AnimItem>()
        var notifList: LinearLayout? = null
        var expandedArea: View? = null
        var topArea: View? = null
        var topHost: View? = null
        var expandedHost: View? = null
        var favRows: List<TileRowParts> = emptyList()
        val clipState = mutableListOf<Triple<ViewGroup, Boolean, Boolean>>()
        var progress = 0f
        var progressAnim: ValueAnimator? = null
        var leaving = false
        // Edit mode (arranging the toggles): set by showPanel, read by Back / close handling
        var editing = false
        var cancelEdit: (() -> Unit)? = null
        var onClose: (() -> Unit)? = null
        // Extra distance the whole group slides down while OPENING (it starts well above the screen)
        var slide = dp(200).toFloat()

        // Backdrop first, then the scroll view with the real buttons, all in ONE draw pass.
        // The scroll position is brought up to date first (a fling only advances while the
        // scroll view draws), so the patches behind the buttons use the very same position
        // the buttons are drawn at. Before, they were one frame behind when you scrolled fast.
        override fun dispatchDraw(canvas: Canvas) {
            scrollView?.computeScroll()
            backdropPainter.draw(canvas)
            super.dispatchDraw(canvas)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return leaving || super.onInterceptTouchEvent(ev)
        }

        // Whatever way the window goes away, stop the edit-mode animations
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            onClose?.invoke()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) handleBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Home, Recents, another app or a system dialog takes focus -> close.
        // Ignored for the first moments so opening the panel can't close itself.
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus && System.currentTimeMillis() - openedAtMs > 800) closePanel()
        }
    }

    // Scroll view that: fades only at the bottom, reports finger down/up,
    // expands the control center on a swipe down (collapses on a swipe up over the card),
    // and closes the panel when you keep swiping up after reaching the bottom.
    private inner class PanelScroll(ctx: Context) : ScrollView(ctx) {
        var onTouching: ((Boolean) -> Unit)? = null
        var onPullUp: (() -> Unit)? = null
        // true = expand, false = collapse; returns true if it actually did something
        var onExpandSwipe: ((Boolean) -> Boolean)? = null
        var isOverCard: ((Float, Float) -> Boolean)? = null
        // Edit mode: gets every touch first and returns true when it uses it (dragging a toggle)
        var editTouch: ((MotionEvent) -> Boolean)? = null
        // Edit mode: no expand / collapse / close-by-swiping while the toggles are being arranged
        var blockGestures = false

        private var anchorY = -1f
        private var closing = false
        private var downX = 0f
        private var downY = 0f
        private var startedOnCard = false
        private var fired = false

        override fun getTopFadingEdgeStrength(): Float = 0f

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (editTouch?.invoke(e) == true) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    anchorY = -1f
                    closing = false
                    fired = false
                    downX = e.rawX
                    downY = e.rawY
                    startedOnCard = isOverCard?.invoke(e.rawX, e.rawY) ?: false
                    onTouching?.invoke(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiping && !blockGestures) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!fired) {
                            if (dy > dp(60) && dy > abs(dx) * 1.5f && !canScrollVertically(-1)) {
                                fired = true
                                onExpandSwipe?.invoke(true)
                            } else if (-dy > dp(60) && -dy > abs(dx) * 1.5f && startedOnCard) {
                                if (onExpandSwipe?.invoke(false) == true) {
                                    fired = true
                                    closing = true // this swipe already collapsed; don't also close
                                }
                            }
                        }
                        if (!closing) {
                            if (!canScrollVertically(1)) {
                                if (anchorY < 0f) anchorY = e.rawY
                                if (anchorY - e.rawY > dp(90)) {
                                    closing = true
                                    post { onPullUp?.invoke() }
                                }
                            } else {
                                anchorY = -1f
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouching?.invoke(false)
            }
            return super.dispatchTouchEvent(e)
        }
    }

    // ───────────── Top buttons ─────────────

    // kind: 0 = toggle (turn off), 1 = tiles (system control center), 2 = power, 3 = pencil (edit toggles)
    private inner class IconButton(ctx: Context, private val kind: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pencil = Path()

        init {
            background = GlassDrawable(true, 0f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            p.color = Color.WHITE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = s * 0.075f
            when (kind) {
                0 -> {
                    p.style = Paint.Style.STROKE
                    canvas.drawRoundRect(
                        RectF(cx - s * 0.27f, cy - s * 0.15f, cx + s * 0.27f, cy + s * 0.15f),
                        s * 0.15f, s * 0.15f, p
                    )
                    p.style = Paint.Style.FILL
                    canvas.drawCircle(cx - s * 0.12f, cy, s * 0.075f, p)
                }
                1 -> {
                    p.style = Paint.Style.FILL
                    val q = s * 0.15f
                    val g = s * 0.07f
                    val left0 = cx - (q + g / 2f)
                    val top0 = cy - (q + g / 2f)
                    for (i in 0..1) {
                        for (j in 0..1) {
                            val l = left0 + i * (q + g)
                            val t = top0 + j * (q + g)
                            canvas.drawRoundRect(RectF(l, t, l + q, t + q), q * 0.3f, q * 0.3f, p)
                        }
                    }
                }
                3 -> {
                    // pencil: drawn pointing down, then turned so the tip points to the bottom-left
                    p.style = Paint.Style.STROKE
                    p.strokeJoin = Paint.Join.ROUND
                    p.strokeWidth = s * 0.065f
                    val hw = s * 0.095f
                    val top = cy - s * 0.27f
                    val shoulder = cy + s * 0.10f
                    val tip = cy + s * 0.27f
                    pencil.reset()
                    pencil.moveTo(cx - hw, top)
                    pencil.lineTo(cx + hw, top)
                    pencil.lineTo(cx + hw, shoulder)
                    pencil.lineTo(cx, tip)
                    pencil.lineTo(cx - hw, shoulder)
                    pencil.close()
                    canvas.save()
                    canvas.rotate(45f, cx, cy)
                    canvas.drawPath(pencil, p)
                    canvas.drawLine(cx - hw, top + s * 0.10f, cx + hw, top + s * 0.10f, p)
                    canvas.restore()
                }
                else -> {
                    p.style = Paint.Style.STROKE
                    val r = s * 0.2f
                    canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, p)
                    canvas.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.1f, p)
                }
            }
        }
    }

    private fun makeIconButton(kind: Int, desc: String, onClick: () -> Unit): View {
        val b = IconButton(this, kind)
        b.contentDescription = desc
        b.setOnClickListener { onClick() }
        return b
    }

    // Close ours, then open the real system quick settings (needs the accessibility service)
    private fun openSystemControlCenter() {
        val svc = TriggerService.instance
        if (svc == null) {
            toast("Turn on accessibility in the app first")
            return
        }
        closePanel()
        handler.postDelayed({ svc.openSystemQuickSettings() }, 150)
    }

    private fun openPowerMenu() {
        val svc = TriggerService.instance
        if (svc == null) {
            toast("Turn on accessibility in the app first")
            return
        }
        closePanel()
        handler.postDelayed({ svc.openPowerMenu() }, 150)
    }

    private fun takeScreenshot() {
        val svc = TriggerService.instance
        if (svc == null) {
            toast("Turn on accessibility in the app first")
            return
        }
        closePanel()
        handler.postDelayed({ svc.takeScreenshot() }, 450) // wait until the panel is gone
    }

    // Confirmation shown inside the panel, so no extra window is needed
    private fun showStopConfirm(root: FrameLayout) {
        if (confirmView != null) return

        val scrim = FrameLayout(this)
        scrim.setBackgroundColor(0xB3000000.toInt())
        scrim.isClickable = true
        scrim.setOnClickListener { handleBack() }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.isClickable = true // taps inside the box must not cancel
        box.setPadding(dp(24), dp(22), dp(24), dp(16))
        box.background = GradientDrawable().apply {
            setColor(0xFF2B2B2B.toInt())
            cornerRadius = dp(28).toFloat()
        }

        val title = TextView(this)
        title.text = "Turn off Flow Center?"
        title.textSize = 18f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(Color.WHITE)
        box.addView(title)

        val msg = TextView(this)
        msg.text = "The swipe-down gesture will stop working until you turn it on again in the app."
        msg.textSize = 14f
        msg.setTextColor(0xFFBDC1C6.toInt())
        msg.setPadding(0, dp(10), 0, dp(18))
        box.addView(msg)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.gravity = Gravity.END
        val cancel = makePill("Cancel")
        cancel.setOnClickListener { handleBack() }
        val ok = makePill("Turn off", 0xFFC62828.toInt())
        ok.setOnClickListener { turnOffControlCenter() }
        btnRow.addView(cancel)
        val okLp = LinearLayout.LayoutParams(WRAP, WRAP)
        okLp.leftMargin = dp(10)
        btnRow.addView(ok, okLp)
        box.addView(btnRow)

        val boxLp = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)
        boxLp.setMargins(dp(32), 0, dp(32), 0)
        scrim.addView(box, boxLp)
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        confirmView = scrim
    }

    // Stops the app's control center completely (same as Stop in the app)
    private fun turnOffControlCenter() {
        getSharedPreferences("cc", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", false).apply()
        closePanel(false)
        toast("Flow Center turned off")
        stopSelf()
    }

    private fun openSettings(action: String) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { toast("Can't open settings") }
    }

    // ───────────── Notification helpers ─────────────

    private fun isShowable(s: StatusBarNotification): Boolean {
        val n = s.notification
        if (s.packageName == packageName) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return false
        return n.extras.getCharSequence(Notification.EXTRA_TITLE) != null ||
            n.extras.getCharSequence(Notification.EXTRA_TEXT) != null
    }

    private fun makeNotifCard(sbn: StatusBarNotification, onGestureEnd: () -> Unit): View {
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        val pm = packageManager

        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }

        val icon: Drawable? = try {
            pm.getApplicationIcon(sbn.packageName)
        } catch (e: Exception) {
            try { n.smallIcon?.loadDrawable(this) } catch (e2: Exception) { null }
        }

        val time = DateUtils.getRelativeTimeSpanString(
            sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.TOP
        row.setPadding(dp(16), dp(14), dp(16), dp(14))
        row.background = GradientDrawable().apply {
            setColor(0xBFFFFFFF.toInt()) // white at 75 %: the blurred panel shows through a little
            cornerRadius = dp(12).toFloat() // was 24: smaller = less rounded
        }

        val iv = ImageView(this)
        iv.setImageDrawable(icon)
        val ivLp = LinearLayout.LayoutParams(dp(36), dp(36))
        ivLp.rightMargin = dp(12)
        row.addView(iv, ivLp)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val header = TextView(this)
        header.text = appName + " · " + time
        header.textSize = 11f
        header.setTextColor(Color.BLACK)
        header.maxLines = 1
        header.ellipsize = TextUtils.TruncateAt.END
        col.addView(header)

        val t = TextView(this)
        t.text = title
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(Color.BLACK)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        col.addView(t)

        val b = TextView(this)
        b.text = text
        b.textSize = 13f
        b.setTextColor(Color.BLACK)
        b.maxLines = 2
        b.ellipsize = TextUtils.TruncateAt.END
        col.addView(b)

        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Tap = open the app's intent, close the panel, remove if auto-cancel
        row.setOnClickListener {
            closePanel()
            try { n.contentIntent?.send() } catch (e: PendingIntent.CanceledException) {}
            if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) {
                NotificationListener.instance?.cancelNotification(sbn.key)
            }
        }

        // Deliberate swipe to clear (tells you if the app made it non-clearable)
        attachSwipeToClear(row, sbn, onGestureEnd)

        return row
    }

    // Swipe-to-clear that is hard to trigger by accident:
    //  1. The gesture must be clearly horizontal (twice as much sideways as vertical),
    //     otherwise it is treated as normal scrolling and the card never moves.
    //  2. Releasing early snaps the card back.
    //  3. It only clears if you drag past about half the width, or flick fast
    //     in the same direction after dragging at least a third of the width.
    private fun attachSwipeToClear(
        card: View,
        sbn: StatusBarNotification,
        onGestureEnd: () -> Unit
    ) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startDx = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        fun track(e: MotionEvent) {
            // Use raw coordinates so the velocity is correct while the card itself moves
            val c = MotionEvent.obtain(e)
            c.setLocation(e.rawX, e.rawY)
            tracker?.addMovement(c)
            c.recycle()
        }

        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain()
                    track(e)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 2) {
                        dragging = true
                        swiping = true
                        startDx = dx
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        // Cancel the card's own press/click handling
                        val c = MotionEvent.obtain(e)
                        c.action = MotionEvent.ACTION_CANCEL
                        v.onTouchEvent(c)
                        c.recycle()
                    }
                    if (dragging) {
                        val shift = dx - startDx
                        v.translationX = shift
                        v.alpha = (1f - abs(shift) / (v.width * 0.9f)).coerceIn(0.25f, 1f)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        tracker?.recycle()
                        tracker = null
                        false
                    } else {
                        dragging = false
                        track(e)
                        tracker?.computeCurrentVelocity(1000)
                        val vx = tracker?.xVelocity ?: 0f
                        tracker?.recycle()
                        tracker = null

                        val w = v.width.toFloat()
                        val dist = abs(v.translationX)
                        val sameDirection = vx * v.translationX > 0f
                        val fastFlick = sameDirection && abs(vx) > dp(1500) && dist > w * 0.33f
                        val wantsDismiss = e.actionMasked == MotionEvent.ACTION_UP &&
                            (dist > w * 0.5f || fastFlick)
                        if (wantsDismiss && !sbn.isClearable) {
                            toast("This notification can't be cleared")
                        }
                        val dismiss = wantsDismiss && sbn.isClearable

                        if (dismiss) {
                            val dir = if (v.translationX > 0f) 1f else -1f
                            v.animate()
                                .translationX(dir * w)
                                .alpha(0f)
                                .setDuration(180)
                                .withEndAction {
                                    collapseAndRemove(v) {
                                        swiping = false
                                        NotificationListener.instance?.cancelNotification(sbn.key)
                                    }
                                }
                                .start()
                        } else {
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(150)
                                .withEndAction {
                                    swiping = false
                                    onGestureEnd()
                                }
                                .start()
                        }
                        true
                    }
                }
                else -> false
            }
        }
    }

    // Smoothly shrinks a card to nothing, removes it, then calls done()
    private fun collapseAndRemove(v: View, done: () -> Unit) {
        val lp = v.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            (v.parent as? ViewGroup)?.removeView(v)
            done()
            return
        }
        val startH = v.height
        val startMargin = lp.bottomMargin
        val anim = ValueAnimator.ofFloat(1f, 0f)
        anim.duration = 150
        anim.addUpdateListener {
            val f = it.animatedValue as Float
            lp.height = (startH * f).toInt()
            lp.bottomMargin = (startMargin * f).toInt()
            v.layoutParams = lp
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                (v.parent as? ViewGroup)?.removeView(v)
                done()
            }
        })
        anim.start()
    }

    private fun makePill(label: String, bg: Int = DARK_BTN): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 13f
        t.setTextColor(Color.WHITE)
        t.setPadding(dp(20), dp(11), dp(20), dp(11))
        t.background = if (bg == DARK_BTN) {
            GlassDrawable(false, dp(22).toFloat())
        } else {
            GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(22).toFloat()
            }
        }
        return t
    }

    private fun makeMessage(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.gravity = Gravity.CENTER
        t.setTextColor(0xFF5F6368.toInt())
        t.setPadding(0, dp(24), 0, dp(24))
        return t
    }

    // ───────────── Control center helpers ─────────────

    // Round tile with a drawn icon (white, same style as the top buttons)
    private fun makeTile(kind: Int, desc: String, onColor: Int = TOGGLE_ON, label: String = desc): TextView {
        val t = TileView(this, kind, onColor, label)
        t.contentDescription = desc
        t.includeFontPadding = false
        t.gravity = Gravity.CENTER
        t.setPadding(0, 0, 0, 0)
        setTileColor(t, false)
        return t
    }

    // A TextView without text that draws a PNG icon from res/drawable-nodpi (ic_tile_*.png).
    // The PNGs are white on transparent, already the same size and centred inside a square
    // canvas, so the icon is simply drawn centred on the tile. The open animation fades the
    // "text colour"; the icon follows that colour's alpha, like the old labels did.
    private inner class TileView(
        ctx: Context, var kind: Int, var onColor: Int, var label: String
    ) : TextView(ctx) {
        private var icon: Drawable? = load(false)
        private var iconAlt: Drawable? = if (kind == T_SOUND) load(true) else null // bell with a slash

        // ── Display toggle only: a round button (MODE_CIRCLE) or a brightness slider (MODE_WIDE / MODE_TALL)
        var sliderMode = MODE_CIRCLE
            set(value) {
                if (value != field) {
                    field = value
                    startMorph()
                }
            }
        // true while a resize is being applied: the shape then GLIDES to its new size
        // (false: it simply jumps there, e.g. when the panel is built or a toggle is dropped)
        var animateMorph = false
        // Sound toggle: the bell with a slash (muted) / the plain bell, and whether it shows its ON colour
        var muted = false
            set(value) { if (value != field) { field = value; invalidate() } }
        var solidOn = false
        // the Sound toggle's ON colour (blue / yellow); it melts away while the toggle turns into a slider
        val solidColorNow: Int get() {
            if (!solidOn) return 0
            val a = 1f - smooth(ramp(sliderness, 0f, 0.7f))
            return (onColor and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
        }
        // true while the toggle is a slider or on its way to / from one
        val shaped: Boolean get() = sliderMode != MODE_CIRCLE || wideAmt > 0f || tallAmt > 0f
        // how far the toggle has grown into a wide / tall slider right now (0 = round, 1 = full size)
        private var wideAmt = 0f
        private var tallAmt = 0f
        private var morphAnim: ValueAnimator? = null
        // 0 = round button ... 1 = slider (also 1 while a wide slider turns into a tall one)
        private val sliderness: Float get() = (wideAmt + tallAmt).coerceIn(0f, 1f)
        // corner roundness: a full circle at 0.5, SLIDER_RADIUS for the slider, in between while morphing
        val cornerFrac: Float get() = 0.5f - (0.5f - SLIDER_RADIUS) * sliderness
        private var baseW = 0 // size of ONE cell's tile
        private var baseH = 0

        private fun startMorph() {
            morphAnim?.cancel()
            morphAnim = null
            val w1 = if (sliderMode == MODE_WIDE) 1f else 0f
            val t1 = if (sliderMode == MODE_TALL) 1f else 0f
            val w0 = wideAmt
            val t0 = tallAmt
            if (!animateMorph || !isAttachedToWindow || (w0 == w1 && t0 == t1)) {
                wideAmt = w1
                tallAmt = t1
                requestLayout()
                invalidate()
                return
            }
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 380
            a.interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // quick start, soft landing
            a.addUpdateListener {
                val k = it.animatedValue as Float
                wideAmt = w0 + (w1 - w0) * k
                tallAmt = t0 + (t1 - t0) * k
                requestLayout()
                invalidate()
                // the frosted patch behind the toggle is painted by the root: keep it in step
                (rootView as? PanelRoot)?.backdropPainter?.invalidate()
            }
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (morphAnim === animation) morphAnim = null
                }
            })
            morphAnim = a
            a.start()
        }

        override fun onDetachedFromWindow() {
            morphAnim?.cancel()
            morphAnim = null
            super.onDetachedFromWindow()
        }
        // true: the layout makes a slider as big as its 2 cells (false: the size is set by hand, like the floating copy)
        var autoSpan = true
        var level = 0.5f // brightness 0..1: how much of the slider is filled
            set(value) { if (value != field) { field = value; invalidate() } }
        var handleAmount = 0f // edit mode: how visible the resize handle is (0..1)
            set(value) { if (value != field) { field = value; invalidate() } }
        var canResize = false // the handle only exists for a toggle in the expanded grid

        private val dens = resources.displayMetrics.density
        private val sliderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sliderPath = Path()
        private val sliderBox = RectF()
        private val sliderFill = RectF()

        // The slider covers 2 cells: the layout gives this tile one cell, we stretch it over the
        // second one (the cell next to it, or the one below it, whose own toggle is hidden).
        // Sideways the gap between two cells is 2 x the side margin; downwards the gap is the
        // margins plus the name label's height.
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (!autoSpan) { // the floating copy: its size is set by hand, one cell = its short side
                baseW = min(measuredWidth, measuredHeight)
                baseH = baseW
                return
            }
            baseW = measuredWidth
            baseH = measuredHeight
            if (wideAmt <= 0f && tallAmt <= 0f) return
            val lp = layoutParams as? ViewGroup.MarginLayoutParams
            val side = lp?.leftMargin ?: 0
            val vert = (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            // full size = 2 cells (wide) / 2 cells + gap + label (tall); in between while morphing
            val w = baseW + ((baseW + side * 2) * wideAmt).toInt()
            val h = baseH + ((baseH + vert + dp(LABEL_H_DP)) * tallAmt).toInt()
            setMeasuredDimension(w, h)
        }

        // Centre of the resize handle, in this view's own coordinates: on the circle's edge at the
        // bottom right, or inside the bottom right corner of the slider
        fun handleX(): Float {
            val bw = if (baseW > 0) baseW.toFloat() else width.toFloat()
            val bh = if (baseH > 0) baseH.toFloat() else height.toFloat()
            val circle = bw / 2f + (min(bw, bh) / 2f + 2f * dens) * 0.7071f
            return circle + (width - 16f * dens - circle) * sliderness
        }
        fun handleY(): Float {
            val bw = if (baseW > 0) baseW.toFloat() else width.toFloat()
            val bh = if (baseH > 0) baseH.toFloat() else height.toFloat()
            val circle = bh / 2f + (min(bw, bh) / 2f + 2f * dens) * 0.7071f
            return circle + (height - 16f * dens - circle) * sliderness
        }

        private fun drawSun(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float) {
            sliderPaint.color = color
            sliderPaint.alpha = (alpha * 255).toInt()
            sliderPaint.style = Paint.Style.FILL
            val r = s * 0.10f
            canvas.drawCircle(cx, cy, r, sliderPaint)
            sliderPaint.style = Paint.Style.STROKE
            sliderPaint.strokeWidth = s * 0.032f
            sliderPaint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = cos(a).toFloat()
                val sn = sin(a).toFloat()
                canvas.drawLine(cx + c * r * 1.6f, cy + sn * r * 1.6f, cx + c * r * 2.1f, cy + sn * r * 2.1f, sliderPaint)
            }
            sliderPaint.style = Paint.Style.FILL
        }

        // The slider: dark glass track (the background), a light fill that grows from the left
        // (wide) or from the bottom (tall), and a sun that turns amber where the fill covers it.
        // It is scaled the same way as the background while the panel expands / closes.
        private fun drawSlider(canvas: Canvas, bgd: YScaleDrawable?, fade: Float, withSun: Boolean = true) {
            if (fade <= 0f) return
            val w = width.toFloat()
            val h = height.toFloat()
            val wide = wideAmt >= tallAmt
            canvas.save()
            val sx = bgd?.scaleXNow ?: 1f
            val sy = bgd?.scaleNow ?: 1f
            if (sx != 1f || sy != 1f) canvas.scale(sx, sy, w / 2f, 0f)

            val rad = cornerFrac * min(w, h)
            sliderBox.set(0f, 0f, w, h)
            sliderPath.reset()
            sliderPath.addRoundRect(sliderBox, rad, rad, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(sliderPath)

            val lv = level.coerceIn(0f, 1f)
            if (wide) sliderFill.set(0f, 0f, w * lv, h) else sliderFill.set(0f, h * (1f - lv), w, h)
            sliderPaint.style = Paint.Style.FILL
            sliderPaint.color = SLIDER_FILL
            sliderPaint.alpha = (Color.alpha(SLIDER_FILL) * fade).toInt()
            canvas.drawRect(sliderFill, sliderPaint)

            if (withSun) {
                val s = min(w, h)
                val sunX = if (wide) s * 0.5f else w / 2f
                val sunY = if (wide) h / 2f else h - s * 0.5f
                drawSun(canvas, sunX, sunY, s, TOGGLE_YELLOW, fade) // fully amber, whatever the level
            }

            canvas.restore() // track clip
            canvas.restore() // scale
        }

        // The Sound toggle's bell. It always sits in the middle of the toggle's first cell (round /
        // wide) or of its bottom cell (tall), so it glides down while a round toggle grows into a tall
        // slider. White as a round toggle, fully blue (007AFF) as a slider, like the Display sun is fully amber.
        private fun drawBell(canvas: Canvas, bgd: YScaleDrawable?, fade: Float, sl: Float) {
            val d = (if (muted && iconAlt != null) iconAlt else icon) ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val bw = if (baseW > 0) baseW.toFloat() else w
            val bh = if (baseH > 0) baseH.toFloat() else h
            val box = (min(bw, bh) * ICON_BOX).toInt()
            val cxp = bw / 2f
            val cyp = h - bh / 2f
            val sx = bgd?.scaleXNow ?: 1f
            val sy = bgd?.scaleNow ?: 1f
            canvas.save()
            if ((iconScales || sl > 0f) && (sx != 1f || sy != 1f)) canvas.scale(sx, sy, w / 2f, 0f)
            val l = (cxp - box / 2f).toInt()
            val t = (cyp - box / 2f).toInt()
            d.setBounds(l, t, l + box, t + box)
            // white as a round toggle, turning fully blue as it becomes a slider (no wipe with the level)
            val k = smooth(ramp(sl, 0.35f, 1f))
            if (k < 1f) {
                d.clearColorFilter()
                d.alpha = (255 * fade).toInt()
                d.draw(canvas)
            }
            if (k > 0f) {
                d.setColorFilter(SOUND_BLUE, PorterDuff.Mode.SRC_IN)
                d.alpha = (255 * fade * k).toInt()
                d.draw(canvas)
                d.clearColorFilter()
            }
            d.alpha = 255
            canvas.restore()
        }

        // Edit mode: a small white knob with a diagonal two-way arrow. Drag it to resize.
        private fun drawHandle(canvas: Canvas, hx: Float, hy: Float, amount: Float) {
            val r = 11f * dens
            sliderPaint.style = Paint.Style.FILL
            sliderPaint.color = Color.WHITE
            sliderPaint.alpha = (245 * amount).toInt()
            canvas.drawCircle(hx, hy, r, sliderPaint)
            sliderPaint.style = Paint.Style.STROKE
            sliderPaint.strokeWidth = 1.6f * dens
            sliderPaint.strokeCap = Paint.Cap.ROUND
            sliderPaint.color = 0xFF1C1C1E.toInt()
            sliderPaint.alpha = (255 * amount).toInt()
            val d = r * 0.42f
            val k = r * 0.38f
            canvas.drawLine(hx - d, hy - d, hx + d, hy + d, sliderPaint)
            canvas.drawLine(hx + d, hy + d, hx + d - k, hy + d, sliderPaint)
            canvas.drawLine(hx + d, hy + d, hx + d, hy + d - k, sliderPaint)
            canvas.drawLine(hx - d, hy - d, hx - d + k, hy - d, sliderPaint)
            canvas.drawLine(hx - d, hy - d, hx - d, hy - d + k, sliderPaint)
            sliderPaint.style = Paint.Style.FILL
        }

        // Lets a tile slot show a different toggle (used when the user rearranges the toggles)
        fun rebind(newKind: Int, newOnColor: Int, newLabel: String) {
            kind = newKind
            onColor = newOnColor
            label = newLabel
            icon = load(false)
            iconAlt = if (newKind == T_SOUND) load(true) else null
            invalidate()
        }

        private fun load(alt: Boolean): Drawable? {
            val name = when (kind) {
                T_FLASH -> "ic_tile_flash"
                T_NET -> "ic_tile_wifi"
                T_BT -> "ic_tile_bluetooth"
                T_SETTINGS -> "ic_tile_settings"
                T_ROTATE -> "ic_tile_rotation_lock"
                T_DND -> "ic_tile_moon"
                T_SOUND -> if (alt) "ic_tile_sound_off" else "ic_tile_sound"
                T_AIRPLANE -> "ic_tile_airplane"
                T_LOCATION -> "ic_tile_location"
                T_DISPLAY -> "ic_tile_display"
                T_BATTERY -> "ic_tile_battery"
                else -> "ic_tile_screenshot"
            }
            val id = resources.getIdentifier(name, "drawable", packageName)
            if (id == 0) return null // PNG missing from res/drawable-nodpi
            return try { resources.getDrawable(id, null).mutate() } catch (e: Exception) { null }
        }

        // true = the icon is scaled together with the black button (same X / Y scale, same pivot)
        var iconScales = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fade = Color.alpha(currentTextColor) / 255f
            val bgd = background as? YScaleDrawable
            val sl = sliderness
            if (kind == T_SOUND) {
                // track + fill fade in during the second half of the morph; the bell stays put
                if (sl > 0f) drawSlider(canvas, bgd, fade * smooth(ramp(sl, 0.35f, 1f)), false)
                drawBell(canvas, bgd, fade, sl)
            } else {
            // The slider's light fill + sun fade in during the second half of the morph ...
            if (sl > 0f) drawSlider(canvas, bgd, fade * smooth(ramp(sl, 0.35f, 1f)))
            // ... while the PNG icon fades out during the first half (it sits where the sun will be)
            val iconFade = 1f - smooth(ramp(sl, 0f, 0.5f))
            val d = icon
            if (d != null && iconFade > 0f) {
                val m = min(width, height)
                val box = (m * ICON_BOX).toInt()
                val wide = wideAmt >= tallAmt
                val cx = if (wide) m * 0.5f else width / 2f
                val cy = if (wide) height / 2f else m * 0.5f
                val l = (cx - box / 2f).toInt()
                val t = (cy - box / 2f).toInt()
                d.setBounds(l, t, l + box, t + box)
                d.alpha = (Color.alpha(currentTextColor) * iconFade).toInt()
                if (iconScales && bgd != null && (bgd.scaleNow != 1f || bgd.scaleXNow != 1f)) {
                    // same pivot as the button: centre horizontally, top edge vertically,
                    // so the icon stays in the middle of the shrunken button
                    canvas.save()
                    canvas.scale(bgd.scaleXNow, bgd.scaleNow, width / 2f, 0f)
                    d.draw(canvas)
                    canvas.restore()
                } else {
                    d.draw(canvas)
                }
            }
            }
            if (isBlockKind(kind) && canResize && autoSpan && handleAmount > 0f && fade > 0f) {
                drawHandle(canvas, handleX(), handleY(), handleAmount * fade)
            }
        }
    }

    private fun setTileColor(t: TextView, on: Boolean) {
        t.setTextColor(Color.WHITE)
        val slider = (t as? TileView)?.sliderMode ?: MODE_CIRCLE
        // the backdrop painter skips the frosted patch behind a toggle that is ON; a slider is never "on"
        t.tag = if (slider != MODE_CIRCLE) false else on
        val tvLive = t as? TileView
        val d: Drawable = if (tvLive != null && isBlockKind(tvLive.kind)) {
            // round, slider, or anything in between; the Sound toggle also paints its ON colour
            GlassDrawable(false, 0f, 0f, { tvLive.cornerFrac }, { tvLive.solidColorNow })
        } else if (slider != MODE_CIRCLE) {
            GlassDrawable(false, 0f, SLIDER_RADIUS)
        } else if (on) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((t as? TileView)?.onColor ?: TOGGLE_ON) // 100 % opaque: no blur shows through
            }
        } else {
            GlassDrawable(true, 0f)
        }
        val cur = t.background
        if (cur is YScaleDrawable) cur.setInner(d) else t.background = d
    }

    // A cell whose tile (cell width minus the side margins) is always as tall as it is wide,
    // so the tile inside is a perfect circle. top/bottom are the margins above/below the tile.
    private inner class SquareCell(
        ctx: Context, private val side: Int, private val top: Int, private val bottom: Int
    ) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = View.MeasureSpec.getSize(widthMeasureSpec)
            val h = w - 2 * side + top + bottom
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        }
    }

    // Lays tiles out as big circles that fill a row of `columns` cells (4 for the expanded rows,
    // 5 for the favourite row), each with its name label under it. If a row has fewer tiles than
    // columns, the rest stays empty. The gap between circles is 2 x the side margin.
    // collapsedLabels = true: the label slot starts with height 0 and an invisible label
    // (the favourite row; the expand animation opens it).
    private fun makeTileRow(columns: Int, collapsedLabels: Boolean, vararg tiles: TextView): TileRowParts {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val side = if (columns > 4) dp(5) else dp(10)
        val top = side
        val bottom = dp(LABEL_GAP_DP)
        val cells = ArrayList<TileCellParts>()
        tiles.forEach { t ->
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL

            val cell = SquareCell(this, side, top, bottom)
            val tlp = FrameLayout.LayoutParams(MATCH, MATCH)
            tlp.setMargins(side, top, side, bottom)
            cell.addView(t, tlp)
            col.addView(cell, LinearLayout.LayoutParams(MATCH, WRAP))

            val label = TextView(this)
            label.text = (t as? TileView)?.label ?: t.contentDescription
            label.textSize = 10.5f
            label.setTextColor(Color.BLACK)
            label.includeFontPadding = false
            label.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            if (collapsedLabels) label.alpha = 0f
            val slot = FrameLayout(this)
            slot.addView(label, FrameLayout.LayoutParams(MATCH, dp(LABEL_H_DP), Gravity.TOP))
            col.addView(slot, LinearLayout.LayoutParams(MATCH, if (collapsedLabels) 0 else dp(LABEL_H_DP)))

            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            cells.add(TileCellParts(t, slot, label, col))
        }
        for (i in tiles.size until columns) {
            r.addView(Space(this), LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val parts = TileRowParts(r, cells)
        r.tag = parts // lets the expand / close animations find the tiles of a row
        return parts
    }

    private fun makeLabel(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.BLACK)
        t.setPadding(0, dp(20), 0, dp(4))
        return t
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

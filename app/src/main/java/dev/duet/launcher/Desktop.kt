package dev.duet.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** An app's window on the external display. */
data class AppWindow(val pkg: String, val bounds: Rect)

/** A right-click menu: for an app ([pkg]), or for the desktop itself when null. */
data class ContextMenu(val pkg: String?, val x: Float, val y: Float)

/**
 * Desktop mode: when an external display is connected, Duet shows a desktop with a taskbar
 * there, and the Duo becomes a touchpad that moves a mouse pointer on it.
 *
 * Android doesn't let an ordinary app inject input, so the pointer and its taps go through
 * Duet's accessibility service: it draws the pointer as an overlay on the external display and
 * performs taps, drags and scrolls there as gestures (Android 11+).
 */
object DesktopMode {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var displays: DisplayManager

    /** The external display in use, or null when none is connected (or desktop mode is off). */
    var display by mutableStateOf<Display?>(null)
        private set
    val active: Boolean get() = display != null

    var menuOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    /** Packages with a window open on the external display, in the order they appeared. */
    var running by mutableStateOf<List<String>>(emptyList())
        private set
    /** App windows visible on the external display, topmost first. */
    var windows by mutableStateOf<List<AppWindow>>(emptyList())
        private set
    val activeWindow: AppWindow? get() = windows.firstOrNull()
    /** App shown full screen (covering the taskbar), if any. */
    var fullscreenPkg by mutableStateOf<String?>(null)
        private set
    /** Right-click menu open in the taskbar layer, shown at the pointer. */
    var contextMenu by mutableStateOf<ContextMenu?>(null)
    /** The pointer is resting on the bottom edge, so a hidden taskbar slides back in. */
    var peek by mutableStateOf(false)
        private set
    /** A window being moved or resized by dragging: its outline, in display pixels. */
    var dragOutline by mutableStateOf<Rect?>(null)

    /** The taskbar steps aside for a full-screen app (until the pointer touches the bottom edge). */
    val taskbarHidden: Boolean
        get() = activeWindow?.pkg != null && activeWindow?.pkg == fullscreenPkg && !peek && !menuOpen && contextMenu == null

    /** Bumped when the touchpad should close (display unplugged). */
    var closeTouchpad by mutableIntStateOf(0)
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun init(ctx: Context) {
        app = ctx.applicationContext
        displays = app.getSystemService(DisplayManager::class.java)
        displays.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = refresh(autoOpen = true)
            override fun onDisplayRemoved(id: Int) = refresh(autoOpen = false)
            override fun onDisplayChanged(id: Int) {
                if (id == display?.displayId) { clampPointer(); overlays?.relayout() }
            }
        }, main)
        refresh(autoOpen = false)
    }

    /** The first external screen: a real monitor/TV or a cast display (not a private one like screen recording). */
    fun findExternal(): Display? = displays.displays.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && it.isValid &&
            it.flags and Display.FLAG_PRESENTATION != 0 && it.flags and Display.FLAG_PRIVATE == 0
    }

    /** Re-evaluates the connected displays; also called when the setting or the service changes. */
    fun refresh(autoOpen: Boolean) {
        val ext = if (supported && DuetApp.instance.prefs.desktopMode) findExternal() else null
        if (ext?.displayId == display?.displayId) {
            if (ext != null) attachOverlays()
            return
        }
        stopShell()
        display = ext
        updateNotification()
        if (ext == null) { closeTouchpad++; return }
        val (w, h) = size(ext)
        pointerX = w / 2f; pointerY = h / 2f
        attachOverlays()
        // A desktop left over from an earlier connection keeps its old window size; start fresh.
        runCatching {
            app.getSystemService(android.app.ActivityManager::class.java).appTasks
                .filter { it.taskInfo.baseActivity?.className == DesktopActivity::class.java.name }
                .forEach { it.finishAndRemoveTask() }
        }
        showDesktop()
        if (autoOpen) openTouchpad()
    }

    fun size(d: Display): Pair<Int, Int> {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    /**
     * Taskbar height in pixels. In Android's desktop mode the system reserves a navigation bar
     * strip along the bottom that apps never use; the taskbar covers exactly that strip (or its
     * own height, whichever is taller), so maximized windows end right where it starts.
     */
    fun taskbarPx(): Int {
        val d = display ?: return 0
        val own = (TASKBAR_DP * density()).toInt()
        val app = android.graphics.Point().also { @Suppress("DEPRECATION") d.getSize(it) }
        val reserved = size(d).second - app.y
        return maxOf(own, reserved)
    }

    /** Opens activities on the desktop in Android's full-screen mode even when the display defaults to windows. */
    fun fullscreenOptions(displayId: Int): ActivityOptions {
        val o = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        // Hidden (test) API: if it's unavailable the desktop just opens as a big window.
        runCatching {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 1 /* FULLSCREEN */)
        }.onFailure { android.util.Log.w("DuetDesktop", "no setLaunchWindowingMode", it) }
        return o
    }

    fun density(): Float {
        val d = display ?: return 1f
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return m.density
    }

    // --- Launching on the desktop ------------------------------------------------------

    // --- Notification ------------------------------------------------------------------------

    private const val CHANNEL = "desktop"
    private const val NOTIFICATION_ID = 7

    /** While desktop mode is on, a quiet ongoing notification that brings the touchpad back. */
    private fun updateNotification() {
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        if (display == null) { nm.cancel(NOTIFICATION_ID); return }
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Desktop mode", android.app.NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while Duet's desktop is on an external display" }
        )
        val open = android.app.PendingIntent.getActivity(
            app, 0,
            Intent(app, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_desktop)
            .setContentTitle("Desktop mode is on")
            .setContentText("Tap to open the touchpad")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(android.app.Notification.CATEGORY_STATUS)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    fun openTouchpad() {
        runCatching {
            app.startActivity(
                Intent(app, TouchpadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        }
    }

    /** Brings the desktop (wallpaper and shortcuts) in front of the apps. */
    fun showDesktop() {
        val d = display ?: return
        menuOpen = false
        minimized = running.toSet()
        runCatching {
            app.startActivity(
                Intent(app, DesktopActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                fullscreenOptions(d.displayId).setLaunchBounds(desktopBounds().takeIf { !it.isEmpty }).toBundle(),
            )
        }
    }

    private var cascade = 0

    /** Window for the next app: most of the screen above the taskbar, each a little offset from the last. */
    private fun nextWindow(d: Display): Rect {
        val (w, h) = size(d)
        val dp = density()
        val bar = taskbarPx()
        val ww = (w * 0.66f).toInt(); val wh = ((h - bar) * 0.8f).toInt()
        val step = (36 * dp).toInt() * (cascade++ % 5)
        val left = (w - ww) / 2 - 2 * (36 * dp).toInt() + step
        val top = (h - bar - wh) / 2 - 2 * (24 * dp).toInt() + step
        return Rect(left, top.coerceAtLeast(0), left + ww, (top + wh).coerceAtMost(h - bar))
    }

    fun options(): Bundle? {
        val d = display ?: return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(nextWindow(d)).toBundle()
    }

    fun launch(appInfo: AppInfo) {
        menuOpen = false; query = ""; contextMenu = null
        val d = display ?: return
        // An app that's already open comes to the front where it is, rather than jumping to a new spot.
        val known = windows.firstOrNull { it.pkg == appInfo.component.packageName }?.bounds
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(known ?: nextWindow(d)).toBundle()
        val la = app.getSystemService(android.content.pm.LauncherApps::class.java)
        val ok = if (appInfo.user == android.os.Process.myUserHandle()) runCatching {
            app.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(appInfo.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                opts,
            )
        }.isSuccess else false
        if (!ok) runCatching { la.startMainActivity(appInfo.component, appInfo.user, null, opts) }
        main.postDelayed({ refreshRunning() }, 800)
    }

    fun launchPackage(pkg: String) {
        DuetApp.instance.apps.all.firstOrNull { it.component.packageName == pkg }?.let { launch(it) }
    }

    // --- Pointer -------------------------------------------------------------------------

    var pointerX = 0f; private set
    var pointerY = 0f; private set

    fun movePointer(dx: Float, dy: Float) {
        pointerX += dx; pointerY += dy
        clampPointer()
        display?.let { d ->
            val h = size(d).second
            val bar = taskbarPx().toFloat()
            if (!peek && pointerY >= h - 3) peek = true
            else if (peek && pointerY < h - bar * 1.5f) peek = false
        }
        overlays?.movePointer()
        drag?.moveTo(pointerX, pointerY)
    }

    private fun clampPointer() {
        val d = display ?: return
        val (w, h) = size(d)
        pointerX = pointerX.coerceIn(0f, w - 1f); pointerY = pointerY.coerceIn(0f, h - 1f)
    }

    private val service: AccessibilityService? get() = ScreenWatcherService.instance
    val canControl: Boolean get() = supported && ScreenCoverage.serviceConnected && service != null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun gesture(stroke: GestureDescription.StrokeDescription, done: (() -> Unit)? = null) {
        val s = service ?: return
        val d = display ?: return
        val g = GestureDescription.Builder().setDisplayId(d.displayId).addStroke(stroke).build()
        s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done?.invoke() }
        }, main)
    }

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y) }

    fun click() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        gesture(GestureDescription.StrokeDescription(point(pointerX, pointerY), 0, 40))
    }

    /** A long press: opens context menus, like a right-click. */
    fun longPress() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        gesture(GestureDescription.StrokeDescription(point(pointerX, pointerY), 0, 650))
    }

    private var drag: LiveStroke? = null

    /** Presses at the pointer and keeps holding; the press follows the pointer until [endDrag]. */
    fun startDrag() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        drag?.end()
        drag = LiveStroke(pointerX, pointerY)
    }

    fun endDrag() { drag?.end(); drag = null }

    private var scroll: LiveStroke? = null
    private var scrollX = 0f
    private var scrollY = 0f

    /** Two-finger scroll: a swipe at the pointer that moves with the fingers (like touching the screen). */
    fun scrollBy(dx: Float, dy: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val d = display ?: return
        val (w, h) = size(d)
        val s = scroll ?: LiveStroke(pointerX, pointerY).also { scroll = it; scrollX = pointerX; scrollY = pointerY }
        scrollX = (scrollX + dx).coerceIn(1f, w - 2f); scrollY = (scrollY + dy).coerceIn(1f, h - 2f)
        s.moveTo(scrollX, scrollY)
    }

    fun endScroll() { scroll?.end(); scroll = null }

    /**
     * One finger held down on the external display that follows later moves, built from a chain
     * of continued gesture strokes (each segment is sent once the previous one has played).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private class LiveStroke(x: Float, y: Float) {
        private var current: GestureDescription.StrokeDescription? = null
        private var atX = x
        private var atY = y
        private var toX = x
        private var toY = y
        private var busy = false
        private var ending = false
        private var finished = false
        private var lastAt = SystemClock.uptimeMillis()

        init { send() }

        fun moveTo(x: Float, y: Float) { toX = x; toY = y; if (!busy) send() }
        fun end() { ending = true; if (!busy) send() }

        private fun send() {
            if (finished) return
            val now = SystemClock.uptimeMillis()
            val duration = (now - lastAt).coerceIn(8, 60)
            lastAt = now
            val path = Path().apply { moveTo(atX, atY); lineTo(toX + if (toX == atX && toY == atY) 0.5f else 0f, toY) }
            val last = ending
            val stroke = current?.continueStroke(path, 0, duration, !last)
                ?: GestureDescription.StrokeDescription(path, 0, duration, !last)
            current = stroke
            atX = toX; atY = toY
            busy = true
            if (last) finished = true
            gesture(stroke) {
                busy = false
                if (!finished && (ending || toX != atX || toY != atY)) send()
            }
        }
    }

    /**
     * Back for the app on the external display. Android sends Back to the display that has
     * focus, which stays on the Duo (gesture taps don't move it), so first bring focus over with
     * an invisible screen that closes at once ([FocusActivity]), then press Back.
     */
    fun back() {
        val d = display ?: return
        if (service == null) return
        runCatching {
            app.startActivity(
                Intent(app, FocusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).toBundle(),
            )
        }.onFailure { pressBack() }
    }

    /** Called once [FocusActivity] has handed focus back to the app underneath it. */
    fun pressBack() = main.postDelayed({ service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }, 120)

    // --- Typing ----------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.R)
    private fun focusedField(): AccessibilityNodeInfo? {
        val s = service ?: return null
        val d = display ?: return null
        val wins = runCatching { s.windowsOnAllDisplays.get(d.displayId) }.getOrNull().orEmpty()
        for (w in wins.sortedByDescending { if (it.isFocused) 1 else 0 }) {
            val f = runCatching { w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            if (f != null && f.isEditable) return f
        }
        return null
    }

    /** Text already in the field that has focus on the desktop, so the touchpad keyboard can continue it. */
    fun currentText(): String {
        if (menuOpen) return query
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val f = focusedField() ?: return ""
        return if (f.isShowingHintText) "" else f.text?.toString().orEmpty()
    }

    /** Replaces the text of the focused field on the desktop (or the start menu's search). */
    fun setText(text: String): Boolean {
        if (menuOpen) { query = text; return true }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val f = focusedField() ?: return false
        return f.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }

    fun enter() {
        if (menuOpen) { searchResults().firstOrNull()?.let { launch(it) }; return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        focusedField()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun searchResults(): List<AppInfo> {
        val hidden = DuetApp.instance.prefs.hidden
        val apps = DuetApp.instance.apps.all.filterNot { it.key in hidden }
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { it.label.contains(q, ignoreCase = true) }
            .sortedBy { if (it.label.startsWith(q, ignoreCase = true)) 0 else 1 }
    }

    // --- Running apps --------------------------------------------------------------------

    /** Called by the accessibility service (on its worker thread) when windows change. */
    fun onWindowsChanged(s: AccessibilityService) {
        if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val id = display?.displayId ?: return
        val wins = runCatching { s.windowsOnAllDisplays.get(id) }.getOrNull().orEmpty()
        val appWins = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { w ->
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: return@mapNotNull null
            AppWindow(pkg, Rect().also { w.getBoundsInScreen(it) })
        }
        val all = appWins.map { it.pkg }.distinct()
        // Android's own desktop layer (the "secondary home") isn't an app window you'd manage.
        val visible = appWins.filter { it.pkg != app.packageName && it.pkg !in homePackages }.distinctBy { it.pkg }
        val desktopShowing = app.packageName in all
        // Android brings its own desktop layer forward when a window closes, covering Duet's desktop
        // (and every app). Put Duet's desktop back on top of it.
        val homeShowing = all.any { it in homePackages && it != app.packageName }
        if (homeShowing && !desktopShowing) main.post { raiseDesktopOverHome() }
        val pkgs = all.filter { it != app.packageName }
        main.post {
            windows = visible
            // (Not while it's being closed and reopened at a new size.)
            if (fullscreenPkg != null && fullscreenPkg != reopening && visible.none { it.pkg == fullscreenPkg }) fullscreenPkg = null
            // Only visible windows are reported, so an app that's out of sight isn't necessarily
            // closed. Treat it as closed only when the desktop shows through without "show
            // desktop" having hidden it (e.g. Back closed it). Hidden behind another app, or
            // behind the desktop on purpose, it stays on the taskbar.
            minimized = minimized - pkgs.toSet()
            val closed = if (desktopShowing) running.filter { it !in pkgs && it !in minimized }.toSet() else emptySet()
            // Keep the order apps were opened in, so taskbar icons don't jump around.
            running = running.filterNot { it in closed } + pkgs.filterNot { it in running }
        }
    }

    private var lastRaise = 0L

    private fun raiseDesktopOverHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRaise < 1500) return // never loop if something keeps it from coming forward
        lastRaise = now
        showDesktop()
    }

    /** Apps hidden behind the desktop by "show desktop". */
    private var minimized = emptySet<String>()

    /** Packages that provide a home screen, including the system's desktop layer on external displays. */
    private val homePackages: Set<String> by lazy {
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).flatMap { cat ->
            runCatching {
                app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).map { it.activityInfo.packageName }
            }.getOrDefault(emptyList())
        }.toSet()
    }

    private fun refreshRunning() { ScreenWatcherService.instance?.requestUpdate() }

    // --- Window management -------------------------------------------------------------------

    enum class Snap { LEFT, RIGHT, MAXIMIZE, FULLSCREEN, RESTORE }

    /** Where windows go when maximized: the whole screen above the taskbar. */
    fun workArea(): Rect {
        val d = display ?: return Rect()
        val (w, h) = size(d)
        return Rect(0, 0, w, h - taskbarPx())
    }

    /**
     * The desktop's own window: the whole display, inset a few pixels at the top and sides.
     * Android shifts a new window that shares a corner with an existing one, and every snapped
     * or maximized window would otherwise share the desktop's top corners.
     */
    private fun desktopBounds(): Rect = fullArea().apply { if (!isEmpty) { left += 8; top += 8; right -= 8 } }

    fun fullArea(): Rect = display?.let { size(it) }?.let { (w, h) -> Rect(0, 0, w, h) } ?: Rect()

    fun snapRect(s: Snap, pkg: String): Rect {
        val a = workArea()
        return when (s) {
            Snap.LEFT -> Rect(a.left, a.top, a.centerX(), a.bottom)
            Snap.RIGHT -> Rect(a.centerX(), a.top, a.right, a.bottom)
            Snap.MAXIMIZE -> a
            Snap.FULLSCREEN -> fullArea()
            // Never "restore" to a full-screen or maximized size; fall back to a normal window.
            Snap.RESTORE -> restore[pkg]?.takeIf { !near(it, fullArea()) && !near(it, a) } ?: display?.let { nextWindow(it) } ?: a
        }
    }

    /** Bounds a window had before it was snapped, maximized or made full screen. */
    private val restore = HashMap<String, Rect>()

    /** Which layout a window is in now, if any (compares against the snap rects). */
    fun snapOf(w: AppWindow): Snap? = when {
        w.pkg == fullscreenPkg -> Snap.FULLSCREEN
        else -> listOf(Snap.MAXIMIZE, Snap.LEFT, Snap.RIGHT).firstOrNull { near(w.bounds, snapRect(it, w.pkg)) }
    }

    private fun near(a: Rect, b: Rect): Boolean {
        val tol = 24 * density()
        return kotlin.math.abs(a.left - b.left) < tol && kotlin.math.abs(a.top - b.top) < tol &&
            kotlin.math.abs(a.right - b.right) < tol && kotlin.math.abs(a.bottom - b.bottom) < tol
    }

    fun snap(pkg: String, s: Snap) {
        val w = windows.firstOrNull { it.pkg == pkg }
        if (w != null && snapOf(w) == null && !near(w.bounds, fullArea())) restore[pkg] = Rect(w.bounds)
        val target = snapRect(s, pkg)
        fullscreenPkg = if (s == Snap.FULLSCREEN) pkg else if (fullscreenPkg == pkg) null else fullscreenPkg
        place(pkg, target)
    }

    /**
     * Moves/resizes [pkg]'s window to [bounds].
     *
     * Android only lets an ordinary app choose a window's size when the window *opens*: launch
     * bounds are ignored for an app that's already open, and it doesn't react to drags on
     * window edges or title bars made through accessibility. So Duet closes the window with its
     * own title-bar ✕ (taps do work) and opens the app again at the new size. Most apps come back
     * where they were; anything they don't save is lost, which the setup guide mentions.
     */
    fun place(pkg: String, bounds: Rect) {
        contextMenu = null
        val d = display ?: return
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg == fullscreenPkg && bounds != fullArea()) fullscreenPkg = null
        val reopen = {
            reopening = null
            runCatching {
                app.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(bounds).toBundle())
            }
            main.postDelayed({ refreshRunning() }, 400)
        }
        val w = windows.firstOrNull { it.pkg == pkg }
        if (w == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { reopen(); return }
        val run: () -> Unit = {
            reopening = pkg
            closeWindow(w)
            // Reopen only once the old window is gone: Android nudges a new window away from any
            // window that shares a corner with it, including the one still closing.
            val start = SystemClock.uptimeMillis()
            var goneAt = 0L
            val wait = object : Runnable {
                override fun run() {
                    val now = SystemClock.uptimeMillis()
                    if (goneAt == 0L && windows.none { it.pkg == pkg }) goneAt = now
                    if ((goneAt != 0L && now - goneAt >= REOPEN_SETTLE_MS) || now - start > REOPEN_TIMEOUT_MS) reopen()
                    else { refreshRunning(); main.postDelayed(this, 100) }
                }
            }
            main.postDelayed(wait, 150)
        }
        // The ✕ must be on top to be clicked: bring the window forward first if another covers it.
        if (activeWindow?.pkg != pkg) {
            runCatching { app.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).toBundle()) }
            main.postDelayed(run, 350)
        } else run()
    }

    /** Clicks the ✕ on a window's title bar (Android's desktop-mode caption, top-right corner). */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun closeWindow(w: AppWindow) {
        val dp = density()
        val x = w.bounds.right - CAPTION_CLOSE_DX_DP * dp
        val y = w.bounds.top + CAPTION_Y_DP * dp
        gesture(GestureDescription.StrokeDescription(point(x, y), 0, 40))
    }

    /** Closes [pkg]'s window (its title-bar ✕). */
    fun close(pkg: String) {
        contextMenu = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        windows.firstOrNull { it.pkg == pkg }?.let { closeWindow(it) }
    }

    private const val CAPTION_CLOSE_DX_DP = 22f
    private const val CAPTION_Y_DP = 21f
    /** App being closed and reopened by [place]; its window is briefly missing. */
    private var reopening: String? = null

    private const val REOPEN_SETTLE_MS = 350L
    private const val REOPEN_TIMEOUT_MS = 2500L

    /** End of a drag on a window's move handle or resize corner. */
    fun finishDrag(pkg: String) {
        val r = dragOutline ?: return
        dragOutline = null
        val min = (200 * density()).toInt()
        if (r.width() < min || r.height() < min) return
        restore.remove(pkg)
        place(pkg, r)
    }

    fun appInfo(pkg: String) {
        contextMenu = null
        val d = display ?: return
        runCatching {
            app.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).setLaunchBounds(nextWindow(d)).toBundle(),
            )
        }
    }

    /** Right-click: a long press, which is how Android apps open their context menus. */
    fun rightClick() = longPress()

    // --- Overlays on the external display --------------------------------------------------

    private var overlays: Overlays? = null

    /** Called when the accessibility service connects or disconnects. */
    fun onServiceChanged() = main.post {
        val owner = overlays?.service
        if (owner != null && owner !== service) { overlays?.remove(); overlays = null }
        if (active) attachOverlays()
    }

    private fun attachOverlays() {
        if (overlays != null) return
        val s = service ?: return
        val d = display ?: return
        overlays = runCatching { Overlays(s, d) }.onFailure { android.util.Log.w("DuetDesktop", "overlays", it) }.getOrNull()
    }

    private fun stopShell() {
        endDrag(); endScroll()
        overlays?.remove(); overlays = null
        menuOpen = false; query = ""; running = emptyList()
    }

    fun toggleMenu() {
        menuOpen = !menuOpen
        if (!menuOpen) query = ""
    }

    const val TASKBAR_DP = 52

    /**
     * Everything Duet draws on the external display above the apps: the taskbar (which grows to
     * full screen while a menu is open, so a click outside closes it), the controls on the
     * active window, the outline while dragging a window, and the pointer on top.
     */
    private class Overlays(val service: AccessibilityService, display: Display) {
        private val ctx = service.createDisplayContext(display)
        private val wm = ctx.getSystemService(WindowManager::class.java)
        private val owner = OverlayOwner()
        private val dp = ctx.resources.displayMetrics.density
        private val shell = compose { DuetTheme { DesktopShell(onLayoutChange = { relayout() }) } }
        private val controls = compose { DuetTheme { WindowControls(onLayoutChange = { relayout() }) } }
        private val grip = compose { DuetTheme { ResizeGrip() } }
        private val outline = OutlineView(ctx)
        private val pointer = PointerView(ctx)
        private val shellParams = params(touchable = true)
        private val controlsParams = params(touchable = true)
        private val gripParams = params(touchable = true)
        private val outlineParams = params(touchable = false)
        private val pointerParams = params(touchable = false)

        init {
            owner.start()
            layoutAll()
            wm.addView(outline, outlineParams)
            wm.addView(controls, controlsParams)
            wm.addView(grip, gripParams)
            wm.addView(shell, shellParams)
            val size = (28 * dp).toInt()
            pointerParams.width = size; pointerParams.height = size
            placePointer()
            wm.addView(pointer, pointerParams)
        }

        private fun compose(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent(content)
        }

        private fun params(touchable: Boolean) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        private fun layoutAll() {
            val (w, h) = size(ctx.display!!)
            val bar = taskbarPx()
            val expanded = menuOpen || contextMenu != null
            shellParams.width = w
            shellParams.height = when { expanded -> h; taskbarHidden -> 2; else -> bar }
            shellParams.y = h - shellParams.height
            shellParams.x = 0

            outlineParams.width = w; outlineParams.height = h; outlineParams.x = 0; outlineParams.y = 0

            // Window controls: a pill centred on the active window's top edge, just above it when there's room.
            val a = activeWindow
            val cw = (CONTROLS_W_DP * dp).toInt(); val ch = (CONTROLS_H_DP * dp).toInt()
            if (a == null || expanded) {
                controlsParams.width = 1; controlsParams.height = 1; controlsParams.x = -10; controlsParams.y = -10
            } else {
                controlsParams.width = cw; controlsParams.height = ch
                // On the left end of the window's title bar (Android's own buttons are on the right).
                controlsParams.x = (a.bounds.left + (6 * dp).toInt()).coerceIn(0, w - cw)
                controlsParams.y = a.bounds.top + (2 * dp).toInt()
            }
            // Resize corner: on the active window's bottom-right corner (not in full screen).
            val g = (GRIP_DP * dp).toInt()
            val free = a != null && snapOf(a) != Snap.FULLSCREEN && !expanded
            // Left alone mid-drag: the corner's window is receiving the drag.
            if (gripDragging) Unit
            else if (!free || a == null) {
                gripParams.width = 1; gripParams.height = 1; gripParams.x = -10; gripParams.y = -10
            } else {
                gripParams.width = g; gripParams.height = g
                gripParams.x = (a.bounds.right - g * 2 / 3).coerceAtMost(w - g)
                gripParams.y = (a.bounds.bottom - g * 2 / 3).coerceAtMost(h - g)
            }
        }

        fun relayout() {
            layoutAll()
            runCatching { wm.updateViewLayout(shell, shellParams) }
            runCatching { wm.updateViewLayout(controls, controlsParams) }
            runCatching { wm.updateViewLayout(grip, gripParams) }
            outline.invalidate()
        }

        private fun placePointer() { pointerParams.x = pointerX.toInt(); pointerParams.y = pointerY.toInt() }

        fun movePointer() {
            placePointer()
            runCatching { wm.updateViewLayout(pointer, pointerParams) }
        }

        fun remove() {
            listOf(pointer, shell, grip, controls, outline).forEach { v -> runCatching { wm.removeView(v) } }
            owner.stop()
        }
    }

    /** True while the resize corner is being dragged (its window must stay put until the drag ends). */
    var gripDragging = false

    /** Relays a layout change from Compose state to the overlay windows. */
    fun relayoutOverlays() { overlays?.relayout() }

    const val CONTROLS_W_DP = 216
    const val CONTROLS_H_DP = 34
    const val GRIP_DP = 30

    /** Outline of a window being moved or resized. */
    private class OutlineView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332F80ED }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
        }

        override fun onDraw(canvas: Canvas) {
            val r = dragOutline ?: return
            val rf = android.graphics.RectF(r)
            canvas.drawRoundRect(rf, 10 * d, 10 * d, fill)
            canvas.drawRoundRect(rf, 10 * d, 10 * d, stroke)
        }
    }

    /** A classic arrow pointer, white with a dark outline. Its tip is the view's top-left corner. */
    private class PointerView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val arrow = Path().apply {
            moveTo(1f, 1f); lineTo(1f, 19f); lineTo(5.5f, 15f); lineTo(9f, 22.5f); lineTo(12f, 21f)
            lineTo(8.7f, 14f); lineTo(14.5f, 14f); close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.3f; strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.scale(d, d)
            canvas.drawPath(arrow, fill); canvas.drawPath(arrow, stroke)
            canvas.restore()
        }
    }

    /** Lifecycle for Compose content in a window that has no activity. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

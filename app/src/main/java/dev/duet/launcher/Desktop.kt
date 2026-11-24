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
        if (ext == null) { closeTouchpad++; return }
        val (w, h) = size(ext)
        pointerX = w / 2f; pointerY = h / 2f
        attachOverlays()
        showDesktop()
        if (autoOpen) openTouchpad()
    }

    fun size(d: Display): Pair<Int, Int> {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    fun density(): Float {
        val d = display ?: return 1f
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return m.density
    }

    // --- Launching on the desktop ------------------------------------------------------

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
                ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId).toBundle(),
            )
        }
    }

    private var cascade = 0

    /** Window for the next app: most of the screen above the taskbar, each a little offset from the last. */
    private fun nextWindow(d: Display): Rect {
        val (w, h) = size(d)
        val dp = density()
        val bar = (TASKBAR_DP * dp).toInt()
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
        menuOpen = false; query = ""
        val opts = options() ?: return
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
        val all = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> runCatching { w.root?.packageName?.toString() }.getOrNull() }
            .distinct()
        val desktopShowing = app.packageName in all
        val pkgs = all.filter { it != app.packageName }
        main.post {
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

    /** Apps hidden behind the desktop by "show desktop". */
    private var minimized = emptySet<String>()

    private fun refreshRunning() { ScreenWatcherService.instance?.requestUpdate() }

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

    /** The taskbar (which grows to show the start menu) and the pointer, drawn above all apps. */
    private class Overlays(val service: AccessibilityService, display: Display) {
        private val ctx = service.createDisplayContext(display)
        private val wm = ctx.getSystemService(WindowManager::class.java)
        private val owner = OverlayOwner()
        private val shell = ComposeView(ctx)
        private val pointer = PointerView(ctx)
        private val shellParams = params(touchable = true)
        private val pointerParams = params(touchable = false)
        private var lastMenu = false

        init {
            owner.start()
            shell.setViewTreeLifecycleOwner(owner)
            shell.setViewTreeSavedStateRegistryOwner(owner)
            shell.setContent { DuetTheme { DesktopShell(onMenuChange = { relayout() }) } }
            layoutShell()
            wm.addView(shell, shellParams)
            val size = (28 * ctx.resources.displayMetrics.density).toInt()
            pointerParams.width = size; pointerParams.height = size
            placePointer()
            wm.addView(pointer, pointerParams)
        }

        private fun params(touchable: Boolean) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        private fun layoutShell() {
            val (w, h) = size(ctx.display!!)
            val bar = (TASKBAR_DP * ctx.resources.displayMetrics.density).toInt()
            shellParams.width = w
            // With the start menu open the shell covers the whole screen, so a click outside the menu closes it.
            shellParams.height = if (menuOpen) h else bar
            shellParams.y = if (menuOpen) 0 else h - bar
            lastMenu = menuOpen
        }

        fun relayout() {
            layoutShell()
            runCatching { wm.updateViewLayout(shell, shellParams) }
        }

        private fun placePointer() { pointerParams.x = pointerX.toInt(); pointerParams.y = pointerY.toInt() }

        fun movePointer() {
            placePointer()
            runCatching { wm.updateViewLayout(pointer, pointerParams) }
        }

        fun remove() {
            runCatching { wm.removeView(pointer) }
            runCatching { wm.removeView(shell) }
            owner.stop()
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

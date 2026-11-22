package dev.duet.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Optional "precise detection": reads only the position of on-screen app windows (never
 * their contents) so the launcher knows when an app is covering one of the two screens.
 *
 * This service lives in the launcher's process, so the window query runs on a background
 * thread — on the main thread it stalled the launcher's animations during transitions.
 */
class ScreenWatcherService : AccessibilityService() {
    private lateinit var worker: HandlerThread
    private lateinit var workerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { update() }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("DuetScreenWatcher").apply { start() }
        workerHandler = Handler(worker.looper)
    }

    override fun onServiceConnected() {
        ScreenCoverage.serviceConnected = true
        scheduleUpdate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleUpdate()
        }
    }

    /** Coalesces bursts of events (a transition fires many) into one query. */
    private fun scheduleUpdate() {
        workerHandler.removeCallbacks(updateRunnable)
        workerHandler.postDelayed(updateRunnable, 16)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenCoverage.serviceConnected = false
        ScreenCoverage.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    /** Runs on the worker thread. */
    private fun update() {
        val g = ScreenCoverage.geometry ?: return
        val all = runCatching { windows }.getOrDefault(emptyList())
        // The notification shade (and similar system overlays) replaces the whole window list
        // with one system window, hiding the apps underneath. Nothing has really changed, so
        // keep the current layout until it's gone.
        val overlayUp = all.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                Rect().also { w.getBoundsInScreen(it) }.let { ScreenCoverage.coversMostOf(it, g.first) || ScreenCoverage.coversMostOf(it, g.second) }
        }
        if (overlayUp) return
        val rects = all
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            // The Duo's gesture/recents layer briefly covers whichever screen you swipe Home on.
            .filterNot { it.title?.toString() in IGNORED_TITLES }
            .map { w -> Rect().also { w.getBoundsInScreen(it) } }
            // A window spanning both screens (including our own home window) isn't
            // "an app on one screen" — and asking apps for their package name is a slow IPC.
            .filterNot { b -> if (g.sideBySide) b.width() > g.first.width() else b.height() > g.first.height() }
        mainHandler.post { ScreenCoverage.updateFromWindows(rects) }
    }

    private companion object {
        val IGNORED_TITLES = setOf("SurfaceQuickstep")
    }
}

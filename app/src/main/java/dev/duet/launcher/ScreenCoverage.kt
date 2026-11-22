package dev.duet.launcher

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Which of the Duo's screens is hidden behind another app while the launcher is spanned. */
enum class Covered {
    NONE, FIRST, SECOND;

    fun opposite() = when (this) { FIRST -> SECOND; SECOND -> FIRST; NONE -> NONE }
}

/**
 * Tracks whether an app is sitting on one of the two screens.
 *
 * The Duo keeps the launcher window spanned across both screens even when an app opens on
 * one of them, and Android doesn't tell the launcher it's half hidden. Two sources feed this:
 *  - [ScreenWatcherService] (optional accessibility service): exact app-window bounds.
 *  - Otherwise a heuristic: the Duo opens an app on the screen *opposite* the one you tapped
 *    (verified on a Duo 2), so we remember that screen when we launch something and lose
 *    focus, and undo it when focus comes back on its own.
 */
object ScreenCoverage {
    data class Geometry(val hinge: Rect, val sideBySide: Boolean, val width: Int, val height: Int) {
        val first: Rect get() = if (sideBySide) Rect(0, 0, hinge.left, height) else Rect(0, 0, width, hinge.top)
        val second: Rect get() = if (sideBySide) Rect(hinge.right, 0, width, height) else Rect(0, hinge.bottom, width, height)
        fun screenAt(x: Float, y: Float): Covered =
            if (sideBySide) { if (x < hinge.left) Covered.FIRST else Covered.SECOND }
            else { if (y < hinge.top) Covered.FIRST else Covered.SECOND }
    }

    /** Set by the launcher while it's spanned (window coordinates == screen coordinates). */
    @Volatile var geometry: Geometry? = null
        set(value) {
            field = value
            if (value == null) clear()
        }

    var covered by mutableStateOf(Covered.NONE)
        private set
    var serviceConnected by mutableStateOf(false)

    // --- Precise source ---------------------------------------------------------------

    fun updateFromWindows(appWindows: List<Rect>) {
        val g = geometry ?: return
        val a = appWindows.any { coversMostOf(it, g.first) }
        val b = appWindows.any { coversMostOf(it, g.second) }
        // Both covered: the launcher isn't visible, so nothing to adapt.
        val next = when {
            a && !b -> Covered.FIRST
            b && !a -> Covered.SECOND
            else -> Covered.NONE
        }
        main.removeCallbacks(applyPending)
        if (next == Covered.NONE) {
            // Screen freed up: show both panes again right away.
            covered = Covered.NONE
        } else {
            // Only move once the app window has settled, so short-lived windows during
            // gestures and transitions don't make the layout jump back and forth.
            pending = next
            main.postDelayed(applyPending, SETTLE_MS)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var pending = Covered.NONE
    private val applyPending = Runnable { covered = pending }
    private const val SETTLE_MS = 220L

    fun coversMostOf(window: Rect, screen: Rect): Boolean {
        val overlap = Rect(window)
        if (!overlap.intersect(screen)) return false
        return overlap.width().toLong() * overlap.height() * 2 > screen.width().toLong() * screen.height()
    }

    // --- Heuristic source -------------------------------------------------------------

    private val stack = ArrayDeque<Covered>()
    private var lastTouchAt = 0L
    /** Screen of the most recent touch-down while spanned. */
    var lastTouchScreen = Covered.NONE
        private set
    private var launchAt = 0L
    private var launchScreen = Covered.NONE

    fun onTouchDown(x: Float, y: Float) {
        lastTouchAt = SystemClock.uptimeMillis()
        lastTouchScreen = geometry?.screenAt(x, y) ?: Covered.NONE
    }

    /**
     * Called whenever the launcher starts an app or activity. [target] is the screen it was
     * explicitly sent to (swipe); otherwise it's assumed to open opposite the touch.
     */
    fun noteLaunch(target: Covered? = null) {
        if (SystemClock.uptimeMillis() - lastTouchAt > 1500) return
        launchAt = SystemClock.uptimeMillis()
        launchScreen = target ?: lastTouchScreen.opposite()
    }

    /** Another app took focus: if we just launched it, it opened on the other screen from the tap. */
    fun onLostFocusToApp() {
        if (serviceConnected || geometry == null) return
        if (SystemClock.uptimeMillis() - launchAt > 3000 || launchScreen == Covered.NONE) return
        launchAt = 0
        stack.remove(launchScreen)
        stack.addLast(launchScreen)
        publishStack()
    }

    /**
     * Focus came back. If the user didn't just tap us, the app on top of us closed, so
     * uncover the screen it was on.
     */
    fun onRegainedFocus() {
        if (serviceConnected) return
        if (SystemClock.uptimeMillis() - lastTouchAt < 700) return
        stack.removeLastOrNull()
        publishStack()
    }

    fun clear() {
        main.removeCallbacks(applyPending)
        stack.clear()
        launchAt = 0
        if (!serviceConnected) covered = Covered.NONE
    }

    private fun publishStack() {
        covered = when {
            Covered.FIRST in stack && Covered.SECOND in stack -> Covered.NONE
            else -> stack.lastOrNull() ?: Covered.NONE
        }
    }
}

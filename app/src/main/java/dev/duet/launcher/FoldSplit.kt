package dev.duet.launcher

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Split screen on regular foldables (one continuous inner screen).
 *
 * A tap opens the app full screen as usual. A swipe opens it in split screen on that side,
 * with a condensed launcher ([SplitHomeActivity]) on the other half; apps opened from there
 * replace whatever is in the other half.
 *
 * Android has no public "split this side" call for launchers, but since Android 12L an app in
 * full screen can start an activity with [Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT] to enter split
 * screen: the task already in front keeps one half and the new one takes the other (right or
 * bottom by default). So we bring up the condensed launcher full screen and it launches the
 * app beside itself. Tested on an Oppo Find N5 (ColorOS 15).
 */
object FoldSplit {
    /** Set while the home screen fills an open foldable. */
    @Volatile var unfolded = false
    @Volatile var sideBySide = true

    /** Entering split screen from full screen needs Android 12L. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 32

    /** Whether a swipe should split the screen. */
    val canPick: Boolean get() = unfolded && supported

    private var homeRef: WeakReference<MainActivity>? = null
    private var splitRef: WeakReference<SplitHomeActivity>? = null

    fun attachHome(a: MainActivity) { homeRef = WeakReference(a) }
    fun attachSplit(a: SplitHomeActivity?) { splitRef = a?.let(::WeakReference) }

    /** The condensed launcher, while it's showing in split screen. */
    private val split: SplitHomeActivity?
        get() = splitRef?.get()?.takeIf { !it.isFinishing && it.inSplit() }

    /**
     * Opens [intent] the foldable way if that applies: beside the condensed launcher while
     * it's in split screen, or in a new split when [target] is a swiped-to side. Returns false
     * when it doesn't apply, so the caller launches normally.
     */
    fun route(intent: Intent, target: Covered): Boolean {
        split?.let { host -> return host.openBeside(intent) }
        if (target == Covered.NONE || !canPick) return false
        val from = homeRef?.get()?.takeIf { !it.isFinishing } ?: return false
        return split(from, intent, appFirst = target == Covered.FIRST)
    }

    /** Brings up the condensed launcher full screen, which then opens [intent] beside it. */
    fun split(from: Activity, intent: Intent, appFirst: Boolean): Boolean {
        // The condensed launcher launches the app itself: some phones (ColorOS) pair the new
        // window with the activity that started it rather than the one in front.
        return runCatching {
            from.startActivity(
                Intent(from, SplitHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(SplitHomeActivity.EXTRA_APP, Intent(intent))
                    .putExtra(SplitHomeActivity.EXTRA_APP_FIRST, appFirst)
            )
        }.isSuccess
    }

    /**
     * ColorOS (Oppo, OnePlus, realme) always puts the app opened beside us on the right and
     * ignores requests for the left; the other orderings tried there break the split.
     */
    val canChooseSide: Boolean
        get() = Build.MANUFACTURER.lowercase() !in setOf("oppo", "oneplus", "realme")

    /**
     * Starts [app] beside [from]. Android puts it on the right (bottom); for [appFirst] we also
     * ask for the left (top) half's bounds, which some phones honour.
     */
    internal fun launchBeside(from: Activity, app: Intent, appFirst: Boolean = false): Boolean = runCatching {
        val i = Intent(app).addFlags(ADJACENT)
        val full = from.windowManager.maximumWindowMetrics.bounds
        val opts = if (appFirst) {
            val half = if (sideBySide) android.graphics.Rect(0, 0, full.width() / 2, full.height())
                else android.graphics.Rect(0, 0, full.width(), full.height() / 2)
            android.app.ActivityOptions.makeBasic().setLaunchBounds(half).toBundle()
        } else null
        from.startActivity(i, opts)
    }.isSuccess


    internal const val ADJACENT = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK
}

/**
 * The launcher squeezed into one half of a split screen: clock, favorites and the app list,
 * without system widgets (those stay with the main home screen). It closes itself as soon as
 * it isn't part of a split any more, so it never lingers as a second home screen.
 */
class SplitHomeActivity : MainActivity() {
    override val condensed: Boolean get() = true

    /** True while this window takes up only part of the screen (works where isInMultiWindowMode doesn't, e.g. ColorOS). */
    fun inSplit(): Boolean {
        if (isInMultiWindowMode) return true
        val w = windowManager.currentWindowMetrics.bounds
        val d = getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.let { android.util.DisplayMetrics().also(it::getRealMetrics) }
            ?: return false
        return w.width() < d.widthPixels * 0.85f || w.height() < d.heightPixels * 0.85f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launchPending(intent)
        // If the device didn't enter split screen after all, step aside: the app is full screen.
        window.decorView.postDelayed({ if (!inSplit() && !isFinishing) finishAndRemoveTask() }, 2500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchPending(intent)
    }

    /** Starts the swiped app next to this half. */
    private fun launchPending(intent: Intent) {
        @Suppress("DEPRECATION")
        val app = intent.getParcelableExtra<Intent>(EXTRA_APP) ?: return
        val appFirst = intent.getBooleanExtra(EXTRA_APP_FIRST, false)
        intent.removeExtra(EXTRA_APP)
        window.decorView.post { FoldSplit.launchBeside(this, app, appFirst) }
    }

    /**
     * Opens [app] in the other half. Rather than launching it straight beside this half (which
     * ColorOS turns into a full-screen app when the app is already running), go back home and
     * split again the way a swipe does, which every phone accepts.
     */
    fun openBeside(app: Intent): Boolean {
        val w = windowManager.currentWindowMetrics.bounds
        val onSecond = w.left > 0 || w.top > 0
        return runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_APP, Intent(app))
                    .putExtra(EXTRA_APP_FIRST, onSecond)
            )
            finishAndRemoveTask()
        }.isSuccess
    }

    override fun onStart() {
        super.onStart()
        FoldSplit.attachSplit(this)
    }

    override fun onStop() {
        super.onStop()
        // Home pressed (or split closed): taps on the home screen open full screen again.
        FoldSplit.attachSplit(null)
        if (!inSplit() && !isChangingConfigurations) finishAndRemoveTask()
    }

    override fun onDestroy() {
        FoldSplit.attachSplit(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The split was closed with this half left over: go back to the real home screen.
        window.decorView.postDelayed({ if (!inSplit() && !isFinishing) finishAndRemoveTask() }, 600)
    }

    companion object {
        const val EXTRA_APP = "dev.duet.launcher.extra.APP"
        const val EXTRA_APP_FIRST = "dev.duet.launcher.extra.APP_FIRST"
    }
}

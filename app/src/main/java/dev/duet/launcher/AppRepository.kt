package dev.duet.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

data class AppInfo(
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    /** Stable id: flattened component + user serial (supports work profiles). */
    val key: String,
) {
    val letter: Char = label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
}

class AppRepository(private val ctx: Context) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val userManager = ctx.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activityInfos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val iconCache = LruCache<String, ImageBitmap>(400)
    private var loadedPackPkg: String? = null
    private var loadedPack: IconPack.Loaded? = null

    var all by mutableStateOf<List<AppInfo>>(emptyList())
        private set

    init {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
            override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
            override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        }, Handler(Looper.getMainLooper()))
        reload()
    }

    fun reload() {
        scope.launch { all = withContext(Dispatchers.IO) { load() } }
    }

    private fun load(): List<AppInfo> {
        val result = ArrayList<AppInfo>()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                val key = "${info.componentName.flattenToShortString()}#$serial"
                activityInfos[key] = info
                result += AppInfo(info.label.toString(), info.componentName, user, key)
            }
        }
        val collator = Collator.getInstance()
        return result.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    /** Already-loaded icon, if any — lets the UI draw it on the first frame. */
    fun peekIcon(app: AppInfo, packPkg: String, sizePx: Int): ImageBitmap? = iconCache.get("${app.key}|$packPkg|$sizePx")

    /** Icon from the selected pack if it has one for this app, otherwise the system icon. */
    suspend fun icon(app: AppInfo, packPkg: String, sizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${app.key}|$packPkg|$sizePx"
        iconCache.get(cacheKey)?.let { return@withContext it }
        val themed = pack(packPkg)?.drawableFor(app.component)?.let {
            if (app.user == android.os.Process.myUserHandle()) it
            else ctx.packageManager.getUserBadgedIcon(it, app.user)
        }
        val drawable = themed ?: activityInfos[app.key]?.getBadgedIcon(0) ?: return@withContext null
        val bmp = runCatching { drawable.toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
            ?: return@withContext null
        iconCache.put(cacheKey, bmp)
        bmp
    }

    @Synchronized
    private fun pack(pkg: String): IconPack.Loaded? {
        if (pkg.isEmpty()) return null
        if (pkg != loadedPackPkg) {
            loadedPack = runCatching { IconPack.load(ctx, pkg) }.getOrNull()
            loadedPackPkg = pkg
        }
        return loadedPack
    }

    /**
     * A tap: see [ScreenCoverage.tapTarget]. Always explicit, so an app that's already running
     * doesn't reopen on its old screen and leave the launcher guessing which screen is free.
     */
    fun launch(app: AppInfo) = launchOn(app, ScreenCoverage.tapTarget())

    /**
     * Opens [app] on a chosen screen while the launcher is spanned, by asking for that
     * screen's bounds. Always explicit: the Duo's "opposite the touch" default only applies to
     * new tasks — an app already running reopens on whichever screen it was last on.
     */
    fun launchOn(app: AppInfo, target: Covered) {
        val own = app.user == android.os.Process.myUserHandle()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (own && FoldSplit.route(intent, target)) return
        val opts = ScreenCoverage.launchOptions(target)
        val bounds = ScreenCoverage.boundsOf(target)
        val started = if (own) runCatching { ctx.startActivity(intent, opts) }.isSuccess else false
        if (!started) runCatching { launcherApps.startMainActivity(app.component, app.user, bounds, opts) }
    }

    /**
     * Starts [intent] on [target] (or normally when not spanned), resolving it to one app first.
     * [asApp]: open the handling app's main screen instead. Some apps (Clock's "show alarms")
     * hand the intent on to another activity of theirs, which drops the requested screen.
     */
    fun startOn(intent: Intent, target: Covered, asApp: Boolean = false): Boolean {
        if (intent.component == null) intent.component = pickHandler(intent) ?: return false
        val launch = if (asApp) ctx.packageManager.getLaunchIntentForPackage(intent.component!!.packageName) ?: intent else intent
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (FoldSplit.route(launch, target)) return true
        return runCatching { ctx.startActivity(launch, ScreenCoverage.launchOptions(target)) }.isSuccess
    }

    /**
     * The app that should handle [intent]: the user's default if they set one, otherwise the
     * built-in app. Avoids Android's "Complete action using" picker, which can't be sent to a
     * particular screen.
     */
    private fun pickHandler(intent: Intent): ComponentName? {
        val pm = ctx.packageManager
        val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        if (default != null && default.packageName != "android" && default.exported) {
            return ComponentName(default.packageName, default.name)
        }
        val all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }
        val pick = all.firstOrNull { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: all.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    fun openInfo(app: AppInfo) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun uninstall(app: AppInfo) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

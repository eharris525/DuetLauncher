package dev.duet.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    fun launch(app: AppInfo) {
        ScreenCoverage.noteLaunch()
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
    }

    /**
     * Opens [app] on a chosen screen while the launcher is spanned, by asking for that
     * screen's bounds. Always explicit: the Duo's "opposite the touch" default only applies to
     * new tasks — an app already running reopens on whichever screen it was last on.
     */
    fun launchOn(app: AppInfo, target: Covered) {
        val g = ScreenCoverage.geometry
        if (g == null || target == Covered.NONE) return launch(app)
        ScreenCoverage.noteLaunch(target)
        val bounds = if (target == Covered.FIRST) g.first else g.second
        val opts = ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
        val started = if (app.user == android.os.Process.myUserHandle()) runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                opts,
            )
        }.isSuccess else false
        if (!started) runCatching { launcherApps.startMainActivity(app.component, app.user, bounds, opts) }
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

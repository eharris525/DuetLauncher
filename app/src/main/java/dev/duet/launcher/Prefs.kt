package dev.duet.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class WidgetEntry(val id: Int, val heightDp: Int)

/** How the two screens are used when the launcher is spanned across the hinge. */
enum class DualLayout(val title: String, val summary: String) {
    HOME_AND_LIST(
        "Home + app list",
        "Clock, widgets and favorites on one screen; the full A–Z list always open on the other",
    ),
    WIDGETS_AND_HOME(
        "Widgets + home",
        "Clock and widgets on one screen; favorites with the alphabet bar on the other",
    ),
}

/**
 * SharedPreferences backed by Compose state, so every screen recomposes the moment a
 * setting changes (including the home screen while Settings is open on the other screen).
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("duet", Context.MODE_PRIVATE)

    private fun <T> pref(
        key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        write: SharedPreferences.Editor.(String, T) -> Unit,
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(sp.read(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            sp.edit().apply { write(key, value) }.apply()
        }
    }

    private fun bool(k: String, d: Boolean) = pref(k, d, { a, b -> getBoolean(a, b) }, { a, b -> putBoolean(a, b) })
    private fun int(k: String, d: Int) = pref(k, d, { a, b -> getInt(a, b) }, { a, b -> putInt(a, b) })
    private fun string(k: String, d: String) = pref(k, d, { a, b -> getString(a, b) ?: b }, { a, b -> putString(a, b) })

    /** Package name of the selected icon pack, or "" for system icons. */
    var iconPack by string("icon_pack", "")
    var showIcons by bool("show_icons", true)
    var iconSizeDp by int("icon_size", 36)
    var labelSizeSp by int("label_size", 22)
    var railOnRight by bool("rail_right", true)
    /** An invisible strip on the other edge also scrolls the alphabet bar, for either hand. */
    var railBothSides by bool("rail_both", true)
    var swapScreens by bool("swap_screens", false)
    var widgetsInSingle by bool("widgets_single", true)
    var actionButton by bool("action_button", true)
    var compactRail by bool("compact_rail", true)
    var followUncoveredScreen by bool("follow_uncovered", true)
    private var tapActionName by string("tap_action", DuetAction.SEARCH.name)
    var tapApp by string("tap_app", "")
    private var swipeActionName by string("swipe_action", DuetAction.NOTIFICATIONS.name)
    var swipeApp by string("swipe_app", "")
    private var fontName by string("font", FontChoice.DEFAULT.name)
    private var surfaceStyleName by string("surface_style", SurfaceStyle.LIQUID_GLASS.name)
    /** Darkens the wallpaper behind the home screen, 0–60 %. */
    var wallpaperDim by int("wallpaper_dim", 0)
    private var accentName by string("accent", AccentColor.BLUE.name)
    /** Bumped whenever a new custom font file is imported, so it gets reloaded. */
    var customFontVersion by int("custom_font_version", 0)
    /** Swipe an app sideways to choose which screen it opens on. */
    var swipeToPickScreen by bool("swipe_pick_screen", true)
    /** Re-post chosen apps' notifications so they show on the Glance Bar while closed. */
    var glanceMirror by bool("glance_mirror", false)
    /** "pkg=colour" lines; colour is an ARGB int, or empty to use the app's own colour. */
    private var glanceAppsRaw by string("glance_apps", "")
    /** The first-run setup guide has been completed (or dismissed). */
    var setupDone by bool("setup_done", false)
    /** Desktop on an external display, with the Duo as its touchpad. */
    var desktopMode by bool("desktop_mode", true)
    /** Touchpad pointer speed, 1–10. */
    var touchpadSpeed by int("touchpad_speed", 5)
    /** The AI button on the clock, and the AI app it talks to ("" = the default assistant). */
    var aiButton by bool("ai_button", true)
    var aiApp by string("ai_app", "")
    /** Swipe right on the home/widgets screen to open the Google feed. */
    var googleFeed by bool("google_feed", true)
    private var dualLayoutName by string("dual_layout", DualLayout.HOME_AND_LIST.name)
    private var favoritesRaw by string("favorites", "")
    private var hiddenRaw by string("hidden", "")
    private var widgetsRaw by string("widgets", "")
    private var builtinRaw by string("builtin_widgets", "[]")

    var dualLayout: DualLayout
        get() = DualLayout.entries.firstOrNull { it.name == dualLayoutName } ?: DualLayout.HOME_AND_LIST
        set(v) { dualLayoutName = v.name }

    var surfaceStyle: SurfaceStyle
        get() = SurfaceStyle.entries.firstOrNull { it.name == surfaceStyleName } ?: SurfaceStyle.LIQUID_GLASS
        set(v) { surfaceStyleName = v.name }

    var accent: AccentColor
        get() = AccentColor.entries.firstOrNull { it.name == accentName } ?: AccentColor.BLUE
        set(v) { accentName = v.name }

    var font: FontChoice
        get() = FontChoice.entries.firstOrNull { it.name == fontName } ?: FontChoice.DEFAULT
        set(v) { fontName = v.name }

    var tapAction: DuetAction
        get() = DuetAction.entries.firstOrNull { it.name == tapActionName } ?: DuetAction.SEARCH
        set(v) { tapActionName = v.name }

    var swipeAction: DuetAction
        get() = DuetAction.entries.firstOrNull { it.name == swipeActionName } ?: DuetAction.NOTIFICATIONS
        set(v) { swipeActionName = v.name }

    var favorites: List<String>
        get() = favoritesRaw.split('\n').filter { it.isNotBlank() }
        set(v) { favoritesRaw = v.distinct().joinToString("\n") }

    var hidden: Set<String>
        get() = hiddenRaw.split('\n').filter { it.isNotBlank() }.toSet()
        set(v) { hiddenRaw = v.joinToString("\n") }

    var widgets: List<WidgetEntry>
        get() = widgetsRaw.split(',').mapNotNull { part ->
            val p = part.split(':')
            val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            WidgetEntry(id, p.getOrNull(1)?.toIntOrNull() ?: 180)
        }
        set(v) { widgetsRaw = v.joinToString(",") { "${it.id}:${it.heightDp}" } }

    private val glanceEntries: Map<String, Int?>
        get() = glanceAppsRaw.split('\n').filter { '=' in it }.associate {
            val (pkg, c) = it.split('=', limit = 2); pkg to c.toIntOrNull()
        }

    val glanceApps: Set<String> get() = glanceEntries.keys

    /** Chosen colour, or null for the app's own notification colour. */
    fun glanceColorFor(pkg: String): Int? = glanceEntries[pkg]

    fun setGlanceApp(pkg: String, on: Boolean, color: Int? = glanceEntries[pkg]) {
        val m = glanceEntries.toMutableMap()
        if (on) m[pkg] = color else m.remove(pkg)
        glanceAppsRaw = m.entries.joinToString("\n") { "${it.key}=${it.value ?: ""}" }
    }

    /** Settings of the built-in widgets (their entries in [widgets] have the same negative ids). */
    var builtins: List<BuiltInConfig>
        get() = BuiltInConfig.parseList(builtinRaw)
        set(v) { builtinRaw = BuiltInConfig.encodeList(v) }

    fun builtin(id: Int) = builtins.firstOrNull { it.id == id }
    fun updateBuiltin(c: BuiltInConfig) { builtins = builtins.map { if (it.id == c.id) c else it } }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
    }

    fun moveFavorite(key: String, delta: Int) {
        val list = favorites.toMutableList()
        val i = list.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        favorites = list
    }
}

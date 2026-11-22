package dev.duet.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Reads icon packs that follow the common ADW/Nova convention: an `appfilter.xml`
 * (in res/xml or assets) mapping `ComponentInfo{pkg/cls}` to a drawable name.
 */
object IconPack {
    data class Info(val pkg: String, val label: String)

    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
    )

    @Suppress("DEPRECATION")
    fun installed(ctx: Context): List<Info> {
        val pm = ctx.packageManager
        return ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), 0) }
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg ->
                runCatching { Info(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    class Loaded(private val res: Resources, private val pkg: String, private val map: Map<String, String>) {
        val size get() = map.size

        fun drawableFor(c: ComponentName): Drawable? {
            val name = map["${c.packageName}/${c.className}"] ?: return null
            @Suppress("DiscouragedApi")
            val id = res.getIdentifier(name, "drawable", pkg)
            if (id == 0) return null
            return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
        }
    }

    fun load(ctx: Context, pkg: String): Loaded {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        @Suppress("DiscouragedApi")
        val xmlId = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (xmlId != 0) {
            res.getXml(xmlId)
        } else {
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(ctx.createPackageContext(pkg, 0).assets.open("appfilter.xml"), "UTF-8")
            }
        }
        val map = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val comp = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (comp != null && !drawable.isNullOrBlank()) normalize(comp)?.let { map.putIfAbsent(it, drawable) }
            }
            event = parser.next()
        }
        return Loaded(res, pkg, map)
    }

    private fun normalize(raw: String): String? {
        val s = raw.trim().removePrefix("ComponentInfo{").removeSuffix("}")
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.lastIndex) return null
        val pkg = s.substring(0, slash)
        var cls = s.substring(slash + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        return "$pkg/$cls"
    }
}

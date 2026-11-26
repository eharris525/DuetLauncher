package dev.duet.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

/** Widgets the launcher draws itself, so they follow the chosen style, accent and font. */
enum class BuiltInKind(val title: String, val summary: String, val defaultHeightDp: Int) {
    CALENDAR("Calendar", "This month, with today highlighted", 250),
    AGENDA("Upcoming events", "Your next few days of events", 200),
    WEATHER("Weather", "Current conditions and today's high and low", 150),
    MUSIC("Music", "What's playing, with controls and a seek bar", 112),
    BATTERY("Battery", "Charge level and charging state", 96),
    ALARM("Next alarm", "When your next alarm goes off", 90),
}

/** What tapping a built-in widget does. [DEFAULT] opens the widget's own app. */
const val TAP_DEFAULT = "DEFAULT"

/** One placed built-in widget. Its [id] is negative, to keep it apart from system widget ids. */
data class BuiltInConfig(
    val id: Int,
    val kind: BuiltInKind,
    val tap: String = TAP_DEFAULT,
    val tapApp: String = "",
    val panel: Boolean = true,
    // Weather
    val place: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val fahrenheit: Boolean = Locale.getDefault().country in setOf("US", "LR", "MM"),
    // Calendar / agenda
    val showEvents: Boolean = true,
    val mondayFirst: Boolean = Calendar.getInstance().firstDayOfWeek == Calendar.MONDAY,
    val days: Int = 7,
) {
    val hasPlace: Boolean get() = !lat.isNaN() && !lon.isNaN()

    fun toJson() = JSONObject().apply {
        put("id", id); put("kind", kind.name); put("tap", tap); put("tapApp", tapApp); put("panel", panel)
        put("place", place); if (hasPlace) { put("lat", lat); put("lon", lon) }
        put("f", fahrenheit); put("events", showEvents); put("mon", mondayFirst); put("days", days)
    }

    companion object {
        fun fromJson(o: JSONObject): BuiltInConfig? {
            val kind = BuiltInKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
            val d = BuiltInConfig(o.optInt("id"), kind)
            return d.copy(
                tap = o.optString("tap", TAP_DEFAULT), tapApp = o.optString("tapApp"), panel = o.optBoolean("panel", true),
                place = o.optString("place"), lat = o.optDouble("lat", Double.NaN), lon = o.optDouble("lon", Double.NaN),
                fahrenheit = o.optBoolean("f", d.fahrenheit), showEvents = o.optBoolean("events", true),
                mondayFirst = o.optBoolean("mon", d.mondayFirst), days = o.optInt("days", 7),
            )
        }

        fun parseList(raw: String): List<BuiltInConfig> = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun encodeList(list: List<BuiltInConfig>) = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

// --- Calendar -------------------------------------------------------------------------

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val eventId: Long)

object CalendarData {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Event instances overlapping [from, to), soonest first. Empty without permission. */
    fun events(ctx: Context, from: Long, to: Long): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_ID,
        )
        return runCatching {
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        CalEvent(c.getString(0) ?: "(No title)", c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getInt(4), c.getLong(5))
                    )
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}

// --- Weather (Open-Meteo: free, no account or key) ---------------------------------------

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

data class Weather(
    val temp: Int, val high: Int, val low: Int, val code: Int, val isDay: Boolean, val fetchedAt: Long,
)

object WeatherData {
    private val cache = HashMap<String, Weather>()
    private const val FRESH_MS = 30 * 60_000L

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "DuetLauncher")
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    /** Blocking; call off the main thread. */
    fun search(query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&name=" + URLEncoder.encode(query.trim(), "UTF-8")))
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(
                r.getString("name"),
                listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                r.getDouble("latitude"), r.getDouble("longitude"),
            )
        }
    }

    fun cached(c: BuiltInConfig): Weather? = cache[key(c)]

    /** Blocking; call off the main thread. Uses the cache while it's fresh unless [force]. */
    fun load(c: BuiltInConfig, force: Boolean = false): Weather? {
        if (!c.hasPlace) return null
        val k = key(c)
        cache[k]?.let { if (!force && System.currentTimeMillis() - it.fetchedAt < FRESH_MS) return it }
        val unit = if (c.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val json = JSONObject(get(
            "https://api.open-meteo.com/v1/forecast?latitude=${c.lat}&longitude=${c.lon}" +
                "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto$unit"
        ))
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        return Weather(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
            cur.getInt("weather_code"),
            cur.optInt("is_day", 1) == 1,
            System.currentTimeMillis(),
        ).also { cache[k] = it }
    }

    private fun key(c: BuiltInConfig) = "${c.lat},${c.lon},${c.fahrenheit}"

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorms"
        else -> "—"
    }
}

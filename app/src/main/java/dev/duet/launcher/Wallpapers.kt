package dev.duet.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Built-in wallpapers, one to match each [ThemePreset]. They're drawn in code at the screen's
 * full size (both Duo screens), so they're always sharp and add nothing to the app's size.
 * All are dark enough for Duet's white text.
 */
enum class DuetWallpaper(val title: String, val theme: ThemePreset, private val draw: (Canvas, Int, Int) -> Unit) {
    AURORA("Aurora", ThemePreset.DUET, ::aurora),
    POSTERBOARD("Posterboard", ThemePreset.POSTERBOARD, ::posterboard),
    GLACIER("Glacier", ThemePreset.FROST, ::glacier),
    STARFIELD("Night sky", ThemePreset.MIDNIGHT, ::starfield),
    ECLIPSE("Eclipse", ThemePreset.OLED, ::eclipse),
    SYNTHWAVE("Synthwave", ThemePreset.NEON, ::synthwave),
    DUSK("Dusk", ThemePreset.SUNSET, ::dusk),
    HILLS("Hills", ThemePreset.FOREST, ::hills),
    SCANLINES("Phosphor", ThemePreset.TERMINAL, ::phosphor),
    LINEN("Linen", ThemePreset.PAPER, ::linen);

    fun render(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { draw(Canvas(it), w, h) }

    companion object {
        fun forTheme(t: ThemePreset) = entries.firstOrNull { it.theme == t }

        /** Sets [wp] as the home and lock screen wallpaper. Slow (drawing + saving): call off the main thread. */
        fun apply(ctx: Context, wp: DuetWallpaper): Boolean = runCatching {
            val wm = WallpaperManager.getInstance(ctx)
            val metrics = ctx.resources.displayMetrics
            // The size Android wants (both screens on the Duo), never smaller than the screen.
            val w = max(wm.desiredMinimumWidth, max(metrics.widthPixels, metrics.heightPixels)).coerceIn(720, 4096)
            val h = max(wm.desiredMinimumHeight, min(metrics.widthPixels, metrics.heightPixels)).coerceIn(720, 4096)
            val bmp = wp.render(w, h)
            wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
            bmp.recycle()
        }.isSuccess
    }
}

// --- Helpers ----------------------------------------------------------------------------

private fun linear(c: Canvas, w: Int, h: Int, vararg colors: Int, x1: Float = 0f, y1: Float = h.toFloat()) {
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, x1, y1, colors, null, Shader.TileMode.CLAMP)
    })
}

/** A soft glow: a radial gradient fading to nothing. */
private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
    val inner = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(x, y, r, inner, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    })
}

/** Fine grain, so gradients don't band and flat colours feel like a material. */
private fun grain(c: Canvas, w: Int, h: Int, amount: Int, seed: Int, light: Boolean = true) {
    val rnd = Random(seed)
    val p = Paint()
    val n = w * h / 40
    repeat(n) {
        p.color = if (light) Color.argb(rnd.nextInt(amount), 255, 255, 255) else Color.argb(rnd.nextInt(amount), 0, 0, 0)
        val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
        c.drawRect(x, y, x + 2f, y + 2f, p)
    }
}

private fun vignette(c: Canvas, w: Int, h: Int, strength: Int) {
    val r = max(w, h) * 0.75f
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
        shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(strength, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    })
}

private fun s(w: Int, h: Int) = min(w, h).toFloat()

// --- The wallpapers ---------------------------------------------------------------------

/** Blue and violet light drifting over deep navy, for the original liquid-glass look. */
private fun aurora(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF070B1A.toInt(), 0xFF0D1633.toInt(), 0xFF120B2A.toInt())
    val u = s(w, h)
    glow(c, w * 0.22f, h * 0.30f, u * 0.75f, 0xFF3D7BFF.toInt(), 150)
    glow(c, w * 0.70f, h * 0.20f, u * 0.65f, 0xFF7A4DFF.toInt(), 120)
    glow(c, w * 0.55f, h * 0.85f, u * 0.70f, 0xFF00B3C8.toInt(), 90)
    // Curtains of light: soft vertical streaks along a wave.
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(u * 0.04f, BlurMaskFilter.Blur.NORMAL) }
    for (i in 0 until 60) {
        val x = w * i / 60f
        val top = h * (0.18f + 0.12f * sin(i * 0.25f).toFloat())
        p.shader = LinearGradient(x, top, x, top + h * 0.45f, intArrayOf(Color.argb(70, 120, 200, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(x, top, x + w / 60f * 0.7f, top + h * 0.45f, p)
    }
    grain(c, w, h, 10, 1)
    vignette(c, w, h, 140)
}

/** Warm brown card stock with a few bold printed shapes, to go with the chunky retro cards. */
private fun posterboard(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF2B241D.toInt(), 0xFF1E1914.toInt())
    val u = s(w, h)
    val rnd = Random(7)
    // Paper fibres.
    val fibre = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f }
    repeat(w * h / 900) {
        val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
        val a = rnd.nextFloat() * PI.toFloat()
        val l = 4f + rnd.nextFloat() * 14f
        fibre.color = if (rnd.nextBoolean()) Color.argb(18, 255, 236, 200) else Color.argb(22, 0, 0, 0)
        c.drawLine(x, y, x + cos(a) * l, y + sin(a) * l, fibre)
    }
    // Printed shapes with hard offset shadows, like the theme's cards.
    fun shape(path: Path, color: Int) {
        val shadow = Path(path).apply { offset(u * 0.012f, u * 0.016f) }
        c.drawPath(shadow, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(150, 0, 0, 0) })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.006f; this.color = 0xFF6E6757.toInt() })
    }
    shape(Path().apply { addCircle(w * 0.80f, h * 0.24f, u * 0.17f, Path.Direction.CW) }, 0xFFD35C4D.toInt())
    shape(Path().apply { addRoundRect(w * 0.06f, h * 0.62f, w * 0.30f, h * 0.92f, u * 0.04f, u * 0.04f, Path.Direction.CW) }, 0xFF3A332A.toInt())
    shape(Path().apply { moveTo(w * 0.52f, h * 0.92f); lineTo(w * 0.66f, h * 0.58f); lineTo(w * 0.80f, h * 0.92f); close() }, 0xFFDDD7C2.toInt())
    // Halftone dots fading across the top-left corner.
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 221, 215, 194) }
    val step = u * 0.035f
    var y = step
    while (y < h * 0.45f) {
        var x = step
        while (x < w * 0.45f) {
            val f = 1f - max(x / (w * 0.45f), y / (h * 0.45f))
            if (f > 0) c.drawCircle(x, y, step * 0.32f * f, dot)
            x += step
        }
        y += step
    }
    grain(c, w, h, 14, 2, light = false)
    vignette(c, w, h, 120)
}

/** Pale-blue ice under soft light, dark enough at the edges for white text. */
private fun glacier(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF1B3A52.toInt(), 0xFF0F2436.toInt(), 0xFF0A1824.toInt())
    val u = s(w, h)
    glow(c, w * 0.5f, -h * 0.1f, u * 1.1f, 0xFFBFE6FF.toInt(), 60)
    // Faceted ice: overlapping translucent triangles.
    val rnd = Random(3)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(40) {
        val x = rnd.nextFloat() * w; val y = h * (0.45f + rnd.nextFloat() * 0.6f)
        val r = u * (0.15f + rnd.nextFloat() * 0.3f)
        p.color = Color.argb(8 + rnd.nextInt(12), 200, 235, 255)
        c.drawPath(Path().apply {
            moveTo(x, y - r); lineTo(x + r * (0.6f + rnd.nextFloat()), y + r * 0.6f); lineTo(x - r * (0.6f + rnd.nextFloat()), y + r * 0.5f); close()
        }, p)
    }
    grain(c, w, h, 10, 4)
    vignette(c, w, h, 150)
}

/** A deep purple night with stars and a faint band of the Milky Way. */
private fun starfield(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF0B0718.toInt(), 0xFF1A1035.toInt(), 0xFF0B0718.toInt(), x1 = w.toFloat(), y1 = h.toFloat())
    val u = s(w, h)
    // The galactic band, running corner to corner.
    for (i in 0..12) {
        val t = i / 12f
        glow(c, w * t, h * (0.85f - 0.7f * t), u * 0.35f, 0xFFB79CFF.toInt(), 34)
    }
    val rnd = Random(11)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    repeat(w * h / 2200) {
        val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
        val big = rnd.nextFloat() < 0.04f
        val r = if (big) 2.2f + rnd.nextFloat() * 1.8f else 0.6f + rnd.nextFloat() * 1.2f
        p.color = Color.argb(90 + rnd.nextInt(165), 235, 228, 255)
        c.drawCircle(x, y, r, p)
        if (big) glow(c, x, y, r * 7f, 0xFFDCCFFF.toInt(), 70)
    }
    vignette(c, w, h, 120)
}

/** Pure black with the thin corona of an eclipse; black pixels stay off on OLED screens. */
private fun eclipse(c: Canvas, w: Int, h: Int) {
    c.drawColor(Color.BLACK)
    val u = s(w, h)
    val cx = w * 0.68f; val cy = h * 0.36f; val r = u * 0.16f
    glow(c, cx, cy, r * 2.4f, 0xFFFFE6C2.toInt(), 70)
    c.drawCircle(cx, cy, r * 1.04f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = u * 0.006f; color = Color.argb(220, 255, 240, 215)
        maskFilter = BlurMaskFilter(u * 0.006f, BlurMaskFilter.Blur.NORMAL)
    })
    c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
}

/** A glowing horizon grid under a striped sun, for the neon theme. */
private fun synthwave(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF05040C.toInt(), 0xFF150A2B.toInt(), 0xFF2A0C3A.toInt())
    val u = s(w, h)
    val horizon = h * 0.6f
    // Sun with cut-out stripes.
    val sunR = u * 0.24f
    val sunX = w * 0.5f; val sunY = horizon - sunR * 0.5f
    glow(c, sunX, sunY, sunR * 2.2f, 0xFFFF3FA4.toInt(), 80)
    c.save()
    c.clipRect(0f, 0f, w.toFloat(), horizon)
    c.drawCircle(sunX, sunY, sunR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, sunY - sunR, 0f, sunY + sunR, 0xFFFFD36E.toInt(), 0xFFFF3F8E.toInt(), Shader.TileMode.CLAMP)
    })
    val gap = Paint().apply { color = 0xFF1C0B33.toInt() }
    for (i in 0..6) {
        val y = sunY + sunR * (0.05f + i * 0.14f)
        c.drawRect(sunX - sunR, y, sunX + sunR, y + sunR * 0.025f * (i + 1), gap)
    }
    c.restore()
    // Floor.
    c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), Paint().apply { color = 0xFF07040F.toInt() })
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = u * 0.003f; color = 0xFF2DF5E0.toInt() }
    val glowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = u * 0.012f; color = Color.argb(90, 45, 245, 224); maskFilter = BlurMaskFilter(u * 0.01f, BlurMaskFilter.Blur.NORMAL)
    }
    for (i in -20..20) {
        val x0 = w / 2f + i * w * 0.02f; val x1 = w / 2f + i * w * 0.16f
        c.drawLine(x0, horizon, x1, h.toFloat(), glowLine); c.drawLine(x0, horizon, x1, h.toFloat(), line)
    }
    var k = 0f
    while (true) {
        val y = horizon + (h - horizon) * k * k
        if (y > h) break
        c.drawLine(0f, y, w.toFloat(), y, glowLine); c.drawLine(0f, y, w.toFloat(), y, line)
        k += 0.08f
    }
    c.drawLine(0f, horizon, w.toFloat(), horizon, Paint().apply { strokeWidth = u * 0.004f; color = 0xFFFF5FB8.toInt() })
    vignette(c, w, h, 150)
}

/** Coral sky fading to plum, with soft layered clouds. */
private fun dusk(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF2A1240.toInt(), 0xFF8A2E52.toInt(), 0xFFE0705A.toInt(), 0xFF3A1A2E.toInt())
    val u = s(w, h)
    glow(c, w * 0.3f, h * 0.72f, u * 0.5f, 0xFFFFC27A.toInt(), 140)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(u * 0.03f, BlurMaskFilter.Blur.NORMAL) }
    val rnd = Random(5)
    repeat(9) { i ->
        p.color = Color.argb(60 + rnd.nextInt(40), 60 + i * 8, 20, 50 + i * 4)
        val y = h * (0.25f + i * 0.07f)
        c.drawOval(w * (rnd.nextFloat() - 0.3f), y, w * (0.5f + rnd.nextFloat()), y + h * 0.06f, p)
    }
    // Dark ground so the bottom stays readable, and a light veil so white text stands out.
    linear(c, w, h, Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(200, 20, 8, 20))
    c.drawColor(Color.argb(60, 10, 4, 16))
    grain(c, w, h, 10, 6)
}

/** Misty green hills, one ridge behind another. */
private fun hills(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF0F2420.toInt(), 0xFF1E3A30.toInt(), 0xFF0C1A16.toInt())
    val u = s(w, h)
    glow(c, w * 0.75f, h * 0.2f, u * 0.6f, 0xFFCFF5D8.toInt(), 60)
    val colors = intArrayOf(0xFF2E5A48.toInt(), 0xFF244A3B.toInt(), 0xFF1A3A2E.toInt(), 0xFF122A21.toInt(), 0xFF0B1C16.toInt())
    colors.forEachIndexed { i, col ->
        val base = h * (0.42f + i * 0.12f)
        val path = Path().apply {
            moveTo(0f, h.toFloat()); lineTo(0f, base)
            var x = 0f
            while (x <= w) {
                val y = base - h * 0.06f * sin(x / w * PI.toFloat() * (1.5f + i * 0.4f) + i).toFloat() - h * 0.025f * sin(x / w * 11f + i * 2f).toFloat()
                lineTo(x, y); x += w / 120f
            }
            lineTo(w.toFloat(), h.toFloat()); close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        // Mist between ridges.
        c.drawRect(0f, base - h * 0.05f, w.toFloat(), base + h * 0.05f, Paint().apply {
            shader = LinearGradient(0f, base - h * 0.05f, 0f, base + h * 0.05f, Color.TRANSPARENT, Color.argb(30, 210, 240, 220), Shader.TileMode.MIRROR)
        })
    }
    grain(c, w, h, 8, 8)
}

/** An old green terminal: scanlines, a faint grid and a soft phosphor glow. */
private fun phosphor(c: Canvas, w: Int, h: Int) {
    c.drawColor(0xFF020A04.toInt())
    val u = s(w, h)
    glow(c, w * 0.5f, h * 0.5f, max(w, h) * 0.7f, 0xFF3BFF6A.toInt(), 30)
    val grid = Paint().apply { color = Color.argb(22, 90, 255, 120); strokeWidth = 1.5f }
    val step = u * 0.06f
    var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), grid); x += step }
    var y = 0f; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, grid); y += step }
    val scan = Paint().apply { color = Color.argb(60, 0, 0, 0) }
    y = 0f; while (y < h) { c.drawRect(0f, y, w.toFloat(), y + 2f, scan); y += 5f }
    // A few lines of "output" in the corner.
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 120, 255, 140); textSize = u * 0.022f; typeface = android.graphics.Typeface.MONOSPACE
    }
    listOf("duet@duo:~$ uptime", "  up 42 days, 2 screens", "duet@duo:~$ _").forEachIndexed { i, l ->
        c.drawText(l, w * 0.05f, h * 0.88f + i * text.textSize * 1.5f, text)
    }
    vignette(c, w, h, 200)
}

/** Dark woven linen in warm grey, quiet behind serif text. */
private fun linen(c: Canvas, w: Int, h: Int) {
    linear(c, w, h, 0xFF34302A.toInt(), 0xFF25221E.toInt())
    val rnd = Random(9)
    val p = Paint().apply { strokeWidth = 1f }
    var y = 0f
    while (y < h) { p.color = Color.argb(8 + rnd.nextInt(14), 255, 245, 225); c.drawLine(0f, y, w.toFloat(), y, p); y += 3f }
    var x = 0f
    while (x < w) { p.color = Color.argb(8 + rnd.nextInt(14), 0, 0, 0); c.drawLine(x, 0f, x, h.toFloat(), p); x += 3f }
    glow(c, w * 0.15f, h * 0.1f, s(w, h) * 0.9f, 0xFFFFE9C4.toInt(), 30)
    grain(c, w, h, 12, 10, light = false)
    vignette(c, w, h, 130)
}

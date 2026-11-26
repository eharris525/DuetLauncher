package dev.duet.launcher

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/**
 * Icon looks generated from each app's own icon, one per theme, like an icon pack made on the
 * phone. Adaptive icons give their foreground layer (the symbol without its background), which
 * is redrawn in the style's colours on a new tile; older icons are recoloured whole.
 */
enum class IconStyle(val title: String, val summary: String) {
    ORIGINAL("Original", "The apps' own icons"),
    POSTER("Posterboard", "Symbols printed in each app's colour on cream cards"),
    FROST("Frost", "White symbols on glass tinted with each app's colour"),
    MIDNIGHT("Midnight", "Pale symbols on night circles with a hint of the app's colour"),
    OLED("OLED", "Symbols in each app's colour on pure black"),
    NEON("Neon", "A neon ring and glow in each app's colour"),
    SUNSET("Sunset", "White symbols on warm gradients from each app's colour"),
    FOREST("Forest", "Leafy tints of each app's colour on deep green"),
    TERMINAL("Terminal", "Phosphor glow in each app's colour, with scanlines"),
    PAPER("Paper", "The original icons in faded sepia");

    /** Draws [d] at [size] px in this style, keeping the app's own main colour in it. */
    fun render(d: Drawable, size: Int): Bitmap {
        if (this == ORIGINAL) return d.toBitmap(size, size)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        // The app's colour, eased into the style's range so every icon still fits the theme.
        val app = mainColor(d)
        when (this) {
            POSTER -> {
                // Cream card, symbol printed in the app's colour as a darker ink.
                val r = RectF(s * 0.06f, s * 0.06f, s * 0.88f, s * 0.88f)
                val rad = s * 0.18f
                c.drawRoundRect(RectF(r).apply { offset(s * 0.06f, s * 0.06f) }, rad, rad, fill(Color.argb(170, 0, 0, 0)))
                c.drawRoundRect(r, rad, rad, fill(mix(0xFFDDD7C2.toInt(), app, 0.12f)))
                c.drawRoundRect(r, rad, rad, stroke(0xFF4A4438.toInt(), s * 0.04f))
                glyph(c, d, r, duotone(mix(app, 0xFF1B1811.toInt(), 0.7f), mix(app, 0xFF1B1811.toInt(), 0.15f)), inset = 0.2f)
            }
            FROST -> {
                // Glass tinted by the app's colour, white symbol.
                circle(c, s, fill(withAlpha(mix(app, Color.WHITE, 0.45f), 120)))
                c.drawCircle(s / 2, s / 2, s * 0.46f, stroke(Color.argb(170, 255, 255, 255), s * 0.025f))
                glyph(c, d, RectF(0f, 0f, s, s), solid(Color.WHITE))
            }
            MIDNIGHT -> {
                // Night-blue disc with a hint of the app's colour; symbol in a pale version of it.
                circle(c, s, fill(mix(0xFF1C1633.toInt(), app, 0.22f)))
                c.drawCircle(s / 2, s / 2, s * 0.46f, stroke(mix(0xFF3A2F63.toInt(), app, 0.35f), s * 0.02f))
                glyph(c, d, RectF(0f, 0f, s, s), duotone(mix(app, 0xFF8E7CC3.toInt(), 0.45f), mix(app, Color.WHITE, 0.7f)))
            }
            OLED -> {
                // Black, with the symbol in the app's own colour (brightened) and a faint ring of it.
                circle(c, s, fill(Color.BLACK))
                c.drawCircle(s / 2, s / 2, s * 0.465f, stroke(withAlpha(app, 90), s * 0.015f))
                glyph(c, d, RectF(0f, 0f, s, s), solid(vivid(app, 0.75f)))
            }
            NEON -> {
                // A neon ring and glow in the app's colour.
                val glowColor = vivid(app, 0.6f)
                circle(c, s, fill(0xFF07090D.toInt()))
                c.drawCircle(s / 2, s / 2, s * 0.44f, stroke(withAlpha(glowColor, 140), s * 0.07f).apply {
                    maskFilter = BlurMaskFilter(s * 0.05f, BlurMaskFilter.Blur.NORMAL)
                })
                c.drawCircle(s / 2, s / 2, s * 0.44f, stroke(glowColor, s * 0.025f))
                glyph(c, d, RectF(0f, 0f, s, s), solid(mix(glowColor, Color.WHITE, 0.6f)), glow = withAlpha(glowColor, 200))
            }
            SUNSET -> {
                // A warm gradient that starts from the app's colour.
                val r = RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.96f)
                c.drawRoundRect(r, s * 0.28f, s * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, s, s, mix(app, 0xFFFF9E80.toInt(), 0.45f), mix(app, 0xFF7A2E6A.toInt(), 0.7f), Shader.TileMode.CLAMP)
                })
                glyph(c, d, r, solid(Color.WHITE))
            }
            FOREST -> {
                // Deep green, nudged towards the app's colour; symbol in a light leafy tint of it.
                circle(c, s, fill(mix(0xFF173A2E.toInt(), app, 0.25f)))
                glyph(c, d, RectF(0f, 0f, s, s), duotone(mix(app, 0xFF6CCB9A.toInt(), 0.55f), mix(app, 0xFFD6F7E4.toInt(), 0.75f)))
            }
            TERMINAL -> {
                // Phosphor in the app's colour: green apps glow green, red ones amber-red, and so on.
                val phosphor = vivid(app, 0.7f)
                val r = RectF(s * 0.06f, s * 0.06f, s * 0.94f, s * 0.94f)
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, fill(0xFF020A04.toInt()))
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, stroke(phosphor, s * 0.03f))
                glyph(c, d, r, solid(mix(phosphor, Color.WHITE, 0.3f)), glow = withAlpha(phosphor, 150))
                val scan = fill(Color.argb(70, 0, 0, 0))
                var y = r.top; while (y < r.bottom) { c.drawRect(r.left, y, r.right, y + s * 0.012f, scan); y += s * 0.04f }
            }
            PAPER -> {
                c.drawBitmap(d.toBitmap(size, size), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = sepia() })
            }
            ORIGINAL -> {}
        }
        return out
    }

    private companion object {
        /**
         * The icon's main colour: its most common strongly coloured hue (so a white logo on a red
         * tile gives red), or its average colour when it's mostly grey.
         */
        fun mainColor(d: Drawable): Int {
            val n = 24
            val b = d.toBitmap(n, n)
            val buckets = FloatArray(12); val sums = Array(12) { FloatArray(3) }
            var gr = 0f; var gg = 0f; var gb = 0f; var gn = 0f
            val hsv = FloatArray(3)
            for (y in 0 until n) for (x in 0 until n) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 200) continue
                gr += Color.red(p); gg += Color.green(p); gb += Color.blue(p); gn++
                Color.colorToHSV(p, hsv)
                val weight = hsv[1] * hsv[2]
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
                val i = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                buckets[i] += weight
                sums[i][0] += Color.red(p) * weight; sums[i][1] += Color.green(p) * weight; sums[i][2] += Color.blue(p) * weight
            }
            val best = buckets.indices.maxBy { buckets[it] }
            if (buckets[best] > 2f) {
                val w = buckets[best]
                return Color.rgb((sums[best][0] / w).toInt(), (sums[best][1] / w).toInt(), (sums[best][2] / w).toInt())
            }
            return if (gn > 0) Color.rgb((gr / gn).toInt(), (gg / gn).toInt(), (gb / gn).toInt()) else Color.GRAY
        }

        /** [a] blended towards [b] by [t]. */
        fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        /** [c] at full brightness and at least [minSat] saturation, for glowing styles (grey stays a soft white). */
        fun vivid(c: Int, minSat: Float): Int {
            val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.12f) return 0xFFE8EEF2.toInt()
            hsv[1] = maxOf(hsv[1], minSat); hsv[2] = 1f
            return Color.HSVToColor(hsv)
        }

        fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = w }
        fun circle(c: Canvas, s: Float, p: Paint) = c.drawCircle(s / 2, s / 2, s * 0.47f, p)

        /**
         * The symbol in one [color]; darker parts of it fade towards [minAlpha], so a white mark
         * on a coloured shape (YouTube's triangle, say) still shows inside the silhouette.
         */
        fun solid(color: Int, minAlpha: Float = 0.6f): ColorMatrixColorFilter {
            val k = (1f - minAlpha) * Color.alpha(color) / 255f
            return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f, 0f, 0f, 0f, Color.red(color).toFloat(),
                0f, 0f, 0f, 0f, Color.green(color).toFloat(),
                0f, 0f, 0f, 0f, Color.blue(color).toFloat(),
                0.299f * k, 0.587f * k, 0.114f * k, minAlpha * Color.alpha(color) / 255f, 0f,
            )))
        }

        /** Brightness mapped from [dark] to [light], so details inside the symbol survive. */
        fun duotone(dark: Int, light: Int): ColorMatrixColorFilter {
            fun row(d: Int, l: Int): FloatArray {
                val k = (l - d) / 255f
                return floatArrayOf(0.299f * k, 0.587f * k, 0.114f * k, 0f, d.toFloat())
            }
            return ColorMatrixColorFilter(ColorMatrix(
                row(Color.red(dark), Color.red(light)) + row(Color.green(dark), Color.green(light)) +
                    row(Color.blue(dark), Color.blue(light)) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
            ))
        }

        /**
         * Keeps just the symbol of an icon that has its background drawn in (icon-pack icons,
         * older apps): samples the background colour near the tile's edges and fades out every
         * pixel close to it. Icons that are already just a symbol (transparent around it) are left alone.
         */
        fun isolate(b: Bitmap) {
            val w = b.width; val h = b.height
            val probes = listOf(0.2f to 0.5f, 0.8f to 0.5f, 0.5f to 0.2f, 0.5f to 0.8f, 0.24f to 0.24f, 0.76f to 0.76f)
                .map { (fx, fy) -> b.getPixel((w * fx).toInt().coerceIn(0, w - 1), (h * fy).toInt().coerceIn(0, h - 1)) }
                .filter { Color.alpha(it) > 200 }
            if (probes.size < 4) return
            // The most common probe colour is the background.
            val bg = probes.maxBy { p -> probes.count { dist(it, p) < 40f } }
            if (probes.count { dist(it, bg) < 40f } < 3) return
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                val a = Color.alpha(c)
                if (a == 0) continue
                val keep = ((dist(c, bg) - 28f) / 70f).coerceIn(0f, 1f)
                px[i] = Color.argb((a * keep).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
        }

        private fun dist(a: Int, b: Int): Float {
            val dr = (Color.red(a) - Color.red(b)).toFloat(); val dg = (Color.green(a) - Color.green(b)).toFloat(); val db = (Color.blue(a) - Color.blue(b)).toFloat()
            return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
        }

        fun sepia() = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.15f)
            postConcat(ColorMatrix(floatArrayOf(
                1.0f, 0f, 0f, 0f, 22f,
                0f, 0.95f, 0f, 0f, 8f,
                0f, 0f, 0.82f, 0f, -12f,
                0f, 0f, 0f, 1f, 0f,
            )))
        })

        /**
         * The app's symbol drawn into [box] through [filter]: an adaptive icon's foreground layer
         * (its safe zone, so it fills the tile), or the whole icon for older apps.
         */
        fun glyph(c: Canvas, d: Drawable, box: RectF, filter: ColorMatrixColorFilter, inset: Float = 0.16f, glow: Int? = null) {
            val size = (box.width() * (1f - inset * 2)).toInt().coerceAtLeast(8)
            val symRaw: Bitmap = if (d is AdaptiveIconDrawable && d.foreground != null) {
                // Layers are 108 units with the visible part in the middle 72: draw at 1.5x and crop.
                val big = (size * 1.5f).toInt()
                val full = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888)
                d.foreground.setBounds(0, 0, big, big)
                d.foreground.draw(Canvas(full))
                val off = (big - size) / 2
                Bitmap.createBitmap(full, off, off, size, size)
            } else d.toBitmap(size, size)
            // A private, writable copy: toBitmap() can hand back the drawable's own bitmap.
            val sym = symRaw.copy(Bitmap.Config.ARGB_8888, true)
            isolate(sym)
            val x = box.centerX() - size / 2f; val y = box.centerY() - size / 2f
            if (glow != null) {
                // A soft halo the shape of the symbol.
                val offset = IntArray(2)
                val halo = sym.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(size * 0.08f, BlurMaskFilter.Blur.NORMAL) }, offset)
                c.drawBitmap(halo, x + offset[0], y + offset[1], Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glow })
            }
            c.drawBitmap(sym, x, y, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter })
        }
    }
}


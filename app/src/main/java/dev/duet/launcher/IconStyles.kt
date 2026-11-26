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
    POSTER("Posterboard", "Ink-printed symbols on cream cards with a hard shadow"),
    FROST("Frost", "White symbols on frosted glass circles"),
    MIDNIGHT("Midnight", "Lavender symbols on deep night circles"),
    OLED("OLED", "White symbols on pure black"),
    NEON("Neon", "Glowing teal symbols in a neon ring"),
    SUNSET("Sunset", "White symbols on warm gradient tiles"),
    FOREST("Forest", "Mint symbols on deep green"),
    TERMINAL("Terminal", "Green phosphor symbols in a terminal box"),
    PAPER("Paper", "The original icons in faded sepia");

    /** Draws [d] at [size] px in this style. */
    fun render(d: Drawable, size: Int): Bitmap {
        if (this == ORIGINAL) return d.toBitmap(size, size)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        when (this) {
            POSTER -> {
                val r = RectF(s * 0.06f, s * 0.06f, s * 0.88f, s * 0.88f)
                val rad = s * 0.18f
                c.drawRoundRect(RectF(r).apply { offset(s * 0.06f, s * 0.06f) }, rad, rad, fill(Color.argb(170, 0, 0, 0)))
                c.drawRoundRect(r, rad, rad, fill(0xFFDDD7C2.toInt()))
                c.drawRoundRect(r, rad, rad, stroke(0xFF4A4438.toInt(), s * 0.04f))
                glyph(c, d, r, duotone(0xFF1B1811.toInt(), 0xFF8C3A30.toInt()), inset = 0.2f)
            }
            FROST -> {
                circle(c, s, fill(Color.argb(70, 255, 255, 255)))
                c.drawCircle(s / 2, s / 2, s * 0.46f, stroke(Color.argb(150, 255, 255, 255), s * 0.025f))
                glyph(c, d, RectF(0f, 0f, s, s), solid(Color.WHITE))
            }
            MIDNIGHT -> {
                circle(c, s, fill(0xFF1C1633.toInt()))
                c.drawCircle(s / 2, s / 2, s * 0.46f, stroke(0xFF3A2F63.toInt(), s * 0.02f))
                glyph(c, d, RectF(0f, 0f, s, s), duotone(0xFF8E7CC3.toInt(), 0xFFE6DCFF.toInt()))
            }
            OLED -> {
                circle(c, s, fill(Color.BLACK))
                c.drawCircle(s / 2, s / 2, s * 0.465f, stroke(Color.argb(60, 255, 255, 255), s * 0.015f))
                glyph(c, d, RectF(0f, 0f, s, s), solid(Color.WHITE))
            }
            NEON -> {
                circle(c, s, fill(0xFF07090D.toInt()))
                val teal = 0xFF2DF5E0.toInt()
                c.drawCircle(s / 2, s / 2, s * 0.44f, stroke(Color.argb(140, 45, 245, 224), s * 0.07f).apply {
                    maskFilter = BlurMaskFilter(s * 0.05f, BlurMaskFilter.Blur.NORMAL)
                })
                c.drawCircle(s / 2, s / 2, s * 0.44f, stroke(teal, s * 0.025f))
                val box = RectF(0f, 0f, s, s)
                glyph(c, d, box, solid(0xFFB9FFF6.toInt()), glow = Color.argb(200, 45, 245, 224))
            }
            SUNSET -> {
                val r = RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.96f)
                c.drawRoundRect(r, s * 0.28f, s * 0.28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, s, s, 0xFFFF9E80.toInt(), 0xFF7A2E6A.toInt(), Shader.TileMode.CLAMP)
                })
                glyph(c, d, r, solid(Color.WHITE))
            }
            FOREST -> {
                circle(c, s, fill(0xFF173A2E.toInt()))
                glyph(c, d, RectF(0f, 0f, s, s), duotone(0xFF6CCB9A.toInt(), 0xFFD6F7E4.toInt()))
            }
            TERMINAL -> {
                val r = RectF(s * 0.06f, s * 0.06f, s * 0.94f, s * 0.94f)
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, fill(0xFF020A04.toInt()))
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, stroke(0xFF3BFF6A.toInt(), s * 0.03f))
                glyph(c, d, r, solid(0xFF7DFF98.toInt()), glow = Color.argb(150, 59, 255, 106))
                // Scanlines.
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


package dev.duet.launcher

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

private val Scheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1A2E),
    surface = Color(0xFF1B1C20),
    background = Color(0xFF121316),
)

/** Fonts that ship with Android, plus a font file the user picks ([CUSTOM]). */
enum class FontChoice(val title: String, private val systemName: String?) {
    DEFAULT("System default", null),
    LIGHT("Light", "sans-serif-light"),
    THIN("Thin", "sans-serif-thin"),
    MEDIUM("Medium", "sans-serif-medium"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    SERIF("Serif", "serif"),
    MONO("Monospace", "monospace"),
    CASUAL("Casual", "casual"),
    CURSIVE("Cursive", "cursive"),
    SMALL_CAPS("Small caps", "sans-serif-smallcaps"),
    CUSTOM("Custom font file", null);

    fun family(ctx: Context): FontFamily? = when {
        this == CUSTOM -> customFontFile(ctx).takeIf { it.exists() }?.let { f ->
            runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { FontFamily(Font(f)) }
        }
        systemName != null -> FontFamily(Typeface.create(systemName, Typeface.NORMAL))
        else -> null
    }

    companion object {
        fun customFontFile(ctx: Context) = File(ctx.filesDir, "custom_font")
    }
}

val LocalDuetFont = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun DuetTheme(content: @Composable () -> Unit) {
    val prefs = DuetApp.instance.prefs
    val ctx = LocalContext.current
    val choice = prefs.font
    val version = prefs.customFontVersion
    val family = remember(choice, version) { choice.family(ctx) }
    val typography = remember(family) { family?.let { Typography().withFamily(it) } ?: Typography() }
    val accentColor = prefs.accent.color
    val scheme = remember(accentColor) { Scheme.copy(primary = accentColor) }
    CompositionLocalProvider(LocalDuetFont provides family) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

private val OnWallpaperBase = TextStyle(
    color = Color.White,
    shadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f),
)

/** White text with a soft shadow so it stays readable on any wallpaper, in the chosen font. */
val OnWallpaper: TextStyle
    @Composable @ReadOnlyComposable
    get() = LocalDuetFont.current?.let { OnWallpaperBase.copy(fontFamily = it) } ?: OnWallpaperBase

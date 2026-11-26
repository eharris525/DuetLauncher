package dev.duet.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Process

/**
 * The AI button on the clock: starts a voice chat with the AI app the user picked.
 *
 * Android has no common "start voice chat" intent, so this tries, in order:
 *  1. the app's own launcher shortcut for voice (ChatGPT "Voice", Gemini "Talk Live", Copilot
 *     "Voice"...): Duet is the home app, so it may start any app's shortcuts;
 *  2. the voice-command intent, aimed at that app;
 *  3. the assist intent, aimed at that app;
 *  4. just opening the app.
 * With no app chosen, it asks the phone's default digital assistant to listen.
 */
object Assistant {
    data class Choice(val pkg: String, val label: String)

    /** [Prefs.aiApp] value for the voice chat with Claude built into Duet. */
    const val IN_DUET = "duet:claude"

    /** Well-known AI apps, shown first in the picker when installed. */
    private val known = listOf(
        "com.openai.chatgpt" to "ChatGPT",
        "com.google.android.apps.bard" to "Gemini",
        "com.anthropic.claude" to "Claude",
        "com.microsoft.copilot" to "Microsoft Copilot",
        "ai.perplexity.app.android" to "Perplexity",
        "ai.x.grok" to "Grok",
        "com.deepseek.chat" to "DeepSeek",
        "com.amazon.dee.app" to "Alexa",
        "com.google.android.googlequicksearchbox" to "Google Assistant",
    )

    private val voiceWords = listOf("voice", "talk", "live", "speak", "conversation", "call")

    /** Installed AI apps: the known ones, plus anything that can act as an assistant. */
    fun installed(ctx: Context): List<Choice> {
        val pm = ctx.packageManager
        fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        val out = LinkedHashMap<String, Choice>()
        for ((pkg, name) in known) if (label(pkg) != null) out[pkg] = Choice(pkg, name)
        for (action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
            pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY).forEach {
                val pkg = it.activityInfo.packageName
                if (pkg !in out && pkg != ctx.packageName) label(pkg)?.let { l -> out[pkg] = Choice(pkg, l) }
            }
        }
        return out.values.toList()
    }

    fun labelOf(ctx: Context, pkg: String): String =
        if (pkg.isEmpty()) "Default assistant"
        else if (pkg == IN_DUET) "Claude, right on the home screen"
        else known.firstOrNull { it.first == pkg }?.second
            ?: runCatching { ctx.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

    /** Starts a voice chat with [pkg] ("" = the default assistant) on the screen [target]. */
    fun startVoice(ctx: Context, pkg: String, target: Covered) {
        val opts = ScreenCoverage.launchOptions(target)
        fun start(i: Intent) = runCatching {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        }.isSuccess

        if (pkg.isEmpty()) {
            if (start(Intent(Intent.ACTION_VOICE_COMMAND))) return
            start(Intent(Intent.ACTION_ASSIST)); return
        }
        voiceShortcut(ctx, pkg)?.let { s ->
            val la = ctx.getSystemService(LauncherApps::class.java)
            if (runCatching { la.startShortcut(s, null, opts) }.isSuccess) return
        }
        if (start(Intent(Intent.ACTION_VOICE_COMMAND).setPackage(pkg))) return
        if (start(Intent(Intent.ACTION_ASSIST).setPackage(pkg))) return
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { start(it) }
    }

    private fun voiceShortcut(ctx: Context, pkg: String): ShortcutInfo? {
        val la = ctx.getSystemService(LauncherApps::class.java)
        if (!la.hasShortcutHostPermission()) return null
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
        val list = runCatching { la.getShortcuts(q, Process.myUserHandle()) }.getOrNull().orEmpty()
        return list.filter { it.isEnabled }.firstOrNull { s ->
            val text = "${s.id} ${s.shortLabel ?: ""} ${s.longLabel ?: ""}".lowercase()
            voiceWords.any { it in text }
        }
    }
}

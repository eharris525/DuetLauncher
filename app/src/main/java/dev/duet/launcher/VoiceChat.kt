package dev.duet.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.MessageCreateParams
import java.util.concurrent.Executors

/**
 * Hands-free voice chat with Claude, right on the home screen: listen (Android's speech
 * recognizer) -> ask Claude (streamed) -> read the reply aloud sentence by sentence (Android
 * text-to-speech) -> listen again, until the panel is closed.
 */
class VoiceChat(private val ctx: Context) {
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }
    data class Turn(val fromUser: Boolean, val text: String)

    var phase by mutableStateOf(Phase.IDLE)
        private set
    /** What's being heard right now, before the sentence is finished. */
    var partial by mutableStateOf("")
        private set
    /** Microphone loudness 0..1, for the orb. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val turns = mutableStateListOf<Turn>()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var closed = false
    /** Utterance ids still to be spoken for the current reply; when it empties, listen again. */
    private val pendingSpeech = mutableSetOf<String>()
    private var replyDone = false
    private var generation = 0

    fun start() {
        closed = false
        tts = TextToSpeech(ctx) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) = main.post { spoken(id) }.let { }
                @Deprecated("Deprecated in Java") override fun onError(id: String) = main.post { spoken(id) }.let { }
            })
        }
        listen()
    }

    fun close() {
        closed = true
        generation++
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        phase = Phase.IDLE
    }

    /** Tap on the orb: interrupt whatever is happening and listen. */
    fun tapOrb() {
        when (phase) {
            Phase.LISTENING -> { recognizer?.stopListening() }
            else -> { generation++; tts?.stop(); pendingSpeech.clear(); listen() }
        }
    }

    fun listen() {
        if (closed) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { fail("Speech recognition isn't available on this phone."); return }
        error = null
        partial = ""
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { phase = Phase.LISTENING }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(code: Int) {
                level = 0f
                if (closed) return
                when (code) {
                    // Heard nothing: go quiet rather than nag; a tap on the orb listens again.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> phase = Phase.IDLE
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Duet needs microphone access for voice chat.")
                    else -> fail("Couldn't hear that (error $code). Tap to try again.")
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = ""
                if (text.isBlank()) { phase = Phase.IDLE; return }
                ask(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        )
        phase = Phase.LISTENING
    }

    /** Typed instead of spoken (the keyboard button). */
    fun send(text: String) {
        if (text.isBlank()) return
        recognizer?.cancel()
        generation++; tts?.stop(); pendingSpeech.clear()
        ask(text.trim())
    }

    private fun ask(text: String) {
        turns += Turn(true, text)
        turns += Turn(false, "")
        val replyIndex = turns.lastIndex
        phase = Phase.THINKING
        replyDone = false
        pendingSpeech.clear()
        val gen = ++generation
        val history = turns.dropLast(1).toList()
        val key = DuetApp.instance.prefs.claudeKey
        io.execute {
            val spokenUpTo = intArrayOf(0)
            val sb = StringBuilder()
            fun speakReady(final: Boolean) {
                // Speak each finished sentence as soon as it arrives, so the answer starts quickly.
                val s = sb.toString()
                var end = spokenUpTo[0]
                for (i in spokenUpTo[0] until s.length) {
                    if (s[i] in ".!?\n" && (i + 1 == s.length || s[i + 1].isWhitespace())) end = i + 1
                }
                if (final) end = s.length
                if (end > spokenUpTo[0]) {
                    val chunk = s.substring(spokenUpTo[0], end).trim()
                    spokenUpTo[0] = end
                    if (chunk.isNotEmpty()) main.post { if (gen == generation) say(chunk) }
                }
            }
            try {
                ClaudeVoice.stream(key, history) { delta ->
                    sb.append(delta)
                    val now = sb.toString()
                    main.post { if (gen == generation && replyIndex < turns.size) turns[replyIndex] = Turn(false, now) }
                    speakReady(false)
                }
                if (sb.isBlank()) {
                    sb.append("Sorry, I can't help with that one.")
                    main.post { if (gen == generation && replyIndex < turns.size) turns[replyIndex] = Turn(false, sb.toString()) }
                }
                speakReady(true)
                main.post { if (gen == generation) { replyDone = true; if (pendingSpeech.isEmpty()) afterSpeaking() } }
            } catch (e: UnauthorizedException) {
                main.post { if (gen == generation) failReply(replyIndex, "Claude didn't accept the API key. Check it in Duet Settings → AI button.") }
            } catch (e: PermissionDeniedException) {
                main.post { if (gen == generation) failReply(replyIndex, "This API key isn't allowed to use Claude.") }
            } catch (e: RateLimitException) {
                main.post { if (gen == generation) failReply(replyIndex, "Claude is busy right now (rate limited). Try again in a moment.") }
            } catch (e: AnthropicServiceException) {
                main.post { if (gen == generation) failReply(replyIndex, "Claude returned an error (${e.statusCode()}). Try again.") }
            } catch (e: Exception) {
                main.post { if (gen == generation) failReply(replyIndex, "Couldn't reach Claude. Check your internet connection.") }
            }
        }
    }

    private fun say(text: String) {
        val t = tts
        if (t == null || !ttsReady) return
        val id = "u${System.nanoTime()}"
        pendingSpeech += id
        phase = Phase.SPEAKING
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun spoken(id: String) {
        pendingSpeech -= id
        if (pendingSpeech.isEmpty() && replyDone) afterSpeaking()
    }

    /** Reply finished and read out: hand the turn back to the user. */
    private fun afterSpeaking() {
        if (closed) return
        if (!ttsReady) { phase = Phase.IDLE; return }
        listen()
    }

    private fun failReply(index: Int, message: String) {
        if (index < turns.size && turns[index].text.isEmpty()) turns.removeAt(index)
        fail(message)
    }

    private fun fail(message: String) {
        error = message
        phase = Phase.ERROR
    }
}

/** The Claude side of [VoiceChat]. */
object ClaudeVoice {
    private const val MODEL = "claude-opus-5-5"
    private const val SYSTEM = "You are a voice assistant built into the user's phone home screen. Your replies are " +
        "read aloud, so answer conversationally and briefly - usually one to three sentences - in plain " +
        "spoken language with no markdown, lists, code, links or emoji. Offer more detail only if asked."

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun client(key: String): AnthropicClient {
        if (client == null || clientKey != key) {
            client = AnthropicOkHttpClient.builder().apiKey(key).build()
            clientKey = key
        }
        return client!!
    }

    /** Streams Claude's reply to [history] (alternating user/assistant turns), calling [onText] with each piece. */
    fun stream(key: String, history: List<VoiceChat.Turn>, onText: (String) -> Unit) {
        val b = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM)
            // Quick, spoken answers: low effort keeps the wait short.
            .outputConfig(com.anthropic.models.beta.messages.BetaOutputConfig.builder()
                .effort(com.anthropic.models.beta.messages.BetaOutputConfig.Effort.LOW).build())
            // If a safety check declines the request, let the API retry it on a fitting model.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        history.filter { it.text.isNotBlank() }.forEach { t -> if (t.fromUser) b.addUserMessage(t.text) else b.addAssistantMessage(t.text) }
        client(key).beta().messages().createStreaming(b.build()).use { s ->
            s.stream()
                .flatMap { event -> event.contentBlockDelta().stream() }
                .flatMap { d -> d.delta().text().stream() }
                .forEach { onText(it.text()) }
        }
    }
}

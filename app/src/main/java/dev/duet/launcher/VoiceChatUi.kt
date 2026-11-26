package dev.duet.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Voice chat with Claude over the home screen; opened by the AI button on the clock. */
@Composable
fun VoiceChatPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val chat = remember { VoiceChat(ctx.applicationContext) }
    DisposableEffect(Unit) { chat.start(); onDispose { chat.close() } }
    BackHandler(onBack = onClose)
    val tint = accent
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)
                .padding(12.dp)
                .clip(RoundedCornerShape(28.dp)).background(Color(0xF0141519))
                .clickable(remember { MutableInteractionSource() }, null) { }
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Claude", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                PillButton(if (typing) "Voice" else "Type") { typing = !typing }
                Spacer(Modifier.size(8.dp))
                PillButton("Close", onClick = onClose)
            }
            val list = rememberLazyListState()
            LaunchedEffect(chat.turns.size, chat.turns.lastOrNull()?.text?.length) {
                if (chat.turns.isNotEmpty()) list.animateScrollToItem(chat.turns.lastIndex)
            }
            if (chat.turns.isNotEmpty()) LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 14.dp), state = list,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(chat.turns) { _, t -> Bubble(t, tint) }
            }
            Spacer(Modifier.height(18.dp))
            Orb(chat.phase, chat.level, tint) { chat.tapOrb() }
            Spacer(Modifier.height(12.dp))
            Text(
                chat.error ?: when (chat.phase) {
                    VoiceChat.Phase.LISTENING -> chat.partial.ifEmpty { "Listening…" }
                    VoiceChat.Phase.THINKING -> "Thinking…"
                    VoiceChat.Phase.SPEAKING -> "Tap to interrupt"
                    VoiceChat.Phase.IDLE -> "Tap to talk"
                    VoiceChat.Phase.ERROR -> ""
                },
                color = if (chat.error != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp, maxLines = 3,
            )
            if (typing) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        draft, { draft = it }, Modifier.weight(1f),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(tint),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { chat.send(draft); draft = "" }),
                        singleLine = true,
                    )
                    Text("Send", color = tint, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { chat.send(draft); draft = "" }.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Bubble(t: VoiceChat.Turn, tint: Color) {
    if (t.text.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            t.text, color = if (t.fromUser) Color(0xFF101216) else Color.White, fontSize = 15.sp,
            modifier = Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp))
                .background(if (t.fromUser) tint else Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** A glowing orb: breathes with your voice while listening, swirls while thinking, ripples while speaking. */
@Composable
private fun Orb(phase: VoiceChat.Phase, level: Float, tint: Color, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val voice by animateFloatAsState(if (phase == VoiceChat.Phase.LISTENING) level else 0f, tween(90), label = "voice")
    val active = phase != VoiceChat.Phase.IDLE && phase != VoiceChat.Phase.ERROR
    Canvas(Modifier.size(112.dp).clip(CircleShape).clickable(onClick = onClick)) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension * 0.30f
        val r = base * (1f + 0.28f * voice + if (phase == VoiceChat.Phase.SPEAKING) 0.06f * pulse else 0f)
        // Halo.
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = if (active) 0.45f else 0.2f), Color.Transparent), c, r * 1.75f), r * 1.75f, c)
        // Body.
        drawCircle(Brush.radialGradient(listOf(lerp(tint, Color.White, 0.55f), tint, lerp(tint, Color.Black, 0.35f)), c + Offset(-r * 0.3f, -r * 0.35f), r * 1.6f), r, c)
        when (phase) {
            VoiceChat.Phase.THINKING -> {
                // A bright arc chasing round the edge.
                drawArc(Color.White.copy(alpha = 0.85f), 360f * spin, 90f, false, Offset(c.x - r * 1.22f, c.y - r * 1.22f),
                    androidx.compose.ui.geometry.Size(r * 2.44f, r * 2.44f), style = Stroke(width = 3.dp.toPx()))
            }
            VoiceChat.Phase.SPEAKING -> {
                for (k in 0..2) {
                    val p = (spin + k / 3f) % 1f
                    drawCircle(tint.copy(alpha = (1f - p) * 0.5f), r * (1f + 0.7f * p), c, style = Stroke(2.dp.toPx()))
                }
            }
            VoiceChat.Phase.LISTENING -> {
                // A small live waveform across the middle.
                val w = r * 1.1f
                for (i in 0..6) {
                    val x = c.x - w / 2 + w * i / 6f
                    val h = r * (0.12f + 0.55f * voice * (0.5f + 0.5f * sin((spin * 2 * PI + i).toFloat())))
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(x, c.y - h), Offset(x, c.y + h), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            else -> {}
        }
    }
}

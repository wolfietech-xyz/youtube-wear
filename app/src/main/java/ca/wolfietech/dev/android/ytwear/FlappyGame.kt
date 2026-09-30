package ca.wolfietech.dev.android.ytwear

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Hidden minigame, opened by holding a loading Short for 6 s: flap a play button through the
 * gaps between ad bars. Positions are in fractions of the screen so it fits any watch.
 */
@Composable
fun FlappyGame(videoReady: Boolean, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val game = remember { FlappyState() }
    val text = rememberTextMeasurer()
    val adLabel = remember(text) {
        text.measure("AD", TextStyle(color = Color(0xFF3A2E00), fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
    var best by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            game.step(((now - last) / 1e9f).coerceAtMost(0.05f))
            last = now
            if (game.over) best = maxOf(best, game.score)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .pointerInput(Unit) { detectTapGestures(onPress = { game.tap() }) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val barWidth = FlappyState.BAR_WIDTH * w
            for (bar in game.bars) {
                val x = bar.x * w
                val gapTop = (bar.gapCentre - FlappyState.GAP / 2) * h
                val gapBottom = (bar.gapCentre + FlappyState.GAP / 2) * h
                drawRoundRect(AD_YELLOW, Offset(x, -20f), Size(barWidth, gapTop + 20f), CornerRadius(10f))
                drawRoundRect(AD_YELLOW, Offset(x, gapBottom), Size(barWidth, h - gapBottom + 20f), CornerRadius(10f))
                // "AD" on each half, next to the gap.
                val labelX = x + (barWidth - adLabel.size.width) / 2
                drawText(adLabel, topLeft = Offset(labelX, gapTop - adLabel.size.height - 6f))
                drawText(adLabel, topLeft = Offset(labelX, gapBottom + 6f))
            }
            // The player: a red rounded rectangle with a white triangle, tilted with its speed.
            val cx = FlappyState.PLAYER_X * w
            val cy = game.y * h
            val pw = FlappyState.PLAYER_SIZE * w * 1.4f
            val ph = FlappyState.PLAYER_SIZE * h
            val tilt = (game.velocity * 60f).coerceIn(-25f, 60f)
            drawContext.transform.rotate(tilt, Offset(cx, cy))
            drawRoundRect(Color(0xFFFF0033), Offset(cx - pw / 2, cy - ph / 2), Size(pw, ph), CornerRadius(ph * 0.3f))
            drawPath(
                Path().apply {
                    moveTo(cx - pw * 0.14f, cy - ph * 0.26f)
                    lineTo(cx + pw * 0.2f, cy)
                    lineTo(cx - pw * 0.14f, cy + ph * 0.26f)
                    close()
                },
                Color.White,
            )
            drawContext.transform.rotate(-tilt, Offset(cx, cy))
        }
        Text(
            "${game.score}",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        when {
            game.over -> Message(stringResource(R.string.game_over, game.score, best))
            !game.started -> Message(stringResource(R.string.game_intro))
        }
        ReadyCard(videoReady, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Once the video is ready: a card every 8 s for 2.5 s, without stopping the game. It's
 * only a notice; taps on it flap like anywhere else.
 */
@Composable
private fun ReadyCard(videoReady: Boolean, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(videoReady) {
        while (videoReady) {
            visible = true
            delay(2_500)
            visible = false
            delay(5_500)
        }
        visible = false
    }
    AnimatedVisibility(visible, modifier.padding(bottom = 24.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            stringResource(R.string.game_video_ready),
            modifier = Modifier
                .background(Color(0xE6202020), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            text,
            modifier = Modifier.background(Color(0xB0000000)).padding(12.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val AD_YELLOW = Color(0xFFFFCC00)

/** Game physics in screen fractions per second. */
private class FlappyState {
    var y by mutableStateOf(0.5f)
    var velocity by mutableStateOf(0f)
    var score by mutableIntStateOf(0)
    var started by mutableStateOf(false)
    var over by mutableStateOf(false)
    val bars = mutableStateListOf<Bar>()
    private var sinceSpawn = 0f

    data class Bar(val x: Float, val gapCentre: Float, val passed: Boolean = false)

    fun tap() {
        when {
            over -> reset()
            !started -> { started = true; velocity = FLAP }
            else -> velocity = FLAP
        }
    }

    private fun reset() {
        y = 0.5f
        velocity = 0f
        score = 0
        bars.clear()
        sinceSpawn = 0f
        over = false
        started = false
    }

    fun step(dt: Float) {
        if (!started || over) return
        velocity += GRAVITY * dt
        y += velocity * dt
        sinceSpawn += dt
        if (sinceSpawn > SPAWN_EVERY) {
            sinceSpawn = 0f
            bars += Bar(1.05f, Random.nextFloat() * 0.4f + 0.3f)
        }
        for (i in bars.indices.reversed()) {
            val bar = bars[i].let { it.copy(x = it.x - SPEED * dt) }
            if (bar.x < -BAR_WIDTH) {
                bars.removeAt(i)
                continue
            }
            if (!bar.passed && bar.x + BAR_WIDTH < PLAYER_X - PLAYER_SIZE / 2) {
                score++
                bars[i] = bar.copy(passed = true)
            } else {
                bars[i] = bar
            }
            if (hits(bar)) over = true
        }
        // The edge of a round screen: leaving the middle 90% ends the run.
        if (y < 0.05f || y > 0.95f) over = true
    }

    private fun hits(bar: Bar): Boolean {
        val half = PLAYER_SIZE / 2 * 0.8f // a little forgiving
        val overlapsX = PLAYER_X + half > bar.x && PLAYER_X - half < bar.x + BAR_WIDTH
        val inGap = y - half > bar.gapCentre - GAP / 2 && y + half < bar.gapCentre + GAP / 2
        return overlapsX && !inGap
    }

    companion object {
        const val PLAYER_X = 0.32f
        const val PLAYER_SIZE = 0.09f
        const val BAR_WIDTH = 0.13f
        const val GAP = 0.34f
        const val GRAVITY = 1.9f
        const val FLAP = -0.62f
        const val SPEED = 0.32f
        const val SPAWN_EVERY = 1.5f
    }
}

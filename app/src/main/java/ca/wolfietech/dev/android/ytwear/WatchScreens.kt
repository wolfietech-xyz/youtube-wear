package ca.wolfietech.dev.android.ytwear

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.icu.text.CompactDecimalFormat
import java.util.Locale

/** Shorts: swipe up or down to move between Shorts. Paused, it shows the Short's info. */
@Composable
fun ShortsScreen(session: WatchSession, player: ExoPlayer, onExit: () -> Unit) {
    val pager = rememberPagerState { session.ids.size }
    val scope = rememberCoroutineScope()
    // The pager decides which Short plays once a swipe settles...
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (session.ids.isNotEmpty()) session.select(it) }
    }
    // ...and follows when the session moves on its own (buttons, first load).
    LaunchedEffect(session.index) {
        if (session.index >= 0 && pager.currentPage != session.index) pager.animateScrollToPage(session.index)
    }

    var holdDots by remember { mutableIntStateOf(0) }
    var gameOpen by remember { mutableStateOf(false) }

    WatchFrame(session, onExit) { volume ->
        if (session.ids.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                session.error?.let { Status(it) } ?: BrandedStatus("CONNECTING")
            }
            return@WatchFrame
        }
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .holdToForceLoad(session, onDots = { holdDots = it }, onGame = { gameOpen = true })
                    .pointerInput(Unit) {
                        // Tap pauses; double-tap likes, like the YouTube app. A long press
                        // is the hold above, so it mustn't also count as a tap.
                        detectTapGestures(
                            onTap = { session.togglePause() },
                            onDoubleTap = { session.toggleLike() },
                            onLongPress = {},
                        )
                    },
                Alignment.Center,
            ) {
                if (page == session.index) {
                    VideoSurface(player, session.aspectRatio, Modifier.fillMaxHeight())
                    if (session.waiting || session.error != null) {
                        session.error?.let { Status(it) }
                            ?: if (session.waitingWithoutTimeout) BrandedStatus("FORCED BUFFERING", showYt = false)
                            else BrandedStatus("LOADING")
                    }
                    LikeBurst(session.likeBursts, session.lastBurstLiked)
                    Notice(session)
                    if (session.paused && session.current != null) {
                        PausedControls(
                            session,
                            volume,
                            showInfo = true,
                            onPrevious = { scope.launch { if (session.index > 0) pager.animateScrollToPage(session.index - 1) } },
                            onNext = { scope.launch { pager.animateScrollToPage(session.index + 1) } },
                        )
                    }
                } else {
                    Status("")
                }
            }
        }
        HoldDots(holdDots)
        if (gameOpen) GameOverlay(session, onExit = { gameOpen = false })
    }
}

/** Normal videos: tap to pause, swipe up for the video's info. */
@Composable
fun VideosScreen(session: WatchSession, player: ExoPlayer, onExit: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    BackHandler(enabled = showInfo) { showInfo = false }
    val swipe = with(LocalDensity.current) { 48.dp.toPx() }
    var holdDots by remember { mutableIntStateOf(0) }
    var gameOpen by remember { mutableStateOf(false) }

    WatchFrame(session, onExit, rotaryEnabled = !showInfo) { volume ->
        Box(
            Modifier
                .fillMaxSize()
                .holdToForceLoad(session, onDots = { holdDots = it }, onGame = { gameOpen = true })
                .pointerInput(Unit) { detectTapGestures(onTap = { session.togglePause() }, onLongPress = {}) }
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (dragged < -swipe && session.current != null) showInfo = true },
                    ) { _, dy -> dragged += dy }
                },
            Alignment.Center,
        ) {
            VideoSurface(player, session.aspectRatio, Modifier.fillMaxWidth())
            if (session.waiting || session.error != null) {
                session.error?.let { Status(it) } ?: BrandedStatus("LOADING")
            }
            if (session.paused && session.current != null && !showInfo) {
                PausedControls(session, volume, showInfo = false, session::previous, session::next)
            }
        }
        AnimatedVisibility(
            visible = showInfo,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            session.current?.let { InfoPanel(it, onClose = { showInfo = false }) }
        }
        HoldDots(holdDots)
        if (gameOpen) GameOverlay(session, onExit = { gameOpen = false })
    }
}

private const val HOLD_DOT_MS = 1_500L
private val HOLD_WOBBLE = 24.dp
/** Dots needed to force-load (3 s); twice that (6 s) opens the minigame. */
private const val FORCE_LOAD_DOTS = 2
private const val GAME_DOTS = FORCE_LOAD_DOTS * 2

/**
 * Hold on a loading or failed video: a dot appears every 1.5 s. Releasing at 2 dots or
 * more makes the video wait without a timeout until it's unloaded (for bad networks);
 * releasing at 4 dots opens the hidden minigame. If the video starts playing mid-hold,
 * the hold is dropped entirely. Dragging more than [HOLD_WOBBLE] cancels,
 * so swipes between Shorts still work. Doesn't consume events, so taps are still seen.
 */
private fun Modifier.holdToForceLoad(
    session: WatchSession,
    onDots: (Int) -> Unit,
    onGame: () -> Unit,
) = pointerInput(session) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!session.waiting && session.error == null) return@awaitEachGesture
        val started = down.uptimeMillis
        var dots = 0
        while (true) {
            val event = withTimeoutOrNull(100) { awaitPointerEvent() }
            // The video got going during the hold: drop it, dots and minigame timer included.
            if (!session.waiting && session.error == null) {
                onDots(0)
                return@awaitEachGesture
            }
            val change = event?.changes?.firstOrNull { it.id == down.id }
            // Pointer times use the same clock as SystemClock.uptimeMillis.
            val now = change?.uptimeMillis ?: SystemClock.uptimeMillis()
            dots = ((now - started) / HOLD_DOT_MS).toInt().coerceAtMost(GAME_DOTS)
            onDots(dots)
            if (change == null) continue
            if (!change.pressed) break
            // A real finger wobbles; only a clear drag (a swipe to another Short) cancels.
            if ((change.position - down.position).getDistance() > HOLD_WOBBLE.toPx()) {
                onDots(0)
                return@awaitEachGesture
            }
        }
        onDots(0)
        when {
            dots >= GAME_DOTS -> onGame()
            dots >= FORCE_LOAD_DOTS -> session.waitWithoutTimeout()
        }
    }
}

/** The hold countdown, top right: white while counting, green once armed, red for the game. */
@Composable
private fun HoldDots(dots: Int) {
    if (dots == 0) return
    Box(Modifier.fillMaxSize().padding(top = 58.dp, end = 58.dp), Alignment.TopEnd) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(dots) { i ->
                val color = when {
                    dots >= GAME_DOTS -> Color(0xFFFF3040)
                    i < FORCE_LOAD_DOTS && dots >= FORCE_LOAD_DOTS -> Color(0xFF3DDC84)
                    else -> Color.White
                }
                Box(Modifier.size(9.dp).background(color, CircleShape))
            }
        }
    }
}

/** The minigame over the video; pauses the video if it finishes loading meanwhile. */
@Composable
private fun GameOverlay(session: WatchSession, onExit: () -> Unit) {
    val ready = !session.waiting && session.error == null && session.current != null
    LaunchedEffect(ready) {
        if (ready && !session.paused) session.togglePause()
    }
    FlappyGame(videoReady = ready, onExit = onExit)
}

/**
 * What both screens share: black background, swipe back to leave, and the rotating bezel
 * or crown adjusting volume.
 */
@Composable
private fun WatchFrame(
    session: WatchSession,
    onExit: () -> Unit,
    rotaryEnabled: Boolean = true,
    content: @Composable (Volume) -> Unit,
) {
    BackHandler(onBack = onExit)
    val volume = rememberVolume()
    val focus = remember { FocusRequester() }
    LaunchedEffect(rotaryEnabled) { if (rotaryEnabled) focus.requestFocus() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent {
                if (rotaryEnabled) volume.step(if (it.verticalScrollPixels > 0) 1 else -1)
                rotaryEnabled
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        content(volume)
    }
}

/**
 * Video rendered into a TextureView. A SurfaceView (PlayerView's default) sits behind the
 * window and shows through a hole, which the black Compose background sometimes covered:
 * audio played over a black screen.
 */
@Composable
private fun VideoSurface(player: ExoPlayer, aspectRatio: Float, modifier: Modifier) {
    AndroidView(
        modifier = modifier.aspectRatio(aspectRatio),
        factory = { context ->
            TextureView(context).apply {
                keepScreenOn = true
                player.setVideoTextureView(this)
            }
        },
        onRelease = { player.clearVideoTextureView(it) },
    )
}

/**
 * Loading screen: YT, then [word] between two thin rules.
 *
 *     YT
 *   ------
 *   LOADING
 *   ------
 */
@Composable
private fun BrandedStatus(word: String, showYt: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showYt) Text("YT", style = MaterialTheme.typography.displaySmall, color = Color(0xFFFF2D45))
        Rule()
        Text(word, style = MaterialTheme.typography.labelMedium, letterSpacing = 3.sp)
        Rule()
    }
}

@Composable
private fun Rule() {
    Box(Modifier.width(96.dp).height(1.dp).background(Color.White.copy(alpha = 0.6f)))
}

@Composable
private fun Status(text: String) {
    Text(
        text,
        modifier = Modifier.padding(32.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 4,
    )
}

/** Shown while paused: previous / play / next, volume, and for Shorts the Short's info. */
@Composable
private fun PausedControls(
    session: WatchSession,
    volume: Volume,
    showInfo: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val video = session.current
        if (showInfo && video != null) {
            Text(
                video.title,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    "♥".takeIf { session.liked[video.id] == true },
                    video.channel,
                    video.viewCount?.let { "${compact(it)} views" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyExtraSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPrevious) { Glyph(Glyphs.Previous) }
            FilledIconButton(onClick = session::togglePause, modifier = Modifier.size(56.dp)) { Glyph(Glyphs.Play) }
            FilledTonalIconButton(onClick = onNext) { Glyph(Glyphs.Next) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { volume.step(-1) }) { Glyph(Glyphs.Minus) }
            Text("Vol ${volume.level}", style = MaterialTheme.typography.labelMedium)
            FilledTonalIconButton(onClick = { volume.step(1) }) { Glyph(Glyphs.Plus) }
        }
    }
}

/**
 * A heart that pops in at the centre each time [bursts] goes up. A like is red and floats
 * up; an unlike pops in red, then turns grey as it sinks. Both fade out as they go.
 */
@Composable
private fun LikeBurst(bursts: Int, liked: Boolean) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.5f) }
    val drift = remember { Animatable(0f) } // 0 at the centre, 1 at the end of the float or sink
    val grey = remember { Animatable(0f) } // 0 red, 1 grey
    // Each Short gets a fresh LikeBurst; only animate for likes made while it's on screen.
    val shownFrom = remember { bursts }
    LaunchedEffect(bursts) {
        if (bursts == shownFrom) return@LaunchedEffect
        alpha.snapTo(1f)
        scale.snapTo(0.5f)
        drift.snapTo(0f)
        grey.snapTo(0f)
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
        coroutineScope {
            launch { drift.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
            if (!liked) launch { grey.animateTo(1f, tween(300)) }
            launch {
                delay(250)
                alpha.animateTo(0f, tween(400))
            }
        }
    }
    if (alpha.value == 0f) return
    val travel = with(LocalDensity.current) { 110.dp.toPx() }
    Canvas(
        Modifier.size(110.dp).graphicsLayer {
            this.alpha = alpha.value
            scaleX = scale.value
            scaleY = scale.value
            translationY = drift.value * travel * if (liked) -1 else 1
        },
    ) {
        val w = size.width
        val h = size.height
        val heart = Path().apply {
            moveTo(w / 2, h * 0.9f)
            cubicTo(w * -0.1f, h * 0.5f, w * 0.15f, h * -0.05f, w / 2, h * 0.28f)
            cubicTo(w * 0.85f, h * -0.05f, w * 1.1f, h * 0.5f, w / 2, h * 0.9f)
            close()
        }
        val g = grey.value
        // Light top-left to dark bottom-right, so the heart looks rounded.
        drawPath(
            heart,
            Brush.linearGradient(
                listOf(
                    lerp(Color(0xFFFF8A95), Color(0xFFE0E0E0), g),
                    lerp(Color(0xFFFF2D45), Color(0xFF9E9E9E), g),
                    lerp(Color(0xFFB0102A), Color(0xFF555555), g),
                ),
                start = Offset(w * 0.15f, h * 0.1f),
                end = Offset(w * 0.85f, h * 0.95f),
            ),
        )
        // Soft highlight on the upper left lobe.
        drawOval(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(w * 0.3f, h * 0.3f),
                radius = w * 0.16f,
            ),
            topLeft = Offset(w * 0.16f, h * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.24f),
        )
    }
}

/** A like failure, shown for a few seconds. */
@Composable
private fun Notice(session: WatchSession) {
    val text = session.notice ?: return
    LaunchedEffect(text) {
        delay(3_000)
        session.notice = null
    }
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), Alignment.BottomCenter) {
        Text(
            text,
            modifier = Modifier.background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyExtraSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Swiped up from a normal video. Scrolls; swipe down or back to close. */
@Composable
private fun InfoPanel(video: ResolvedVideo, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val closeDrag = with(LocalDensity.current) { 48.dp.toPx() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0101010))
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (scroll.value == 0 && dragged > closeDrag) onClose() },
                ) { _, dy ->
                    dragged += dy
                    scope.launch { scroll.scrollBy(-dy) }
                }
            }
            .onRotaryScrollEvent {
                scope.launch { scroll.scrollBy(it.verticalScrollPixels) }
                true
            }
            .focusRequester(focus)
            .focusable()
            .verticalScroll(scroll, enabled = false)
            .padding(horizontal = 32.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(video.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        video.channel?.let { Text(it, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
        Text(
            listOfNotNull(
                video.viewCount?.let { "${compact(it)} views" },
                video.likeCount?.let { "${compact(it)} likes" },
                video.uploadDate?.let(::formatDate),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyExtraSmall,
            textAlign = TextAlign.Center,
        )
        video.description?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Media volume, the same stream the watch's own volume controls change. */
class Volume(private val audio: AudioManager) {
    var level by mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        private set

    fun step(direction: Int) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
        refresh()
    }

    /** Re-read the system value; the stream can change from outside (bezel, system panel). */
    fun refresh() {
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
}

@Composable
private fun rememberVolume(): Volume {
    val context = LocalContext.current
    val volume = remember { Volume(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager) }
    DisposableEffect(volume) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) = volume.refresh()
        }
        // Not a public constant, but the long-standing broadcast for stream volume changes.
        context.registerReceiver(
            receiver,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
        )
        volume.refresh()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return volume
}

private enum class Glyphs { Previous, Play, Next, Plus, Minus }

/** Small drawn icons, so the app doesn't need an icon library for five symbols. */
@Composable
private fun Glyph(glyph: Glyphs) {
    val color = MaterialTheme.colorScheme.onSurface.let { if (glyph == Glyphs.Play) MaterialTheme.colorScheme.onPrimary else it }
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val bar = w * 0.14f
        fun triangle(left: Float, right: Float, pointRight: Boolean) = Path().apply {
            if (pointRight) {
                moveTo(left, h * 0.15f); lineTo(right, h / 2); lineTo(left, h * 0.85f)
            } else {
                moveTo(right, h * 0.15f); lineTo(left, h / 2); lineTo(right, h * 0.85f)
            }
            close()
        }
        when (glyph) {
            Glyphs.Play -> drawPath(triangle(w * 0.22f, w * 0.88f, pointRight = true), color)
            Glyphs.Next -> {
                drawPath(triangle(w * 0.12f, w * 0.72f, pointRight = true), color)
                drawRect(color, Offset(w * 0.74f, h * 0.15f), androidx.compose.ui.geometry.Size(bar, h * 0.7f))
            }
            Glyphs.Previous -> {
                drawRect(color, Offset(w * 0.12f, h * 0.15f), androidx.compose.ui.geometry.Size(bar, h * 0.7f))
                drawPath(triangle(w * 0.28f, w * 0.88f, pointRight = false), color)
            }
            Glyphs.Plus -> {
                drawRect(color, Offset(w * 0.15f, h / 2 - bar / 2), androidx.compose.ui.geometry.Size(w * 0.7f, bar))
                drawRect(color, Offset(w / 2 - bar / 2, h * 0.15f), androidx.compose.ui.geometry.Size(bar, h * 0.7f))
            }
            Glyphs.Minus -> drawRect(color, Offset(w * 0.15f, h / 2 - bar / 2), androidx.compose.ui.geometry.Size(w * 0.7f, bar))
        }
    }
}

private fun compact(n: Long): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(n)

private fun formatDate(yyyymmdd: String): String =
    if (yyyymmdd.length == 8) "${yyyymmdd.substring(0, 4)}-${yyyymmdd.substring(4, 6)}-${yyyymmdd.substring(6)}" else yyyymmdd

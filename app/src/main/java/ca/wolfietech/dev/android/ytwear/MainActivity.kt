package ca.wolfietech.dev.android.ytwear

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "YouTubeWear"

// "Me at the zoo": short, public and long-lived. Stands in until there's a way to pick videos.
private const val TEST_VIDEO = "https://www.youtube.com/watch?v=jNQXAC9IVRw"

sealed interface UiState {
    data class Idle(val status: String) : UiState
    data object Loading : UiState
    data object Playing : UiState
    data class Failed(val message: String) : UiState
}

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf<UiState>(UiState.Idle("starting…"))
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = buildPlayer(this).apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback failed", error)
                    state = UiState.Failed("Playback: ${error.errorCodeName}")
                }
            })
        }
        setContent { WearApp(state, player, onPlay = ::playTestVideo, onStop = ::stopVideo) }

        // Starting Python takes a moment on a watch, so keep it off the main thread.
        lifecycleScope.launch {
            val ytDlpVersion = withContext(Dispatchers.Default) { YtDlp.version(this@MainActivity) }
            if (state is UiState.Idle) state = UiState.Idle("yt-dlp $ytDlpVersion")
        }
    }

    private fun playTestVideo() {
        state = UiState.Loading
        lifecycleScope.launch {
            try {
                val video = withContext(Dispatchers.IO) { YtDlp.resolve(this@MainActivity, TEST_VIDEO) }
                Log.i(TAG, "Playing '${video.title}': ${video.video.description} + ${video.audio?.description}")
                player.playVideo(video)
                state = UiState.Playing
            } catch (e: Exception) {
                Log.e(TAG, "yt-dlp failed", e)
                state = UiState.Failed(e.message?.lineSequence()?.firstOrNull() ?: e.toString())
            }
        }
    }

    private fun stopVideo() {
        player.stop()
        state = UiState.Idle("Stopped")
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

@Composable
fun WearApp(state: UiState, player: Player?, onPlay: () -> Unit, onStop: () -> Unit) {
    MaterialTheme {
        AppScaffold {
            if (state is UiState.Playing && player != null) {
                VideoScreen(player, onStop)
            } else {
                HomeScreen(state, onPlay)
            }
        }
    }
}

@Composable
fun HomeScreen(state: UiState, onPlay: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("YouTube Wear", style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (state) {
                is UiState.Idle -> state.status
                UiState.Loading -> "Finding video…"
                UiState.Playing -> ""
                is UiState.Failed -> state.message
            },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
        )
        Button(onClick = onPlay, enabled = state !is UiState.Loading) {
            Text("Play test video")
        }
    }
}

/** Full-screen video. Tap to pause or resume; swipe back to stop. */
@Composable
fun VideoScreen(player: Player, onStop: () -> Unit) {
    BackHandler(onBack = onStop)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { if (player.isPlaying) player.pause() else player.play() },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    // Media3's built-in controls are sized for phones.
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    this.player = player
                }
            },
            onRelease = { it.player = null },
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearAppPreview() {
    WearApp(UiState.Idle("yt-dlp 2026.09.01"), player = null, onPlay = {}, onStop = {})
}

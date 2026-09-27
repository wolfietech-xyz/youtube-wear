package ca.wolfietech.dev.android.ytwear

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
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

    private val pickCookies =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importCookies) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = buildPlayer(this).apply {
            if (BuildConfig.DEBUG) addAnalyticsListener(EventLogger())
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val name = when (playbackState) {
                        Player.STATE_BUFFERING -> "buffering"
                        Player.STATE_READY -> "ready"
                        Player.STATE_ENDED -> "ended"
                        else -> "idle"
                    }
                    Log.i(TAG, "Player $name at ${currentPosition}ms")
                }

                override fun onRenderedFirstFrame() {
                    Log.i(TAG, "First video frame rendered")
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    Log.i(TAG, "Video size ${videoSize.width}x${videoSize.height}")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback failed", error)
                    state = UiState.Failed("Playback: ${error.errorCodeName}")
                }
            })
        }
        setContent {
            WearApp(state, player, onPlay = ::playTestVideo, onImportCookies = ::chooseCookies, onStop = ::stopVideo)
        }

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

    private fun chooseCookies() {
        try {
            pickCookies.launch(arrayOf("text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            state = UiState.Failed("This watch has no file picker")
        }
    }

    /** Copies a cookies.txt exported from a signed-in browser into the app's private storage. */
    private fun importCookies(uri: Uri) {
        lifecycleScope.launch {
            state = try {
                val count = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    // Netscape format: 7 tab-separated fields per cookie line.
                    val cookies = text.lineSequence().count { it.split('	').size == 7 }
                    require(cookies > 0) { "Not a cookies.txt file" }
                    YtDlp.cookieFile(this@MainActivity).writeText(text)
                    cookies
                }
                UiState.Idle("Imported $count cookies")
            } catch (e: Exception) {
                Log.e(TAG, "Cookie import failed", e)
                UiState.Failed("Cookie import: ${e.message}")
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
fun WearApp(
    state: UiState,
    player: Player?,
    onPlay: () -> Unit,
    onImportCookies: () -> Unit,
    onStop: () -> Unit,
) {
    MaterialTheme {
        AppScaffold {
            if (state is UiState.Playing && player != null) {
                VideoScreen(player, onStop)
            } else {
                HomeScreen(state, onPlay, onImportCookies)
            }
        }
    }
}

@Composable
fun HomeScreen(state: UiState, onPlay: () -> Unit, onImportCookies: () -> Unit) {
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
        Button(onClick = onImportCookies, enabled = state !is UiState.Loading) {
            Text("Import cookies")
        }
    }
}

/** Full-screen video. Tap to pause or resume; swipe back to stop. */
@Composable
fun VideoScreen(player: Player, onStop: () -> Unit) {
    BackHandler(onBack = onStop)
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { if (player.isPlaying) player.pause() else player.play() },
        contentAlignment = Alignment.Center,
    ) {
        // A TextureView draws inside the app's window. A SurfaceView (PlayerView's default)
        // sits behind it and shows through a hole, which this black Box sometimes covered:
        // audio played over a black screen.
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio),
            factory = { context ->
                TextureView(context).apply {
                    keepScreenOn = true
                    player.setVideoTextureView(this)
                }
            },
            onRelease = { player.clearVideoTextureView(it) },
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun WearAppPreview() {
    WearApp(UiState.Idle("yt-dlp 2026.09.01"), player = null, onPlay = {}, onImportCookies = {}, onStop = {})
}

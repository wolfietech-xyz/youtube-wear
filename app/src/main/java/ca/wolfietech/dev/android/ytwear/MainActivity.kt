package ca.wolfietech.dev.android.ytwear

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TAG = "YouTubeWear"

// Long-lived public videos for video mode, until there's a way to pick videos.
private val TEST_VIDEOS = listOf("jNQXAC9IVRw", "dQw4w9WgXcQ", "9bZkp7q19f0")

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("starting…")
    private var session by mutableStateOf<WatchSession?>(null)
    private lateinit var player: ExoPlayer
    private lateinit var loader: VideoLoader

    private val pickCookies =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importCookies) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loader = VideoLoader(applicationContext, lifecycleScope)
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
            })
        }
        setContent {
            MaterialTheme {
                AppScaffold {
                    val watching = session
                    when (watching?.mode) {
                        WatchMode.Reels -> ReelsScreen(watching, player, onExit = ::stopWatching)
                        WatchMode.Videos -> VideosScreen(watching, player, onExit = ::stopWatching)
                        null -> HomeScreen(
                            status,
                            onReels = { startWatching(WatchMode.Reels) },
                            onVideos = { startWatching(WatchMode.Videos) },
                            onImportCookies = ::chooseCookies,
                        )
                    }
                }
            }
        }

        // Starting Python takes a moment on a watch, so keep it off the main thread.
        lifecycleScope.launch {
            val ytDlpVersion = withContext(Dispatchers.Default) { YtDlp.version(this@MainActivity) }
            status = "yt-dlp $ytDlpVersion"
        }
    }

    private fun startWatching(mode: WatchMode) {
        session?.close()
        val like: suspend (String) -> Unit = { id -> withContext(Dispatchers.IO) { YtDlp.like(applicationContext, id) } }
        session = when (mode) {
            WatchMode.Reels -> WatchSession(mode, player, loader, like, lifecycleScope) { token ->
                withContext(Dispatchers.IO) { YtDlp.shortsFeed(applicationContext, token) }
            }.apply { start() }
            WatchMode.Videos -> WatchSession(mode, player, loader, like, lifecycleScope).apply { start(TEST_VIDEOS) }
        }
    }

    private fun stopWatching() {
        session?.close()
        session = null
    }

    private fun chooseCookies() {
        try {
            pickCookies.launch(arrayOf("text/plain", "*/*"))
        } catch (e: ActivityNotFoundException) {
            status = "This watch has no file picker"
        }
    }

    /** Copies a cookies.txt exported from a signed-in browser into the app's private storage. */
    private fun importCookies(uri: Uri) {
        lifecycleScope.launch {
            status = try {
                val count = withContext(Dispatchers.IO) {
                    val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    // Netscape format: 7 tab-separated fields per cookie line.
                    val cookies = text.lineSequence().count { it.split('\t').size == 7 }
                    require(cookies > 0) { "Not a cookies.txt file" }
                    YtDlp.cookieFile(this@MainActivity).writeText(text)
                    cookies
                }
                "Imported $count cookies"
            } catch (e: Exception) {
                Log.e(TAG, "Cookie import failed", e)
                "Cookie import: ${e.message}"
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        session?.close()
        player.release()
        super.onDestroy()
    }
}

@Composable
fun HomeScreen(status: String, onReels: () -> Unit, onVideos: () -> Unit, onImportCookies: () -> Unit) {
    ScalingLazyColumn(Modifier.fillMaxWidth()) {
        item { Text("YouTube Wear", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
        item { Button(onClick = onReels, modifier = Modifier.fillMaxWidth()) { Text("Reels") } }
        item { FilledTonalButton(onClick = onVideos, modifier = Modifier.fillMaxWidth()) { Text("Videos") } }
        item {
            FilledTonalButton(onClick = onImportCookies, modifier = Modifier.fillMaxWidth()) { Text("Import cookies") }
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun HomeScreenPreview() {
    MaterialTheme { HomeScreen("yt-dlp 2026.09.01", onReels = {}, onVideos = {}, onImportCookies = {}) }
}

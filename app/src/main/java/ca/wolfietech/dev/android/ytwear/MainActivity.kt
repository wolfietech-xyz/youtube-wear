package ca.wolfietech.dev.android.ytwear

import android.content.ActivityNotFoundException
import android.net.Uri
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.tooling.preview.devices.WearDevices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TAG = "YouTubeWear"

private const val SEARCH_QUERY = "query"

// Hidden until the watch can pick a file: Wear OS has no document picker (see HANDOFF.md).
private const val SHOW_IMPORT_COOKIES = false

class MainActivity : ComponentActivity() {
    private var status by mutableStateOf("starting…")
    private var session by mutableStateOf<WatchSession?>(null)
    private var showSettings by mutableStateOf(false)
    /** The Videos menu while it's open; stays open under a video started from it. */
    private var menu by mutableStateOf<VideoMenu?>(null)
    private lateinit var settings: Settings
    private lateinit var player: ExoPlayer
    private lateinit var loader: VideoLoader

    // Wear OS's own text input screen: voice, keyboard or handwriting, whatever the watch has.
    private val askSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val query = result.data?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(SEARCH_QUERY)?.toString()?.trim()
        if (!query.isNullOrEmpty()) search(query)
    }

    // The watch's own speech recognition.
    private val askVoice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val query = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!query.isNullOrEmpty()) search(query)
    }

    private val pickCookies =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importCookies) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loader = VideoLoader(applicationContext, lifecycleScope)
        settings = Settings(applicationContext)
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
                        null -> if (menu != null) {
                            VideoMenuScreen(
                                menu!!,
                                onSearch = ::openSearch,
                                onVoiceSearch = ::openVoiceSearch,
                                onPlay = ::playFromMenu,
                                onBack = { menu = null },
                            )
                        } else if (showSettings) {
                            BackHandler { showSettings = false }
                            SettingsScreen(settings)
                        } else HomeScreen(
                            status,
                            onReels = { startWatching(WatchMode.Reels) },
                            onVideos = ::openMenu,
                            onImportCookies = ::chooseCookies,
                            onSettings = { showSettings = true },
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

    private fun openMenu() {
        loadMenu("Home") { YtDlp.homeFeed(applicationContext) }
    }

    /** Opens the menu (or reuses it) and fills it with [heading] and what [fetch] returns. */
    private fun loadMenu(heading: String, fetch: () -> VideoList) {
        val m = menu ?: VideoMenu().also { menu = it }
        m.heading = heading
        m.loading = true
        m.error = null
        lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { fetch() }
                if (list.source == "popular") m.heading = "Popular"
                m.videos = list.videos
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load $heading", e)
                m.error = e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }
                    ?.replace(Regex("^\\w+(Error|Exception): "), "") ?: "Couldn't load videos"
            } finally {
                m.loading = false
            }
        }
    }

    private fun openSearch() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(SEARCH_QUERY).setLabel("Search YouTube").build()),
        )
        askSearch.launch(intent)
    }

    private fun openVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search YouTube")
        try {
            askVoice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            menu?.error = "No voice input on this watch"
        }
    }

    private fun search(query: String) {
        loadMenu("\u201c$query\u201d") { YtDlp.search(applicationContext, query) }
    }

    /** Plays the menu's list from [index]; next and previous move through the list. */
    private fun playFromMenu(index: Int) {
        val ids = menu?.videos?.map { it.id } ?: return
        startWatching(WatchMode.Videos, ids, index)
    }

    private fun startWatching(mode: WatchMode, videoIds: List<String> = emptyList(), startIndex: Int = 0) {
        session?.close()
        val like = object : Likes {
            override suspend fun get(id: String) = withContext(Dispatchers.IO) { YtDlp.likeStatus(applicationContext, id) }
            override suspend fun set(id: String, liked: Boolean) =
                withContext(Dispatchers.IO) { YtDlp.setLike(applicationContext, id, liked) }
        }
        session = when (mode) {
            WatchMode.Reels -> WatchSession(mode, player, loader, like, settings, lifecycleScope) { token ->
                withContext(Dispatchers.IO) { YtDlp.shortsFeed(applicationContext, token) }
            }.apply { start() }
            WatchMode.Videos -> WatchSession(mode, player, loader, like, settings, lifecycleScope)
                .apply { start(videoIds, startIndex) }
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
fun HomeScreen(
    status: String,
    onReels: () -> Unit,
    onVideos: () -> Unit,
    onImportCookies: () -> Unit,
    onSettings: () -> Unit,
) {
    ScalingLazyColumn(Modifier.fillMaxWidth()) {
        item { Text("YouTube Wear", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
        item { Button(onClick = onReels, modifier = Modifier.fillMaxWidth()) { Text("Reels") } }
        item { FilledTonalButton(onClick = onVideos, modifier = Modifier.fillMaxWidth()) { Text("Videos") } }
        if (SHOW_IMPORT_COOKIES) {
            item {
                FilledTonalButton(onClick = onImportCookies, modifier = Modifier.fillMaxWidth()) { Text("Import cookies") }
            }
        }
        item { FilledTonalButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") } }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun HomeScreenPreview() {
    MaterialTheme { HomeScreen("yt-dlp 2026.09.01", onReels = {}, onVideos = {}, onImportCookies = {}, onSettings = {}) }
}

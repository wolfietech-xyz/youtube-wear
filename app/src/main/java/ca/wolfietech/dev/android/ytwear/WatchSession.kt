package ca.wolfietech.dev.android.ytwear

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class WatchMode { Reels, Videos }

/**
 * A list of videos being watched and which one is playing. Reels come from a paged feed and
 * loop; videos come from a fixed list and advance when one ends.
 */
class WatchSession(
    val mode: WatchMode,
    private val player: ExoPlayer,
    private val loader: VideoLoader,
    private val scope: CoroutineScope,
    /** Fetches the next page of IDs, or null for a fixed list. */
    private val nextPage: (suspend (token: String?) -> FeedPage)? = null,
) {
    val ids = mutableStateListOf<String>()
    var index by mutableIntStateOf(-1)
        private set
    var current by mutableStateOf<ResolvedVideo?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var aspectRatio by mutableFloatStateOf(if (mode == WatchMode.Reels) 9f / 16f else 16f / 9f)
        private set

    private var pageToken: String? = null
    private var morePages = nextPage != null
    private var fetchingPage = false
    private var playJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            paused = !playWhenReady
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && mode == WatchMode.Videos) next()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed", error)
            this@WatchSession.error = "Playback: ${error.errorCodeName}"
        }
    }

    fun start(initialIds: List<String> = emptyList()) {
        player.addListener(listener)
        // Shorts loop like they do in the YouTube app.
        player.repeatMode = if (mode == WatchMode.Reels) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        ids.addAll(initialIds)
        if (ids.isNotEmpty()) select(0) else loadMore(thenSelectFirst = true)
    }

    fun close() {
        playJob?.cancel()
        player.removeListener(listener)
        player.stop()
        player.clearMediaItems()
    }

    /** Plays the video at [newIndex], resolving it first if it isn't ready yet. */
    fun select(newIndex: Int) {
        if (newIndex !in ids.indices || newIndex == index) return
        index = newIndex
        val id = ids[newIndex]
        playJob?.cancel()
        player.stop()
        current = null
        error = null
        loading = true
        playJob = scope.launch {
            try {
                val video = loader.load(id).await()
                current = video
                Log.i(TAG, "Playing '${video.title}': ${video.video.description} + ${video.audio?.description}")
                player.playVideo(video)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Couldn't load $id", e)
                error = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
            } finally {
                loading = false
            }
        }
        loader.prefetch(ids.drop(newIndex + 1).take(2))
        if (newIndex >= ids.size - 4) loadMore()
    }

    fun next() = select(index + 1)

    /** Restarts the current video if it's a few seconds in, like most players; else goes back. */
    fun previous() {
        if (player.currentPosition > 3_000 || index == 0) player.seekTo(0) else select(index - 1)
    }

    fun togglePause() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    private fun loadMore(thenSelectFirst: Boolean = false) {
        val fetch = nextPage ?: return
        if (!morePages || fetchingPage) return
        fetchingPage = true
        scope.launch {
            try {
                val page = fetch(pageToken)
                Log.i(TAG, "Got ${page.ids.size} reels from ${page.source}")
                ids.addAll(page.ids.filterNot { it in ids })
                pageToken = page.token
                morePages = page.token != null
                if (thenSelectFirst) select(0)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Couldn't load reels", e)
                if (ids.isEmpty()) {
                    error = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                    loading = false
                }
            } finally {
                fetchingPage = false
            }
        }
    }
}

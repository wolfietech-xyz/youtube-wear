package ca.wolfietech.dev.android.ytwear

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

enum class WatchMode { Reels, Videos }

/** The signed-in account's likes on YouTube. Both run off the main thread. */
interface Likes {
    /** Whether YouTube has the video liked; null when signed out. */
    suspend fun get(id: String): Boolean?

    /** Checks YouTube first, changes it if needed, returns the state after. Throws on failure. */
    suspend fun set(id: String, liked: Boolean): Boolean
}

/**
 * A list of videos being watched and which one is playing. Reels come from a paged feed and
 * loop; videos come from a fixed list and advance when one ends.
 */
class WatchSession(
    val mode: WatchMode,
    private val player: ExoPlayer,
    private val loader: VideoLoader,
    private val likes: Likes,
    private val settings: Settings,
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
    /** The player is filling its buffer: before the first frame, or stalled mid-video. */
    var buffering by mutableStateOf(false)
        private set
    /** Something to wait for: the stream being found, or the player buffering. */
    val waiting get() = loading || buffering
    var error by mutableStateOf<String?>(null)
        private set
    var paused by mutableStateOf(false)
        private set
    var aspectRatio by mutableFloatStateOf(if (mode == WatchMode.Reels) 9f / 16f else 16f / 9f)
        private set

    /** What the screen shows as liked: the user's latest wish, else YouTube's state. */
    val liked = mutableStateMapOf<String, Boolean>()
    /** Bumped on every like or unlike, so the screen can animate a heart. */
    var likeBursts by mutableIntStateOf(0)
        private set
    /** Whether the latest burst was a like (red heart) or an unlike. */
    var lastBurstLiked by mutableStateOf(true)
        private set
    /** Shown briefly when a like fails. */
    var notice by mutableStateOf<String?>(null)

    /** YouTube's state as last confirmed, to roll back to when a change fails. */
    private val confirmedLikes = mutableMapOf<String, Boolean>()
    /** Videos with a like request in flight; at most one worker per video. */
    private val syncingLikes = mutableSetOf<String>()

    private var pageToken: String? = null
    private var morePages = nextPage != null
    private var fetchingPage = false
    private var playJob: Job? = null
    /** Gives up on a video that takes longer than the video timeout to load or buffer. */
    private var watchdog: Job? = null
    /** Set by a long press: keep waiting for the current video however long it takes. */
    var waitingWithoutTimeout by mutableStateOf(false)
        private set

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
            buffering = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_BUFFERING -> if (watchdog?.isActive != true) armWatchdog()
                Player.STATE_READY -> watchdog?.cancel()
                Player.STATE_ENDED -> {
                    watchdog?.cancel()
                    if (mode == WatchMode.Videos) next()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed", error)
            this@WatchSession.error = "Playback: ${error.errorCodeName}"
        }
    }

    fun start(initialIds: List<String> = emptyList(), startIndex: Int = 0) {
        player.addListener(listener)
        // Shorts loop like they do in the YouTube app.
        player.repeatMode = if (mode == WatchMode.Reels) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        ids.addAll(initialIds)
        if (ids.isNotEmpty()) select(startIndex.coerceIn(ids.indices)) else loadMore(thenSelectFirst = true)
    }

    fun close() {
        playJob?.cancel()
        watchdog?.cancel()
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
        waitingWithoutTimeout = false
        armWatchdog()
        playJob = scope.launch {
            try {
                val video = try {
                    loader.load(id).await()
                } finally {
                    // Only now look ahead, so the video being watched gets the network and CPU
                    // to itself. Skipped if the user scrolled on (this job is then cancelled).
                    if (isActive) loader.prefetch(ids.drop(newIndex + 1).take(2))
                }
                current = video
                Log.i(TAG, "Playing '${video.title}': ${video.video.description} + ${video.audio?.description}")
                player.playVideo(video)
                refreshLike(id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Couldn't load $id", e)
                watchdog?.cancel()
                error = (e.message?.lineSequence()?.firstOrNull() ?: e.toString()) + ". Tap to retry"
            } finally {
                loading = false
            }
        }
        if (newIndex >= ids.size - 4) loadMore()
    }

    fun next() = select(index + 1)

    /** Loads the current video again after it failed or timed out. */
    fun retry() {
        val i = index
        index = -1
        select(i)
    }

    /**
     * Long press, for bad networks: stops the video timeout for the current video. On a
     * video that already failed or timed out, retries it without a timeout.
     */
    fun waitWithoutTimeout() {
        if (error != null) retry()
        watchdog?.cancel()
        waitingWithoutTimeout = true
        Log.i(TAG, "Waiting for ${ids.getOrNull(index)} without a timeout")
    }

    private fun armWatchdog() {
        watchdog?.cancel()
        if (waitingWithoutTimeout) return
        val seconds = settings.videoTimeoutSeconds
        val watching = index
        watchdog = scope.launch {
            delay(seconds * 1_000L)
            if (index != watching) return@launch
            Log.w(TAG, "Gave up on ${ids.getOrNull(watching)} after $seconds s")
            playJob?.cancel()
            player.stop()
            loading = false
            error = "Timed out after $seconds s. Tap to retry"
        }
    }

    /** Restarts the current video if it's a few seconds in, like most players; else goes back. */
    fun previous() {
        if (player.currentPosition > 3_000 || index == 0) player.seekTo(0) else select(index - 1)
    }

    /** Double-tap: likes the current video, or un-likes it if it's already liked. */
    fun toggleLike() {
        val id = current?.id ?: run { notice = "Still loading"; return }
        val want = liked[id] != true
        liked[id] = want
        lastBurstLiked = want
        likeBursts++
        if (id !in syncingLikes) syncLike(id)
    }

    /**
     * Brings YouTube in line with [liked] for one video. A single worker per video sends one
     * request at a time and loops while the user keeps tapping, so the last tap wins and
     * requests can't overtake each other. A failure only rolls the screen back if the user
     * hasn't changed their mind since; otherwise it goes on to their newer choice.
     */
    private fun syncLike(id: String) {
        syncingLikes += id
        scope.launch {
            try {
                while (true) {
                    val want = liked[id] ?: break
                    if (confirmedLikes[id] == want) break
                    try {
                        val seconds = settings.likeTimeoutSeconds
                        val actual = try {
                            withTimeout(seconds * 1_000L) { likes.set(id, want) }
                        } catch (e: TimeoutCancellationException) {
                            // The request may still land later; the next like checks YouTube first.
                            throw RuntimeException("${if (want) "Like" else "Unlike"} timed out after $seconds s", e)
                        }
                        confirmedLikes[id] = actual
                        Log.i(TAG, "${if (actual) "Liked" else "Unliked"} $id")
                        // YouTube's answer wins unless the user tapped again meanwhile.
                        if (liked[id] == want) {
                            liked[id] = actual
                            break
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Couldn't ${if (want) "like" else "unlike"} $id", e)
                        if (liked[id] == want) {
                            confirmedLikes[id]?.let { liked[id] = it } ?: liked.remove(id)
                            notice = shortMessage(e, if (want) "Couldn't like" else "Couldn't unlike")
                            break
                        }
                    }
                }
            } finally {
                syncingLikes -= id
            }
        }
    }

    /** Shows YouTube's actual like state for a video, unless the user already tapped it. */
    private fun refreshLike(id: String) {
        if (id in liked || id in syncingLikes) return
        scope.launch {
            try {
                val actual = likes.get(id) ?: return@launch
                confirmedLikes[id] = actual
                if (id !in liked && id !in syncingLikes) liked[id] = actual
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Couldn't read like state of $id: ${e.message?.lineSequence()?.lastOrNull()}")
            }
        }
    }

    private fun shortMessage(e: Exception, fallback: String) =
        e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }
            ?.replace(Regex("^\\w+(Error|Exception): "), "")?.take(80) ?: fallback

    fun togglePause() {
        if (error != null) return retry()
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

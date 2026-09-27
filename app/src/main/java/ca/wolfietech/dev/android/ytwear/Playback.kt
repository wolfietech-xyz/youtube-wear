package ca.wolfietech.dev.android.ytwear

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

fun buildPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()

/** Loads [video] and starts playing, merging the separate audio stream when there is one. */
fun ExoPlayer.playVideo(video: ResolvedVideo) {
    val source = video.audio
        ?.let { MergingMediaSource(mediaSource(video.video), mediaSource(it)) }
        ?: mediaSource(video.video)
    setMediaSource(source)
    prepare()
    play()
}

private fun mediaSource(stream: Stream): MediaSource {
    // yt-dlp's headers include the User-Agent the URL was signed for; YouTube rejects others.
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(stream.headers["User-Agent"])
        .setDefaultRequestProperties(stream.headers - "User-Agent")
        .setAllowCrossProtocolRedirects(true)
    return ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(stream.url))
}

/**
 * Resolves video IDs through yt-dlp and remembers the results, so swiping back is instant
 * and upcoming videos can be resolved ahead of time. A signed-in resolve takes ~15 s on a
 * watch (mostly QuickJS solving YouTube's challenge), so the video being watched comes
 * first: [load] starts at once, while [prefetch] works through its list one video at a
 * time in the background, and a new [prefetch] call replaces whatever was still queued.
 */
class VideoLoader(private val context: Context, private val scope: CoroutineScope) {
    private val cache = mutableMapOf<String, Deferred<ResolvedVideo>>()
    private val queued = ArrayDeque<String>()
    private var prefetcher: Job? = null

    /** Resolves [id] now (or returns the resolve already under way or done). */
    fun load(id: String): Deferred<ResolvedVideo> = synchronized(cache) {
        cache[id]?.takeUnless { it.isCompleted && it.getCompletionExceptionOrNull() != null }
            ?: scope.async(Dispatchers.IO) {
                Log.i(TAG, "Resolving $id")
                YtDlp.resolve(context, "https://www.youtube.com/watch?v=$id")
            }.also { cache[id] = it }
    }

    /** Resolves [ids] one after another in the background, dropping any earlier queue. */
    fun prefetch(ids: List<String>) {
        synchronized(queued) {
            queued.clear()
            queued.addAll(ids)
        }
        if (prefetcher?.isActive == true) return
        prefetcher = scope.launch {
            while (true) {
                val id = synchronized(queued) { queued.removeFirstOrNull() } ?: break
                runCatching { load(id).await() }
            }
        }
    }
}

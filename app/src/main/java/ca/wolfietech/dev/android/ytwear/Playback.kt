package ca.wolfietech.dev.android.ytwear

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

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

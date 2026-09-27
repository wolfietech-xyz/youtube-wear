"""yt-dlp entry points called from Kotlin through Chaquopy."""

import json

import yt_dlp

# What to play on a ~450 px round screen. Direct https files only (no HLS/DASH manifests),
# 360p or less, H.264 preferred because every watch decodes it in hardware. Separate video
# and audio streams are fine: ExoPlayer merges them.
WATCH_FORMAT = "/".join([
    "bv*[height<=360][vcodec^=avc1][protocol=https]+ba[ext=m4a][protocol=https]",
    "b[height<=360][vcodec^=avc1][protocol=https]",
    "bv*[height<=360][protocol=https]+ba[protocol=https]",
    "b[height<=360][protocol=https]",
    "b[protocol=https]",
])


def version():
    return yt_dlp.version.__version__


def _stream(f):
    return {
        "url": f["url"],
        "format_id": f.get("format_id"),
        "vcodec": f.get("vcodec"),
        "acodec": f.get("acodec"),
        "height": f.get("height"),
        "headers": f.get("http_headers") or {},
    }


def resolve(url):
    """Pick a watch-friendly stream for a video URL, without downloading.

    Returns JSON: id, title, duration, video (a stream) and audio (a stream, or null when
    the video stream already carries sound). Each stream has a direct url and the HTTP
    headers yt-dlp says to send with it.
    """
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
        "format": WATCH_FORMAT,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    requested = info.get("requested_formats")
    if requested:
        video, audio = _stream(requested[0]), _stream(requested[1])
    else:
        video, audio = _stream(info), None

    return json.dumps({
        "id": info.get("id"),
        "title": info.get("title"),
        "duration": info.get("duration"),
        "video": video,
        "audio": audio,
    })

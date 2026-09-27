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


def resolve(url, api_key=None, verbose=False, cookie_file=None, qjs_path=None):
    """Pick a watch-friendly stream for a video URL, without downloading.

    Returns JSON: id, title, duration, video (a stream) and audio (a stream, or null when
    the video stream already carries sound). Each stream has a direct url and the HTTP
    headers yt-dlp says to send with it. api_key, when given, is sent as the key parameter
    on yt-dlp's requests to YouTube's internal API. verbose logs yt-dlp's debug output.
    cookie_file is a Netscape cookies.txt from a signed-in YouTube session, the only sign-in
    yt-dlp supports for YouTube; yt-dlp writes refreshed cookies back to it.
    qjs_path is the QuickJS-ng executable yt-dlp uses to solve YouTube's JS challenges.
    """
    opts = {
        "quiet": not verbose,
        "no_warnings": not verbose,
        "verbose": verbose,
        "skip_download": True,
        "noplaylist": True,
        "format": WATCH_FORMAT,
    }
    if qjs_path:
        opts["js_runtimes"] = {"quickjs": {"path": qjs_path}}
    if cookie_file:
        opts["cookiefile"] = cookie_file
    if api_key:
        opts["extractor_args"] = {"youtube": {"innertube_key": [api_key]}}
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

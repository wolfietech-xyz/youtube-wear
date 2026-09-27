"""yt-dlp entry points called from Kotlin through Chaquopy."""

import json

import yt_dlp


def version():
    return yt_dlp.version.__version__


def extract(url):
    """Resolve a video URL without downloading. Returns JSON with the title and playable formats."""
    opts = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    formats = [
        {
            "id": f.get("format_id"),
            "ext": f.get("ext"),
            "height": f.get("height"),
            "vcodec": f.get("vcodec"),
            "acodec": f.get("acodec"),
            "url": f.get("url"),
        }
        for f in info.get("formats", [])
        if f.get("url")
    ]
    return json.dumps({
        "id": info.get("id"),
        "title": info.get("title"),
        "duration": info.get("duration"),
        "formats": formats,
    })

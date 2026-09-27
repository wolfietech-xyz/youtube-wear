"""yt-dlp entry points called from Kotlin through Chaquopy.

Every call takes the same settings, passed as keyword arguments from YtDlp.kt:
api_key     YouTube Data API v3 key. Sent as the key parameter on yt-dlp's requests to
            YouTube's internal API, and used for the search fallback of shorts_feed.
cookie_file Netscape cookies.txt from a signed-in YouTube session, the only sign-in yt-dlp
            supports for YouTube. yt-dlp writes refreshed cookies back to it.
qjs_path    QuickJS-ng executable yt-dlp uses to solve YouTube's JavaScript challenges.
verbose     Log yt-dlp's debug output.
"""

import base64
import json
import re
import urllib.parse

import yt_dlp

# What to play on a ~450 px round screen: direct https files only (no HLS/DASH manifests),
# separate video and audio are fine since ExoPlayer merges them. Sorting by res uses the
# shorter side, so portrait Shorts and landscape videos both land near 360p; H.264 is
# preferred because every watch decodes it in hardware.
WATCH_FORMAT = "bv*[protocol=https]+ba[protocol=https]/b[protocol=https]"
WATCH_FORMAT_SORT = ["res:360", "vcodec:h264", "acodec:aac"]

SHORTS_PAGE = "https://www.youtube.com/shorts"
SUBSCRIPTION_SHORTS = "https://www.youtube.com/feed/subscriptions/shorts"


def version():
    return yt_dlp.version.__version__


def _ydl(api_key=None, cookie_file=None, qjs_path=None, verbose=False, **extra):
    opts = {
        "quiet": not verbose,
        "no_warnings": not verbose,
        "verbose": verbose,
        "skip_download": True,
        "noplaylist": True,
        **extra,
    }
    if qjs_path:
        opts["js_runtimes"] = {"quickjs": {"path": qjs_path}}
    if cookie_file:
        opts["cookiefile"] = cookie_file
    if api_key:
        opts["extractor_args"] = {"youtube": {"innertube_key": [api_key]}}
    return yt_dlp.YoutubeDL(opts)


def _stream(f):
    return {
        "url": f["url"],
        "format_id": f.get("format_id"),
        "vcodec": f.get("vcodec"),
        "acodec": f.get("acodec"),
        "height": f.get("height"),
        "headers": f.get("http_headers") or {},
    }


def resolve(url, **settings):
    """Pick a watch-friendly stream for a video URL, without downloading.

    Returns JSON: id, title, channel, duration, view_count, like_count, upload_date
    (YYYYMMDD), description, video (a stream) and audio (a stream, or null when the video
    stream already carries sound). Each stream has a direct url and the HTTP headers
    yt-dlp says to send with it.
    """
    with _ydl(format=WATCH_FORMAT, format_sort=WATCH_FORMAT_SORT, **settings) as ydl:
        info = ydl.extract_info(url, download=False)

    requested = info.get("requested_formats")
    if requested:
        video, audio = _stream(requested[0]), _stream(requested[1])
    else:
        video, audio = _stream(info), None

    return json.dumps({
        "id": info.get("id"),
        "title": info.get("title"),
        "channel": info.get("channel") or info.get("uploader"),
        "duration": info.get("duration"),
        "view_count": info.get("view_count"),
        "like_count": info.get("like_count"),
        "upload_date": info.get("upload_date"),
        "description": info.get("description"),
        "video": video,
        "audio": audio,
    })


def shorts_feed(token=None, **settings):
    """One page of Shorts video IDs.

    Tries, in order: the account's own Shorts feed (personalised when signed in), Shorts
    from subscriptions (needs cookies), then a YouTube Data API search (needs api_key).
    Pass the returned token back to get the next page from the same source.
    Returns JSON: source, ids, token (null when there are no more pages).
    """
    source, _, value = (token or ":").partition(":")
    errors = []
    for name, fetch in (("feed", _reel_feed), ("subscriptions", _subscription_shorts),
                        ("search", _search_shorts)):
        if source and name != source:
            continue
        try:
            ids, next_value = fetch(value or None, settings)
        except Exception as e:
            errors.append(f"{name}: {e}")
            continue
        if ids:
            return json.dumps({
                "source": name,
                "ids": ids,
                "token": f"{name}:{next_value}" if next_value else None,
            })
        errors.append(f"{name}: no results")
    raise RuntimeError("No Shorts available (" + "; ".join(errors) + ")")


def like(video_id, **settings):
    """Likes a video as the signed-in account. Needs cookie_file; raises if YouTube refuses."""
    if not settings.get("cookie_file"):
        raise RuntimeError("Sign in (cookies.txt) to like videos")
    # No api_key: it only helps anonymous requests, and liking is tied to the account.
    settings = {**settings, "api_key": None}
    with _ydl(**settings) as ydl:
        ie = ydl.get_info_extractor("Youtube")
        ie._real_initialize()
        if not ie.is_authenticated:
            raise RuntimeError("YouTube cookies have expired; export new ones")
        ie._call_api("like/like", {"target": {"videoId": video_id}}, video_id, note=False)
    return True


def _sequence_params(video_id):
    # Protobuf YouTube's web client sends to start a Shorts sequence from one video.
    raw = b"\x0a\x0b" + video_id.encode() + b"\x2a\x02\x18\x05\x50\x19\x68\x00"
    return base64.urlsafe_b64encode(raw).decode()


def _reel_feed(continuation, settings):
    """YouTube's own Shorts feed through the internal reel endpoints yt-dlp doesn't cover."""
    with _ydl(**settings) as ydl:
        ie = ydl.get_info_extractor("Youtube")
        ie._real_initialize()
        ids = []
        if not continuation:
            # The Shorts page names a first Short to start the sequence from.
            page = ie._download_webpage(SHORTS_PAGE, None, note=False)
            seed = next(iter(re.findall(r"/shorts/([\w-]{11})", page)), None)
            if not seed:
                return [], None
            ids.append(seed)
            continuation = _sequence_params(seed)
        data = ie._call_api("reel/reel_watch_sequence", {"sequenceParams": continuation},
                            None, note=False)

    for entry in data.get("entries") or []:
        video_id = (entry.get("command", {}).get("reelWatchEndpoint") or {}).get("videoId")
        if video_id and video_id not in ids:
            ids.append(video_id)
    token = (((data.get("continuationEndpoint") or {}).get("continuationCommand") or {})
             .get("token"))
    return ids, token


def _subscription_shorts(start, settings):
    if not settings.get("cookie_file"):
        return [], None
    first = int(start or 1)
    with _ydl(extract_flat="in_playlist", playliststart=first, playlistend=first + 19,
              **settings) as ydl:
        info = ydl.extract_info(SUBSCRIPTION_SHORTS, download=False)
    ids = [e["id"] for e in info.get("entries") or [] if e.get("id")]
    return ids, str(first + 20) if len(ids) == 20 else None


def _search_shorts(page_token, settings):
    api_key = settings.get("api_key")
    if not api_key:
        return [], None
    query = urllib.parse.urlencode({
        "part": "id", "type": "video", "videoDuration": "short", "q": "#shorts",
        "maxResults": 25, "key": api_key, **({"pageToken": page_token} if page_token else {}),
    })
    with _ydl(**settings) as ydl:
        data = json.loads(ydl.urlopen(
            "https://www.googleapis.com/youtube/v3/search?" + query).read())
    ids = [item["id"]["videoId"] for item in data.get("items", [])]
    return ids, data.get("nextPageToken")

"""yt-dlp entry points called from Kotlin through Chaquopy.

Every call takes the same settings, passed as keyword arguments from YtDlp.kt:
api_key     YouTube Data API v3 key, for the Most Popular list and the Shorts search
            fallback. Restricted to this Android app in Google Cloud, so requests carry
            android_package and android_cert the way Google's Android libraries send them.
cookie_file Netscape cookies.txt from a signed-in YouTube session, the only sign-in yt-dlp
            supports for YouTube. yt-dlp writes refreshed cookies back to it.
qjs_path    QuickJS-ng executable yt-dlp uses to solve YouTube's JavaScript challenges.
verbose     Log yt-dlp's debug output.
region      Two-letter country code for the Most Popular chart: the watch's, or the region set in
            Settings. Or null. YouTube may ignore or reject it.
language    The watch's language code (for example "it"), sent as hl on the Most Popular chart, or null.
"""

# The Data API key is checked against the app's package and signing certificate.
_android_identity = {}

import base64
import json
import re
import urllib.parse

import yt_dlp
from yt_dlp.networking import Request

# What to play on a ~450 px round screen: direct https files only (no HLS/DASH manifests),
# separate video and audio are fine since ExoPlayer merges them. Sorting by res uses the
# shorter side, so portrait Shorts and landscape videos both land near 360p; H.264 is
# preferred because every watch decodes it in hardware.
WATCH_FORMAT = "bv*[protocol=https]+ba[protocol=https]/b[protocol=https]"
WATCH_FORMAT_SORT = ["res:360", "vcodec:h264", "acodec:aac"]

SHORTS_PAGE = "https://www.youtube.com/shorts"
SUBSCRIPTION_SHORTS = "https://www.youtube.com/feed/subscriptions/shorts"

# The error home_feed raises when YouTube says the login has no session.
LOGIN_REJECTED = "LOGIN_REJECTED"


def version():
    return yt_dlp.version.__version__


def _ydl(api_key=None, cookie_file=None, qjs_path=None, verbose=False, android_package=None,
         android_cert=None, region=None, language=None, **extra):
    if android_package and android_cert:
        _android_identity.update({"X-Android-Package": android_package, "X-Android-Cert": android_cert})
    opts = {
        "quiet": not verbose,
        "no_warnings": not verbose,
        "verbose": verbose,
        "skip_download": True,
        "noplaylist": True,
        **extra,
    }
    if region:
        opts["geo_bypass_country"] = region
    if qjs_path:
        opts["js_runtimes"] = {"quickjs": {"path": qjs_path}}
    if cookie_file:
        opts["cookiefile"] = cookie_file
    return yt_dlp.YoutubeDL(opts)


def _data_api(ydl, endpoint, **params):
    """GET a YouTube Data API v3 endpoint with the app's identity headers."""
    url = f"https://www.googleapis.com/youtube/v3/{endpoint}?" + urllib.parse.urlencode(params)
    return json.loads(ydl.urlopen(Request(url, headers=_android_identity)).read())


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
    for name, fetch in (("feed", _shorts_feed), ("subscriptions", _subscription_shorts),
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


def home_feed(**settings):
    """Videos for the menu: the account's own YouTube home feed. Needs the login cookies, and sends
    no region, so the account decides. Returns JSON: source, videos.

    Raises RuntimeError(LOGIN_REJECTED) only when YouTube itself says this login has no session.
    Network trouble and everything else raise other errors, so they can be told apart."""
    if not settings.get("cookie_file"):
        raise RuntimeError(LOGIN_REJECTED)
    failure = None
    try:
        videos = _flat_videos(":ytrec", settings)
    except Exception as e:
        videos, failure = [], e
    if videos:
        return json.dumps({"source": "home", "videos": videos})
    # Nothing came back. Ask YouTube whether it still accepts the login; if even that fails
    # (no network), that error is what the caller sees.
    if not _login_accepted(settings):
        raise RuntimeError(LOGIN_REJECTED)
    if failure:
        raise failure
    raise RuntimeError("YouTube's home feed came back empty")


def popular(**settings):
    """YouTube's Most Popular chart through the Data API. The one place a region matters: it is
    sent as regionCode when given (the watch's region, or the region set in Settings), and the watch's
    language as hl. If YouTube has no chart for that region (an error, or an empty list) it asks
    again without a region. Returns JSON: source, videos."""
    api_key = settings.get("api_key")
    if not api_key:
        raise RuntimeError("No YouTube API key in this build")
    region = settings.pop("region", None)
    language = settings.pop("language", None)

    def fetch(with_region):
        params = {"part": "snippet,contentDetails", "chart": "mostPopular", "maxResults": 30, "key": api_key}
        if with_region and region:
            params["regionCode"] = region
        if language:
            params["hl"] = language
        with _ydl(**settings) as ydl:
            return _data_api(ydl, "videos", **params)

    data = None
    if region:
        try:
            data = fetch(True)
        except Exception:
            data = None  # no chart for this region, perhaps: ask again without one
        if data is not None and not data.get("items"):
            data = None
    if data is None:
        data = fetch(False)
    videos = [{
        "id": item["id"],
        "title": item["snippet"]["title"],
        "channel": item["snippet"].get("channelTitle"),
        "duration": _iso_seconds(item.get("contentDetails", {}).get("duration")),
    } for item in data.get("items", [])]
    return json.dumps({"source": "popular", "videos": videos})


def _login_accepted(settings):
    """True or False when YouTube's home page says whether the cookies are a session; raises when it
    can't be read (no network, or a page without the answer, such as a consent screen)."""
    with _ydl(**settings) as ydl:
        page = ydl.urlopen(Request("https://www.youtube.com/")).read().decode("utf-8", "replace")
    m = re.search(r'"LOGGED_IN":(true|false)', page)
    if not m:
        raise RuntimeError("Couldn't tell whether YouTube accepts the login")
    return m.group(1) == "true"


def search(query, **settings):
    """Search results for the menu. Returns JSON: source, videos."""
    return json.dumps({"source": "search", "videos": _flat_videos(f"ytsearch30:{query}", settings)})


def _flat_videos(url, settings):
    """Video entries of a feed or search, without resolving each video (fast)."""
    with _ydl(extract_flat="in_playlist", playlistend=30, **settings) as ydl:
        info = ydl.extract_info(url, download=False)
    return [{
        "id": e["id"],
        "title": e.get("title") or "",
        "channel": e.get("channel") or e.get("uploader"),
        "duration": e.get("duration"),
    } for e in info.get("entries") or []
        # Skip channels and playlists mixed into results.
        if e.get("id") and len(e["id"]) == 11 and e.get("ie_key") in (None, "Youtube")]


def _iso_seconds(duration):
    """PT1H2M3S -> 3723."""
    m = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", duration or "")
    if not m:
        return None
    h, mins, s = (int(x or 0) for x in m.groups())
    return h * 3600 + mins * 60 + s


def like_status(video_id, **settings):
    """The signed-in account's rating of a video: LIKE, DISLIKE, INDIFFERENT, or SIGNED_OUT."""
    if not settings.get("cookie_file"):
        return "SIGNED_OUT"
    with _account(settings) as ie:
        if not ie.is_authenticated:
            return "SIGNED_OUT"
        return _read_like_status(ie, video_id)


def set_like(video_id, liked, **settings):
    """Makes the account's rating of a video LIKE (liked) or INDIFFERENT (not liked).

    Reads YouTube's current rating first and sends nothing if it already matches, so a
    watch that is out of step with YouTube (liked elsewhere, an earlier request that did
    go through) doesn't toggle it the wrong way. Reads it again afterwards, because YouTube
    can answer 200 and still not apply the change. Raises RuntimeError with a short,
    user-facing message on any failure. Returns JSON: status (YouTube's rating now) and
    changed (whether a request was sent).
    """
    if not settings.get("cookie_file"):
        raise RuntimeError("Sign in (cookies.txt) to like videos")
    want = "LIKE" if liked else "INDIFFERENT"
    with _account(settings) as ie:
        if not ie.is_authenticated:
            raise RuntimeError("YouTube sign-in expired; export new cookies")
        before = _read_like_status(ie, video_id)
        if before == want:
            return json.dumps({"status": before, "changed": False})
        # removelike clears a like or a dislike; like replaces a dislike.
        endpoint = "like/like" if liked else "like/removelike"
        _youtube_call(lambda: ie._call_api(
            endpoint, {"target": {"videoId": video_id}}, video_id, note=False))
        after = _read_like_status(ie, video_id)
    if after != want:
        raise RuntimeError(f"YouTube didn't apply it (still {after.lower()})")
    return json.dumps({"status": after, "changed": True})


class _account:
    """A YouTube extractor signed in with the cookies, for account actions."""

    def __init__(self, settings):
        self.ydl = _ydl(**settings)

    def __enter__(self):
        ie = self.ydl.__enter__().get_info_extractor("Youtube")
        ie._real_initialize()
        return ie

    def __exit__(self, *exc):
        return self.ydl.__exit__(*exc)


def _read_like_status(ie, video_id):
    data = _youtube_call(lambda: ie._call_api("next", {"videoId": video_id}, video_id, note=False))
    # likeStatusEntity keys are protobufs that embed the video ID; the response can also
    # carry entities for other videos, so match on the ID.
    for entity in _find_key(data, "likeStatusEntity"):
        key = urllib.parse.unquote(entity.get("key") or "")
        try:
            raw = base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))
        except ValueError:
            continue
        if video_id.encode() in raw and entity.get("likeStatus"):
            return entity["likeStatus"]
    raise RuntimeError("YouTube didn't say whether it's liked")


def _youtube_call(call):
    """Runs an internal API request, turning YouTube's failures into short messages."""
    try:
        return call()
    except yt_dlp.utils.ExtractorError as e:
        status = getattr(getattr(e, "cause", None), "status", None)
        if status in (401, 403):
            raise RuntimeError("YouTube sign-in expired; export new cookies") from e
        if status == 429:
            raise RuntimeError("YouTube is rate-limiting; try again later") from e
        if status:
            raise RuntimeError(f"YouTube error {status}") from e
        raise RuntimeError("Couldn't reach YouTube") from e


def _find_key(node, key):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == key:
                yield v
            else:
                yield from _find_key(v, key)
    elif isinstance(node, list):
        for item in node:
            yield from _find_key(item, key)


def _sequence_params(video_id):
    # Protobuf YouTube's web client sends to start a Shorts sequence from one video.
    raw = b"\x0a\x0b" + video_id.encode() + b"\x2a\x02\x18\x05\x50\x19\x68\x00"
    return base64.urlsafe_b64encode(raw).decode()


def _shorts_feed(continuation, settings):
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
    with _ydl(**settings) as ydl:
        data = _data_api(ydl, "search", part="id", type="video", videoDuration="short",
                         q="#shorts", maxResults=25, key=api_key,
                         **({"regionCode": settings["region"]} if settings.get("region") else {}),
                         **({"pageToken": page_token} if page_token else {}))
    ids = [item["id"]["videoId"] for item in data.get("items", [])]
    return ids, data.get("nextPageToken")

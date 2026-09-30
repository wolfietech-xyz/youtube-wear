package ca.wolfietech.dev.android.ytwear

import android.content.Context
import android.content.pm.PackageManager
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Kotlin side of app/src/main/python/ytwear.py. Calls block, so run them off the main thread. */
object YtDlp {
    private fun module(context: Context) = run {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        Python.getInstance().getModule("ytwear")
    }

    fun version(context: Context): String =
        module(context).callAttr("version").toString()

    /** Asks yt-dlp for a watch-sized stream of [url]. Throws if yt-dlp can't resolve it. */
    fun resolve(context: Context, url: String): ResolvedVideo {
        val json = JSONObject(call(context, "resolve", url).toString())
        return ResolvedVideo(
            id = json.getString("id"),
            title = json.optString("title"),
            channel = json.optStringOrNull("channel"),
            viewCount = json.optLongOrNull("view_count"),
            likeCount = json.optLongOrNull("like_count"),
            uploadDate = json.optStringOrNull("upload_date"),
            description = json.optStringOrNull("description"),
            video = json.getJSONObject("video").toStream(),
            audio = json.optJSONObject("audio")?.toStream(),
        )
    }

    /** One page of Shorts IDs. Pass the previous page's [FeedPage.token] to get the next one. */
    fun shortsFeed(context: Context, token: String?): FeedPage {
        val json = JSONObject(call(context, "shorts_feed", token).toString())
        val ids = json.getJSONArray("ids")
        return FeedPage(
            source = json.getString("source"),
            ids = List(ids.length()) { ids.getString(it) },
            token = json.optStringOrNull("token"),
        )
    }

    /** The menu's default list: the home feed, or YouTube's Most Popular when signed out. */
    /** The signed-in account's home feed. No region is sent; the account decides. */
    fun homeFeed(context: Context): VideoList = videoList(call(context, "home_feed").toString())

    /** The Most Popular chart, for the debug region override if set, else the watch's region. */
    fun popular(context: Context): VideoList =
        videoList(call(context, "popular", region = regionOverride(context) ?: localeRegion()).toString())

    /** Whether [e] is YouTube refusing the login, as opposed to any other failure. */
    fun isLoginRejected(e: Exception): Boolean = e.message?.contains("LOGIN_REJECTED") == true

    fun search(context: Context, query: String): VideoList = videoList(call(context, "search", query).toString())

    private fun videoList(text: String): VideoList {
        val json = JSONObject(text)
        val videos = json.getJSONArray("videos")
        return VideoList(
            source = json.getString("source"),
            videos = List(videos.length()) { i ->
                val v = videos.getJSONObject(i)
                VideoItem(
                    id = v.getString("id"),
                    title = v.optString("title"),
                    channel = v.optStringOrNull("channel"),
                    durationSeconds = v.optLongOrNull("duration")?.toInt(),
                )
            },
        )
    }

    /** Whether the signed-in account likes [videoId]; null when signed out. */
    fun likeStatus(context: Context, videoId: String): Boolean? =
        when (val status = call(context, "like_status", videoId).toString()) {
            "SIGNED_OUT" -> null
            else -> status == "LIKE"
        }

    /**
     * Likes or un-likes [videoId], checking YouTube's current state first. Returns whether
     * it's liked on YouTube afterwards. Throws with a short, user-facing message on failure.
     */
    fun setLike(context: Context, videoId: String, liked: Boolean): Boolean {
        val json = JSONObject(call(context, "set_like", videoId, liked).toString())
        return json.getString("status") == "LIKE"
    }

    /** Signed-in YouTube cookies (Netscape cookies.txt), if present. Private to the app. */
    fun cookieFile(context: Context) = File(context.filesDir, "cookies.txt")

    /** Calls a ytwear function with the settings every call shares (see ytwear.py). */
    private fun call(
        context: Context,
        function: String,
        vararg args: Any?,
        region: String? = regionOverride(context),
    ): PyObject {
        // Built from app/src/main/cpp/quickjs-ng and extracted here at install time.
        val qjs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so").path
        return module(context).callAttr(
            function,
            *args,
            Kwarg("api_key", BuildConfig.YOUTUBE_API_KEY.ifEmpty { null }),
            Kwarg("cookie_file", cookieFile(context).takeIf { it.exists() }?.path),
            Kwarg("qjs_path", qjs),
            Kwarg("verbose", BuildConfig.DEBUG),
            Kwarg("region", region),
            Kwarg("android_package", context.packageName),
            Kwarg("android_cert", signingCertSha1(context)),
        )
    }

    /** The region set in debug builds' Settings, or null; release builds never use one. */
    private fun regionOverride(context: Context): String? =
        if (!BuildConfig.DEBUG) null
        else context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(Settings.REGION_KEY, null)?.takeIf { it.isNotEmpty() }

    /** The watch's country from its language setting, or null when there is none. */
    private fun localeRegion(): String? = Locale.getDefault().country.takeIf { it.length == 2 }

    private var certSha1: String? = null

    /** SHA-1 of the app's signing certificate, as the Data API key restriction expects it. */
    private fun signingCertSha1(context: Context): String? = certSha1 ?: runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
    }.getOrNull().also { certSha1 = it }

    private fun JSONObject.toStream(): Stream {
        val headers = getJSONObject("headers")
        return Stream(
            url = getString("url"),
            description = "${optString("format_id")} ${optString("vcodec")}/${optString("acodec")}",
            headers = headers.keys().asSequence().associateWith { headers.getString(it) },
        )
    }

    private fun JSONObject.optStringOrNull(name: String) =
        if (isNull(name)) null else optString(name).ifEmpty { null }

    private fun JSONObject.optLongOrNull(name: String) =
        if (isNull(name)) null else optLong(name)
}

/** A direct media URL plus the HTTP headers YouTube expects with it. */
data class Stream(val url: String, val description: String, val headers: Map<String, String>)

/** A playable video and what the info panel shows. [audio] is null when [video] carries sound. */
data class ResolvedVideo(
    val id: String,
    val title: String,
    val channel: String?,
    val viewCount: Long?,
    val likeCount: Long?,
    /** YYYYMMDD. */
    val uploadDate: String?,
    val description: String?,
    val video: Stream,
    val audio: Stream?,
)

/** A row in the video menu. */
data class VideoItem(val id: String, val title: String, val channel: String?, val durationSeconds: Int?)

/** [source] is home, popular or search. */
data class VideoList(val source: String, val videos: List<VideoItem>)

/** [source] is feed, subscriptions or search; [token] is null on the last page. */
data class FeedPage(val source: String, val ids: List<String>, val token: String?)

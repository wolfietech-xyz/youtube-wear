package ca.wolfietech.dev.android.ytwear

import android.content.Context
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

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

    /** Signed-in YouTube cookies (Netscape cookies.txt), if present. Private to the app. */
    fun cookieFile(context: Context) = File(context.filesDir, "cookies.txt")

    /** Calls a ytwear function with the settings every call shares (see ytwear.py). */
    private fun call(context: Context, function: String, vararg args: Any?): PyObject {
        // Built from app/src/main/cpp/quickjs-ng and extracted here at install time.
        val qjs = File(context.applicationInfo.nativeLibraryDir, "libqjs.so").path
        return module(context).callAttr(
            function,
            *args,
            Kwarg("api_key", BuildConfig.YOUTUBE_API_KEY.ifEmpty { null }),
            Kwarg("cookie_file", cookieFile(context).takeIf { it.exists() }?.path),
            Kwarg("qjs_path", qjs),
            Kwarg("verbose", BuildConfig.DEBUG),
        )
    }

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

/** [source] is feed, subscriptions or search; [token] is null on the last page. */
data class FeedPage(val source: String, val ids: List<String>, val token: String?)

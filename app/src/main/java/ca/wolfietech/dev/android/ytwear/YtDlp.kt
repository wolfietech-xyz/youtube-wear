package ca.wolfietech.dev.android.ytwear

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

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
        val json = JSONObject(module(context).callAttr("resolve", url).toString())
        return ResolvedVideo(
            title = json.optString("title"),
            video = json.getJSONObject("video").toStream(),
            audio = json.optJSONObject("audio")?.toStream(),
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
}

/** A direct media URL plus the HTTP headers YouTube expects with it. */
data class Stream(val url: String, val description: String, val headers: Map<String, String>)

/** [audio] is null when [video] already carries sound. */
data class ResolvedVideo(val title: String, val video: Stream, val audio: Stream?)

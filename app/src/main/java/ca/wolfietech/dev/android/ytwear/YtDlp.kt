package ca.wolfietech.dev.android.ytwear

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

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

    /** Returns yt-dlp's result as JSON: id, title, duration and formats with direct URLs. */
    fun extract(context: Context, url: String): String =
        module(context).callAttr("extract", url).toString()
}

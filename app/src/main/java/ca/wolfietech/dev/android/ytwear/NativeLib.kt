package ca.wolfietech.dev.android.ytwear

object NativeLib {
    init {
        System.loadLibrary("youtubewear")
    }

    external fun hello(): String
}

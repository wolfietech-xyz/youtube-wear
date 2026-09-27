#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_ca_wolfietech_dev_android_ytwear_NativeLib_hello(JNIEnv *env, jobject /* this */) {
    std::string message = "YouTube Wear\nnative ready";
    return env->NewStringUTF(message.c_str());
}

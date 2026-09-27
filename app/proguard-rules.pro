# Keep JNI entry points: native code looks these classes and methods up by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

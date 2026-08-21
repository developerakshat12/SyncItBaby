# Keep JNI native methods so C++ can call Kotlin and vice-versa
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep audio engine classes, JNI callbacks, and related audio models
-keep class com.example.greetingcard.audio.** { *; }

# Keep Oboe JNI bindings if needed
-keep class com.google.oboe.** { *; }

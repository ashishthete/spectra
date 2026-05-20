# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.lite.**

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ML Kit Face Detection
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }

# MediaPipe (hand gesture detection)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Hilt
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# CameraX
-keep class androidx.camera.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Data classes used in serialization
-keep class com.spectra.core.model.** { *; }
-keep class com.spectra.ai.model.** { *; }
-keep class com.spectra.ai.cloud.** { *; }

# Keep enum entries
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

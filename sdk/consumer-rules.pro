# UseSense SDK - Consumer ProGuard Rules
# These rules are automatically applied to apps using this SDK

# Keep all public API classes and interfaces
-keep class com.usesense.sdk.** { *; }
-keep interface com.usesense.sdk.** { *; }
-keepclassmembers class com.usesense.sdk.** {
    public *;
}

# Keep result/config data classes for serialization
-keepclassmembers class com.usesense.sdk.UseSenseResult { *; }
-keepclassmembers class com.usesense.sdk.UseSenseConfig { *; }
-keepclassmembers class com.usesense.sdk.UseSenseEvent { *; }
-keepclassmembers class com.usesense.sdk.UseSenseError { *; }

# Keep API model classes (Moshi serialization)
-keep class com.usesense.sdk.api.models.** { *; }
-keepclassmembers class com.usesense.sdk.api.models.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Moshi
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <fields>;
    @com.squareup.moshi.* <methods>;
}

# MediaPipe (v4.1: FaceMesh for 3D liveness)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Google Play Integrity
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**

# ML Kit Face Detection (for RMAS action validation)
-keep class com.google.mlkit.vision.face.** { *; }
-dontwarn com.google.mlkit.vision.face.**

# ── Release builds (R8) with MediaPipe ──────────────────────────────────────
# MediaPipe logs through Flogger, which finds its caller by walking the stack
# for its own class names. If R8 renames them, Graph.<clinit> throws
# "no caller found on the stack" and the app crashes when the face step starts
# (found 2026-11-07 on a Flutter release build).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
# tasks-vision ships the AutoValue annotation processor on the runtime
# classpath; it references compile-time-only javax.lang.model classes and
# fails R8 with "Missing class" unless these are suppressed.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.auto.common.**

# MediaPipe's graph config and landmarker options are protobuf-lite messages,
# including well-known types that live in com.google.protobuf. protobuf-lite
# reads message fields by name at runtime, so if R8 renames them the landmarker
# fails to start ("Unable to get message info for com.google.protobuf.g") and
# no face mesh is captured. Seen on a Flutter release build of 4.16.0
# (2026-11-20); Flutter enables R8 for release by default.
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**

# DeviceSignalCollector looks GoogleApiAvailability up by reflection (so the SDK
# needs no compile-time play-services-base dependency). Renamed, the lookup
# fails and every minified app reports "no Play services", which DeepSense
# scores as a custom ROM.
-keep class com.google.android.gms.common.GoogleApiAvailability {
    public static ** getInstance();
    public int isGooglePlayServicesAvailable(android.content.Context);
}

# TensorFlow Lite calls back into Java from native code by name.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit (face detection, document scanner) assembles itself at app start from
# component registrars named in the merged manifest (MlKitInitProvider ->
# ComponentDiscovery). Newer R8 (AGP 9) renames or removes registrars it only
# sees as manifest strings, and the app crashes on launch with "Unsatisfied
# dependency for component ... mlkit.vision.face.internal" (native example on
# AGP 9.1, release + minify, 2026-11-21).
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.common.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-dontwarn com.google.mlkit.**

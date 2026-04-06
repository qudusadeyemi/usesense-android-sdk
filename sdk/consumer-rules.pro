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

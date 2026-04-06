# UseSense SDK ProGuard Rules
-keep class com.usesense.sdk.api.models.** { *; }
-keep class com.usesense.sdk.UseSenseResult { *; }
-keep class com.usesense.sdk.UseSenseError { *; }

# MediaPipe (v4.1: FaceMesh for 3D liveness)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Google Play Integrity
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**

# ML Kit Face Detection (for RMAS action validation)
-keep class com.google.mlkit.vision.face.** { *; }
-dontwarn com.google.mlkit.vision.face.**

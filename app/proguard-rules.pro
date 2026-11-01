# MediaPipe + CameraX release rules (minify is off, this is belt-and-braces).
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class androidx.camera.** { *; }

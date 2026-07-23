# Keep Flutter
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Keep WebRTC
-keep class org.webrtc.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }

# Keep our providers
-keep class com.example.background.** { *; }

# Flutter Play Core (deferred components) — ignore missing classes
-dontwarn com.google.android.play.core.**
-dontwarn io.flutter.embedding.android.FlutterPlayStoreSplitApplication
-dontwarn io.flutter.embedding.engine.deferredcomponents.PlayStoreDeferredComponentManager

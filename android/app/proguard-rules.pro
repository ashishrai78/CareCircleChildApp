# ====================================================================
# CareCircle ProGuard Rules — Production
# ====================================================================

# ============ Flutter ============
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn io.flutter.embedding.**

# ============ WebRTC ============
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ============ Firebase (Native SDK) ============
-keep class com.google.firebase.** { *; }
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.common.** { *; }
-keep class com.google.firebase.internal.** { *; }
-dontwarn com.google.firebase.**

# Firestore internal protobuf & gRPC
-keep class com.google.cloud.** { *; }
-keep class com.google.api.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.protobuf.**

# ============ Google Play Services (Location) ============
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.android.gms.**

# ============ Kotlin Coroutines ============
-keepclassmembers class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ============ Kotlin Metadata ============
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ============ App's Own Classes (CRITICAL — Firestore models) ============
-keep class com.example.background.** { *; }
-keepclassmembers class com.example.background.** { *; }

# ============ Gson Serialization ============
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============ Firestore Map/HashMap Serialization ============
# Critical: FirestoreClient writes Map<String, Any?> — keep Map types
-keep class java.util.HashMap { *; }
-keep class java.util.LinkedHashMap { *; }
-keep class java.util.TreeMap { *; }
-keep class java.util.Map { *; }
-keep class java.util.ArrayList { *; }
-keep class java.util.List { *; }
-keepclassmembers class * implements java.io.Serializable { *; }
-keep class java.lang.Number { *; }

# ============ AndroidX ============
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.work.** { *; }
-keep class androidx.multidex.** { *; }
-dontwarn androidx.**

# ============ NotificationCompat ============
-keep class androidx.core.app.NotificationCompat$Builder { *; }
-keep class androidx.core.app.NotificationCompat { *; }

# ============ Reflection & lambdas (Kotlin) ============
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Kotlin lambdas (Firestore callbacks use them)
-keepclassmembers class * {
    @kotlin.jvm.JvmStatic <methods>;
}

# ============ Flutter Play Core (deferred components) ============
-dontwarn com.google.android.play.core.**
-dontwarn io.flutter.embedding.android.FlutterPlayStoreSplitApplication
-dontwarn io.flutter.embedding.engine.deferredcomponents.PlayStoreDeferredComponentManager

# ============ Misc optimizations ============
# Disable warnings about missing optional parts
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
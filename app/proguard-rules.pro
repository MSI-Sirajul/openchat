# Add project specific ProGuard rules here.
# OpenChat R8 & ProGuard Optimization Configuration

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# WebRTC native library & JNI preservation
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepclassmembers class * {
    native <methods>;
}

# Firebase Realtime Database & Storage
-keep class com.msi.openchat.data.remote.firebase.** { *; }
-keep class com.msi.openchat.data.remote.storage.** { *; }
-keepclassmembers class com.msi.openchat.data.model.** {
    <init>(...);
    <fields>;
}

# Moshi & JSON models
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.msi.openchat.data.model.** { *; }
-keepclassmembers class com.msi.openchat.data.model.** { *; }

# Room SQLite database entities and DAOs
-keep class com.msi.openchat.data.local.entity.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# AndroidX Biometric
-keep class androidx.biometric.** { *; }

# ViewBinding preservation for native list adapters
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}

# AndroidX Navigation & ViewModel preservation
-keep class androidx.navigation.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Firebase Messaging
-dontwarn com.google.firebase.**
-keep class com.google.firebase.messaging.** { *; }

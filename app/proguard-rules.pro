# PadVideo ProGuard / R8 Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Kotlinx Serialization (official AAR rules are bundled automatically)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *; }

# PadVideo Data Models
-keep class com.padvideo.data.model.** { *; }

# AndroidX Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Google ML Kit Translate & Language ID
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# SMBJ, MBassador Event Bus, NTLM, BouncyCastle crypto & DCERPC Windows Share Enumeration
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
-dontwarn java.rmi.**
-dontwarn com.hierynomus.**
-dontwarn com.rapid7.**
-dontwarn net.engio.mbassy.**
-dontwarn org.slf4j.**
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.rapid7.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-keep class com.padvideo.data.repository.SmbRpcDecodingException { *; }
-keepclassmembers class com.hierynomus.** {
    @net.engio.mbassy.listener.Handler <methods>;
}

# Sherpa-ONNX Built-in Offline Neural Speech Recognition JNI Bindings
-dontwarn com.k2fsa.sherpa.onnx.**
-keep class com.k2fsa.sherpa.onnx.** { *; }

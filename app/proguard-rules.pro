# JNA needs its native bits and the classes it reflects on.
-dontwarn java.awt.*
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# UniFFI-generated bindings and our callback interfaces are invoked from native.
-keep class com.lightphone.spotify.ffi.** { *; }

# NativeInit JNI entrypoints must keep their names to resolve native symbols.
-keep class com.lightphone.spotify.NativeInit { *; }

# Path C AudioTrack sink — invoked from Rust player thread via JNI.
-keep class com.lightphone.spotify.audio.PhonoAudioTrackSink { *; }

# Media3
-keep class androidx.media3.** { *; }

# CameraX + ML Kit (QR scanner on Web API setup)
-keep class androidx.camera.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

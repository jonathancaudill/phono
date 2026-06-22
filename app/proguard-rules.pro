# JNA needs its native bits and the classes it reflects on.
-dontwarn java.awt.*
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# UniFFI-generated bindings and our callback interfaces are invoked from native.
-keep class com.lightphone.spotify.ffi.** { *; }

# NativeInit JNI entrypoint must keep its name to resolve the native symbol.
-keep class com.lightphone.spotify.NativeInit { *; }

# Media3
-keep class androidx.media3.** { *; }

//! Stub when audiotrack-sink feature is disabled but Kotlin still links registerAudioSink.
#![cfg(all(target_os = "android", not(feature = "audiotrack-sink")))]

use jni::objects::JClass;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_lightphone_spotify_NativeInit_registerAudioSink(
    _env: JNIEnv,
    _class: JClass,
) {
}

#[no_mangle]
pub extern "C" fn phono_attach_player_thread() {}

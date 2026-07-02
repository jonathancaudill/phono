//! Cached JNI bridge to [PhonoAudioTrackSink] on Android (Phase C drain path).

#![cfg(target_os = "android")]

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};

use jni::objects::{GlobalRef, JByteBuffer, JClass, JValue};
use jni::JNIEnv;

use crate::android_ctx;
use crate::audio_drain::DrainControl;

pub const ERROR_DEAD_OBJECT: i32 = -6;
pub const ERROR_INVALID_OPERATION: i32 = -3;

const BYTES_PER_SECOND: u64 = 176_400; // stereo S16 @ 44.1 kHz

static SINK_JNI: OnceLock<SinkJni> = OnceLock::new();
static WRITE_ERROR_COUNT: AtomicU32 = AtomicU32::new(0);
static DRAIN_CONTROL: Mutex<Option<std::sync::Arc<DrainControl>>> = Mutex::new(None);

struct SinkJni {
    class: GlobalRef,
    direct_buffer: Option<GlobalRef>,
}

fn env() -> Result<JNIEnv<'static>, String> {
    android_ctx::attach_current_thread_permanently();
    let vm = android_ctx::java_vm().ok_or("JavaVM not initialized")?;
    vm.get_env()
        .map_err(|e| format!("get_env failed: {e}"))
}

fn sink_jni() -> Result<&'static SinkJni, String> {
    SINK_JNI.get().ok_or_else(|| "Audio sink JNI not registered".into())
}

pub fn set_drain_control(control: std::sync::Arc<DrainControl>) {
    *DRAIN_CONTROL.lock().unwrap() = Some(control);
}

pub fn clear_drain_control() {
    *DRAIN_CONTROL.lock().unwrap() = None;
}

fn drain_control() -> Option<std::sync::Arc<DrainControl>> {
    DRAIN_CONTROL.lock().unwrap().clone()
}

/// Register JNI method IDs. Called from Kotlin before engine construction.
#[no_mangle]
pub extern "system" fn Java_com_lightphone_spotify_NativeInit_registerAudioSink(
    mut env: JNIEnv,
    _class: JClass,
) {
    if SINK_JNI.get().is_some() {
        return;
    }

    let class = match env.find_class("com/lightphone/spotify/audio/PhonoAudioTrackSink") {
        Ok(c) => c,
        Err(e) => {
            log::error!("registerAudioSink: class not found: {e}");
            return;
        }
    };

    let global = match env.new_global_ref(&class) {
        Ok(g) => g,
        Err(e) => {
            log::error!("registerAudioSink: global ref failed: {e}");
            return;
        }
    };

    let direct_buffer = env
        .call_static_method(
            &global,
            "prepareDirectBuffer",
            "(I)Ljava/nio/ByteBuffer;",
            &[JValue::Int(crate::pcm_ring::DRAIN_CHUNK_BYTES as i32)],
        )
        .ok()
        .and_then(|v| v.l().ok())
        .and_then(|buf| env.new_global_ref(buf).ok());

    let _ = SINK_JNI.set(SinkJni {
        class: global,
        direct_buffer,
    });
    log::info!("PhonoAudioTrackSink JNI registered (Phase C drain)");
}

#[allow(dead_code)]
#[no_mangle]
pub extern "C" fn phono_attach_player_thread() {
    android_ctx::attach_current_thread_permanently();
}

pub fn start(sample_rate: u32, channels: u8) -> Result<(), String> {
    let mut env = env()?;
    let jni = sink_jni()?;
    let ok = env
        .call_static_method(
            &jni.class,
            "start",
            "(II)Z",
            &[
                JValue::Int(sample_rate as i32),
                JValue::Int(channels as i32),
            ],
        )
        .map_err(|e| e.to_string())?
        .z()
        .map_err(|e| e.to_string())?;
    if ok {
        Ok(())
    } else {
        Err("PhonoAudioTrackSink.start returned false".into())
    }
}

pub fn write_pcm_direct(data: &[u8]) -> Result<i32, String> {
    if data.is_empty() {
        return Ok(0);
    }
    let mut env = env()?;
    let jni = sink_jni()?;

    if let Some(buf_ref) = jni.direct_buffer.as_ref() {
        let obj = buf_ref.as_obj();
        let byte_buf = JByteBuffer::from(
            env.new_local_ref(obj).map_err(|e| e.to_string())?,
        );
        let cap = env
            .get_direct_buffer_capacity(&byte_buf)
            .map_err(|e| e.to_string())?;
        if data.len() > cap {
            return Err(format!("direct buffer too small: need {} have {cap}", data.len()));
        }
        let addr = env
            .get_direct_buffer_address(&byte_buf)
            .map_err(|e| e.to_string())?;
        unsafe {
            std::ptr::copy_nonoverlapping(data.as_ptr(), addr, data.len());
        }
        let written = env
            .call_static_method(
                &jni.class,
                "writePcmDirect",
                "(Ljava/nio/ByteBuffer;I)I",
                &[JValue::Object(obj), JValue::Int(data.len() as i32)],
            )
            .map_err(|e| e.to_string())?
            .i()
            .map_err(|e| e.to_string())?;
        if written < 0 {
            WRITE_ERROR_COUNT.fetch_add(1, Ordering::Relaxed);
        }
        return Ok(written);
    }

    let arr = env
        .byte_array_from_slice(data)
        .map_err(|e| e.to_string())?;
    let written = env
        .call_static_method(
            &jni.class,
            "writePcmDirectBytes",
            "([BII)I",
            &[
                JValue::Object(&arr),
                JValue::Int(0),
                JValue::Int(data.len() as i32),
            ],
        )
        .map_err(|e| e.to_string())?
        .i()
        .map_err(|e| e.to_string())?;
    if written < 0 {
        WRITE_ERROR_COUNT.fetch_add(1, Ordering::Relaxed);
    }
    Ok(written)
}

pub fn flush() -> Result<(), String> {
    let mut env = env()?;
    let jni = sink_jni()?;
    env.call_static_method(&jni.class, "flush", "()V", &[])
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn pause_output() -> Result<(), String> {
    let mut env = env()?;
    let jni = sink_jni()?;
    env.call_static_method(&jni.class, "pauseOutput", "()V", &[])
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn resume_output() -> Result<(), String> {
    let mut env = env()?;
    let jni = sink_jni()?;
    env.call_static_method(&jni.class, "resumeOutput", "()V", &[])
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn stop() -> Result<(), String> {
    let mut env = env()?;
    let jni = sink_jni()?;
    env.call_static_method(&jni.class, "stop", "()V", &[])
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn release() -> Result<(), String> {
    let mut env = env()?;
    let jni = sink_jni()?;
    env.call_static_method(&jni.class, "release", "()V", &[])
        .map_err(|e| e.to_string())?;
    Ok(())
}

pub fn request_track_recreate() {
    let Ok(mut env) = env() else {
        return;
    };
    let Ok(jni) = sink_jni() else {
        return;
    };
    let _ = env.call_static_method(&jni.class, "requestRecreate", "()V", &[]);
}

pub fn routing_event_count() -> u32 {
    count_method("getRoutingEventCount")
}

pub fn dead_object_count() -> u32 {
    count_method("getDeadObjectCount")
}

pub fn write_error_count() -> u32 {
    WRITE_ERROR_COUNT
        .load(Ordering::Relaxed)
        .max(count_method("getWriteErrorCount"))
}

pub fn ring_occupancy_ms() -> u32 {
    let bytes = drain_control()
        .map(|c| c.ring_occupancy_bytes.load(Ordering::Relaxed))
        .unwrap_or(0);
    ms_from_bytes(bytes)
}

pub fn pending_output_ms() -> u32 {
    count_method("getPendingOutputMs")
}

pub fn producer_block_ms() -> u32 {
    drain_control()
        .map(|c| c.producer_block_ms.load(Ordering::Relaxed))
        .unwrap_or(0)
        .min(u32::MAX as u64) as u32
}

pub fn drain_partial_writes() -> u32 {
    drain_control()
        .map(|c| c.drain_partial_writes.load(Ordering::Relaxed))
        .unwrap_or(0)
        .min(u32::MAX as u64) as u32
}

fn ms_from_bytes(bytes: u64) -> u32 {
    if bytes == 0 {
        return 0;
    }
    ((bytes * 1000) / BYTES_PER_SECOND).min(u32::MAX as u64) as u32
}

fn count_method(name: &str) -> u32 {
    let Ok(mut env) = env() else {
        return 0;
    };
    let Ok(jni) = sink_jni() else {
        return 0;
    };
    env.call_static_method(&jni.class, name, "()I", &[])
        .ok()
        .and_then(|v| v.i().ok())
        .unwrap_or(0)
        .max(0) as u32
}

/// Legacy byte-path for debug fallback.
pub fn write_pcm(data: &[u8]) -> Result<(), String> {
    let written = write_pcm_direct(data)?;
    if written <= 0 {
        return Err(format!("AudioTrack write error ({written})"));
    }
    Ok(())
}

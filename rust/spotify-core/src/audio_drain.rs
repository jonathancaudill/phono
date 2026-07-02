//! Dedicated drain thread: SPSC ring → JNI → AudioTrack (Phase C).

#![cfg(all(target_os = "android", feature = "audiotrack-sink"))]

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::audio_sink_jni;
use crate::pcm_ring::{DRAIN_CHUNK_BYTES, PcmConsumer};

pub struct DrainControl {
    pub drain_pause: Arc<AtomicBool>,
    pub shutdown: Arc<AtomicBool>,
    pub ring_occupancy_bytes: Arc<AtomicU64>,
    pub drain_partial_writes: Arc<AtomicU64>,
    pub producer_block_ms: Arc<AtomicU64>,
}

impl DrainControl {
    pub fn new() -> Self {
        Self {
            drain_pause: Arc::new(AtomicBool::new(false)),
            shutdown: Arc::new(AtomicBool::new(false)),
            ring_occupancy_bytes: Arc::new(AtomicU64::new(0)),
            drain_partial_writes: Arc::new(AtomicU64::new(0)),
            producer_block_ms: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn pause_drain(&self) {
        self.drain_pause.store(true, Ordering::Release);
    }

    pub fn resume_drain(&self) {
        self.drain_pause.store(false, Ordering::Release);
    }

    pub fn request_shutdown(&self) {
        self.shutdown.store(true, Ordering::Release);
    }
}

pub struct AudioDrainThread {
    handle: Option<JoinHandle<()>>,
    control: Arc<DrainControl>,
}

impl AudioDrainThread {
    pub fn spawn(consumer: PcmConsumer, control: Arc<DrainControl>) -> Self {
        let ctrl = control.clone();
        let handle = thread::Builder::new()
            .name("PhonoAudioDrain".into())
            .spawn(move || drain_loop(consumer, ctrl))
            .expect("spawn PhonoAudioDrain");
        Self {
            handle: Some(handle),
            control,
        }
    }

    pub fn control(&self) -> Arc<DrainControl> {
        self.control.clone()
    }

    pub fn stop(&mut self) {
        self.control.request_shutdown();
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

impl Drop for AudioDrainThread {
    fn drop(&mut self) {
        self.stop();
    }
}

fn drain_loop(mut consumer: PcmConsumer, control: Arc<DrainControl>) {
    crate::android_ctx::attach_current_thread_permanently();
    let mut chunk = vec![0u8; DRAIN_CHUNK_BYTES];
    let mut pending: Vec<u8> = Vec::with_capacity(DRAIN_CHUNK_BYTES);

    while !control.shutdown.load(Ordering::Acquire) {
        if control.drain_pause.load(Ordering::Acquire) {
            // Flush/recreate barrier: discard ring data without writing to AudioTrack.
            let n = consumer.pop_slice(&mut chunk);
            if n > 0 {
                control
                    .ring_occupancy_bytes
                    .store(consumer.occupancy() as u64, Ordering::Relaxed);
                continue;
            }
            thread::sleep(Duration::from_millis(2));
            continue;
        }

        control
            .ring_occupancy_bytes
            .store(consumer.occupancy() as u64, Ordering::Relaxed);

        if pending.is_empty() {
            let avail = consumer.occupancy();
            if avail == 0 {
                thread::sleep(Duration::from_millis(1));
                continue;
            }
            let to_read = avail.min(DRAIN_CHUNK_BYTES);
            let n = consumer.pop_slice(&mut chunk[..to_read]);
            if n == 0 {
                thread::yield_now();
                continue;
            }
            pending.extend_from_slice(&chunk[..n]);
        }

        match audio_sink_jni::write_pcm_direct(&pending) {
            Ok(written) if written >= pending.len() as i32 => {
                pending.clear();
            }
            Ok(written) if written > 0 => {
                control.drain_partial_writes.fetch_add(1, Ordering::Relaxed);
                let w = written as usize;
                pending.drain(..w);
            }
            Ok(0) => {
                // AudioTrack buffer full (NON_BLOCKING) — retry immediately.
                thread::yield_now();
            }
            Ok(written) if written == audio_sink_jni::ERROR_INVALID_OPERATION => {
                thread::sleep(Duration::from_millis(2));
            }
            Ok(written) if written == audio_sink_jni::ERROR_DEAD_OBJECT => {
                audio_sink_jni::request_track_recreate();
                pending.clear();
                thread::sleep(Duration::from_millis(50));
            }
            Ok(_) => {
                thread::yield_now();
            }
            Err(e) => {
                log::warn!("drain write_pcm_direct failed: {e}");
                thread::sleep(Duration::from_millis(5));
            }
        }
    }
}

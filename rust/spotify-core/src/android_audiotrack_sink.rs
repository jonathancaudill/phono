//! Android [AudioTrack] sink — SPSC ring + drain thread (Phase C).

#![cfg(all(target_os = "android", feature = "audiotrack-sink"))]

use std::sync::Arc;
use std::time::{Duration, Instant};

use librespot::playback::audio_backend::{Open, Sink, SinkError, SinkResult};
use librespot::playback::config::AudioFormat;
use librespot::playback::convert::Converter;
use librespot::playback::decoder::AudioPacket;
use librespot::playback::{NUM_CHANNELS, SAMPLE_RATE};

use crate::audio_drain::{AudioDrainThread, DrainControl};
use crate::audio_sink_jni;
use crate::pcm_ring::{split_pcm_ring, HIGH_WATER_BYTES, PcmProducer};

struct SinkState {
    producer: PcmProducer,
    drain: AudioDrainThread,
    control: Arc<DrainControl>,
}

pub struct AndroidAudioTrackSink {
    format: AudioFormat,
    started: bool,
    state: Option<SinkState>,
    byte_scratch: Vec<u8>,
    s16_scratch: Vec<i16>,
}

impl AndroidAudioTrackSink {
    pub const NAME: &'static str = "audiotrack";
}

impl Open for AndroidAudioTrackSink {
    fn open(_device: Option<String>, format: AudioFormat) -> Self {
        Self {
            format,
            started: false,
            state: None,
            byte_scratch: Vec::with_capacity(16384),
            s16_scratch: Vec::with_capacity(4096),
        }
    }
}

impl Sink for AndroidAudioTrackSink {
    fn start(&mut self) -> SinkResult<()> {
        if self.started {
            if let Some(state) = self.state.as_ref() {
                state.control.resume_drain();
            }
            audio_sink_jni::resume_output().map_err(SinkError::StateChange)?;
            return Ok(());
        }
        // Claim ownership BEFORE (re)creating the physical track so any old drain
        // thread from a superseded player observes the newer epoch and stops
        // writing before we touch the AudioTrack.
        let epoch = audio_sink_jni::claim_sink_epoch();
        audio_sink_jni::start(SAMPLE_RATE, NUM_CHANNELS).map_err(|e| {
            SinkError::ConnectionRefused(format!("AudioTrack start failed: {e}"))
        })?;

        let (producer, consumer) = split_pcm_ring();
        let control = Arc::new(DrainControl::new(epoch));
        audio_sink_jni::set_drain_control(control.clone());
        let drain = AudioDrainThread::spawn(consumer, control.clone());
        self.state = Some(SinkState {
            producer,
            drain,
            control,
        });
        self.started = true;
        Ok(())
    }

    fn pause(&mut self) -> SinkResult<()> {
        // Transport pause: halt AudioTrack output only — keep ring + drain alive (gapless resume).
        audio_sink_jni::pause_output().map_err(SinkError::StateChange)?;
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        if !self.started {
            return Ok(());
        }
        let epoch = self.state.as_ref().map(|s| s.control.epoch);
        if let Some(mut state) = self.state.take() {
            state.control.request_shutdown();
            state.drain.stop();
        }
        if let Err(e) = audio_sink_jni::stop() {
            log::error!("AudioTrack stop failed (continuing): {e}");
        }
        if let Some(epoch) = epoch {
            audio_sink_jni::clear_drain_control(epoch);
        }
        self.started = false;
        Ok(())
    }

    fn flush(&mut self) -> SinkResult<()> {
        if let Some(state) = self.state.as_mut() {
            state.control.pause_drain();
            let deadline = Instant::now() + Duration::from_millis(100);
            while state.producer.occupancy() > 0 && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(2));
            }
            state.control.ring_occupancy_bytes.store(0, std::sync::atomic::Ordering::Relaxed);
            state.control.resume_drain();
        }
        audio_sink_jni::flush()
            .map_err(|e| SinkError::StateChange(format!("AudioTrack flush failed: {e}")))
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        self.byte_scratch.clear();

        match packet {
            AudioPacket::Samples(samples) => {
                if samples.is_empty() {
                    return Ok(());
                }
                match self.format {
                    AudioFormat::S16 => {
                        self.s16_scratch.clear();
                        self.s16_scratch.extend_from_slice(&converter.f64_to_s16(&samples));
                        append_i16_le(&mut self.byte_scratch, &self.s16_scratch);
                    }
                    AudioFormat::F64 => {
                        append_f64_le(&mut self.byte_scratch, &samples);
                    }
                    AudioFormat::F32 => {
                        let f32s = converter.f64_to_f32(&samples);
                        append_f32_le(&mut self.byte_scratch, &f32s);
                    }
                    AudioFormat::S32 => {
                        let s32s = converter.f64_to_s32(&samples);
                        append_i32_le(&mut self.byte_scratch, &s32s);
                    }
                    AudioFormat::S24 | AudioFormat::S24_3 => {
                        self.s16_scratch.clear();
                        self.s16_scratch.extend_from_slice(&converter.f64_to_s16(&samples));
                        append_i16_le(&mut self.byte_scratch, &self.s16_scratch);
                    }
                }
            }
            AudioPacket::Raw(raw) => self.byte_scratch.extend_from_slice(&raw),
        }

        if self.byte_scratch.is_empty() {
            return Ok(());
        }

        let state = self
            .state
            .as_mut()
            .ok_or_else(|| SinkError::NotConnected("AudioTrack sink not started".into()))?;

        let mut offset = 0usize;
        while offset < self.byte_scratch.len() {
            let n = state.producer.push_slice(&self.byte_scratch[offset..]);
            if n > 0 {
                offset += n;
                state.control.ring_occupancy_bytes.store(
                    state.producer.occupancy() as u64,
                    std::sync::atomic::Ordering::Relaxed,
                );
                continue;
            }
            if state.producer.occupancy() >= HIGH_WATER_BYTES {
                let start = Instant::now();
                std::thread::sleep(Duration::from_millis(5));
                state.control.producer_block_ms.fetch_add(
                    start.elapsed().as_millis() as u64,
                    std::sync::atomic::Ordering::Relaxed,
                );
            } else {
                std::thread::sleep(Duration::from_millis(1));
            }
        }
        Ok(())
    }
}

impl Drop for AndroidAudioTrackSink {
    fn drop(&mut self) {
        if self.started {
            let _ = self.stop();
        }
        let _ = audio_sink_jni::release();
    }
}

fn append_i16_le(out: &mut Vec<u8>, samples: &[i16]) {
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
}

fn append_i32_le(out: &mut Vec<u8>, samples: &[i32]) {
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
}

fn append_f32_le(out: &mut Vec<u8>, samples: &[f32]) {
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
}

fn append_f64_le(out: &mut Vec<u8>, samples: &[f64]) {
    for s in samples {
        out.extend_from_slice(&s.to_le_bytes());
    }
}

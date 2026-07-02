//! Lock-free SPSC byte ring for Phase C decoupled AudioTrack output.

use ringbuf::{traits::*, HeapRb};

/// ~635 ms stereo S16 @ 44.1 kHz.
pub const RING_CAPACITY_BYTES: usize = 112 * 1024;
/// Block producer when ring holds more than ~450 ms (rodio / MAX_PENDING_MS parity).
pub const HIGH_WATER_BYTES: usize = 79_380;
/// Drain thread urgency threshold (~50 ms).
pub const LOW_WATER_BYTES: usize = 8_820;
/// Batch size for drain → JNI → AudioTrack.
pub const DRAIN_CHUNK_BYTES: usize = 8192;

pub struct PcmProducer {
    inner: ringbuf::HeapProd<u8>,
}

pub struct PcmConsumer {
    inner: ringbuf::HeapCons<u8>,
}

pub fn split_pcm_ring() -> (PcmProducer, PcmConsumer) {
    let rb = HeapRb::<u8>::new(RING_CAPACITY_BYTES);
    let (prod, cons) = rb.split();
    (PcmProducer { inner: prod }, PcmConsumer { inner: cons })
}

impl PcmProducer {
    pub fn push_slice(&mut self, data: &[u8]) -> usize {
        self.inner.push_slice(data)
    }

    pub fn occupancy(&self) -> usize {
        self.inner.occupied_len()
    }
}

impl PcmConsumer {
    pub fn pop_slice(&mut self, out: &mut [u8]) -> usize {
        self.inner.pop_slice(out)
    }

    pub fn occupancy(&self) -> usize {
        self.inner.occupied_len()
    }

    pub fn discard_all(&mut self) {
        let mut scratch = [0u8; 4096];
        while self.inner.pop_slice(&mut scratch) > 0 {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_pop_roundtrip() {
        let (mut prod, mut cons) = split_pcm_ring();
        let data = [1u8, 2, 3, 4, 5, 6];
        assert_eq!(prod.push_slice(&data), data.len());
        assert_eq!(prod.occupancy(), 6);
        let mut out = [0u8; 6];
        assert_eq!(cons.pop_slice(&mut out), 6);
        assert_eq!(out, data);
    }

    #[test]
    fn partial_push_when_full() {
        let (mut prod, _cons) = split_pcm_ring();
        let chunk = vec![7u8; RING_CAPACITY_BYTES];
        assert_eq!(prod.push_slice(&chunk), RING_CAPACITY_BYTES);
        assert_eq!(prod.push_slice(&[1]), 0);
    }

    #[test]
    fn discard_empties_ring() {
        let (mut prod, mut cons) = split_pcm_ring();
        prod.push_slice(&[1, 2, 3]);
        cons.discard_all();
        assert_eq!(prod.occupancy(), 0);
        assert_eq!(cons.occupancy(), 0);
    }
}

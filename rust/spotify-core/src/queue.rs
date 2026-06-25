use librespot::core::SpotifyUri;
use rand::seq::SliceRandom;

/// Local playback queue with shuffle and repeat modes.
#[derive(Clone, Debug)]
pub(crate) struct QueueState {
    uris: Vec<SpotifyUri>,
    /// Playback order: `positions[i]` is an index into `uris`.
    positions: Vec<usize>,
    index: usize,
    position_ms: u32,
    shuffle: bool,
    repeat_context: bool,
    repeat_track: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RepeatMode {
    Off,
    Context,
    Track,
}

impl Default for QueueState {
    fn default() -> Self {
        Self {
            uris: Vec::new(),
            positions: Vec::new(),
            index: 0,
            position_ms: 0,
            shuffle: false,
            repeat_context: false,
            repeat_track: false,
        }
    }
}

impl QueueState {
    pub fn shuffle(&self) -> bool {
        self.shuffle
    }

    pub fn repeat_mode(&self) -> RepeatMode {
        if self.repeat_track {
            RepeatMode::Track
        } else if self.repeat_context {
            RepeatMode::Context
        } else {
            RepeatMode::Off
        }
    }

    pub fn position_ms(&self) -> u32 {
        self.position_ms
    }

    pub fn set_position_ms(&mut self, position_ms: u32) {
        self.position_ms = position_ms;
    }

    pub fn set_queue(&mut self, uris: Vec<SpotifyUri>, start_index: usize) {
        self.uris = uris;
        self.positions = (0..self.uris.len()).collect();
        self.index = start_index.min(self.uris.len().saturating_sub(1));
        self.position_ms = 0;
        self.shuffle = false;
        self.repeat_context = false;
        self.repeat_track = false;
    }

    pub fn restore_linear(&mut self, uris: Vec<SpotifyUri>, uri_index: usize, position_ms: u32) {
        self.uris = uris;
        self.positions = (0..self.uris.len()).collect();
        self.index = uri_index.min(self.uris.len().saturating_sub(1));
        self.position_ms = position_ms;
        self.shuffle = false;
        self.repeat_context = false;
        self.repeat_track = false;
    }

    pub fn snapshot_resume(&self) -> Option<(Vec<SpotifyUri>, usize, u32)> {
        if self.uris.is_empty() {
            None
        } else {
            let uri_index = self.positions.get(self.index).copied().unwrap_or(0);
            Some((self.uris.clone(), uri_index, self.position_ms))
        }
    }

    pub fn current_uri(&self) -> Option<SpotifyUri> {
        self.positions
            .get(self.index)
            .and_then(|&uri_index| self.uris.get(uri_index))
            .cloned()
    }

    pub fn set_shuffle(&mut self, enabled: bool) {
        if self.shuffle == enabled {
            return;
        }
        let restore_uri_index = self
            .positions
            .get(self.index)
            .copied()
            .unwrap_or(0);
        self.shuffle = enabled;
        self.rebuild_positions(restore_uri_index);
    }

    pub fn toggle_shuffle(&mut self) -> bool {
        self.set_shuffle(!self.shuffle);
        self.shuffle
    }

    pub fn toggle_repeat(&mut self) -> RepeatMode {
        let next = match self.repeat_mode() {
            RepeatMode::Off => RepeatMode::Context,
            RepeatMode::Context => RepeatMode::Track,
            RepeatMode::Track => RepeatMode::Off,
        };
        self.set_repeat_mode(next);
        next
    }

    fn set_repeat_mode(&mut self, mode: RepeatMode) {
        match mode {
            RepeatMode::Off => {
                self.repeat_context = false;
                self.repeat_track = false;
            }
            RepeatMode::Context => {
                self.repeat_context = true;
                self.repeat_track = false;
            }
            RepeatMode::Track => {
                self.repeat_context = false;
                self.repeat_track = true;
            }
        }
    }

    pub fn skip_next(&mut self, user_initiated: bool) -> Option<SpotifyUri> {
        if user_initiated && self.repeat_track {
            self.repeat_track = false;
        }
        if self.index + 1 < self.positions.len() {
            self.index += 1;
            self.position_ms = 0;
            return self.current_uri();
        }
        if self.repeat_context {
            self.index = 0;
            self.position_ms = 0;
            return self.current_uri();
        }
        None
    }

    pub fn skip_prev(&mut self, user_initiated: bool) -> Option<SpotifyUri> {
        if user_initiated && self.repeat_track {
            self.repeat_track = false;
        }
        if self.index > 0 {
            self.index -= 1;
            self.position_ms = 0;
            return self.current_uri();
        }
        None
    }

    pub fn end_of_track(&mut self) -> Option<SpotifyUri> {
        if self.repeat_track {
            self.position_ms = 0;
            return self.current_uri();
        }
        if self.index + 1 < self.positions.len() {
            self.index += 1;
            self.position_ms = 0;
            return self.current_uri();
        }
        if self.repeat_context {
            self.index = 0;
            self.position_ms = 0;
            return self.current_uri();
        }
        None
    }

    pub fn next_preload_uri(&self) -> Option<SpotifyUri> {
        if self.repeat_track {
            return self.current_uri();
        }
        if self.index + 1 < self.positions.len() {
            let uri_index = self.positions[self.index + 1];
            return self.uris.get(uri_index).cloned();
        }
        if self.repeat_context {
            return self.positions.first().and_then(|&uri_index| self.uris.get(uri_index).cloned());
        }
        None
    }

    fn rebuild_positions(&mut self, restore_uri_index: usize) {
        self.positions = (0..self.uris.len()).collect();
        if self.shuffle && self.positions.len() > 1 {
            let cur_pos_idx = self.index.min(self.positions.len() - 1);
            if cur_pos_idx > 0 {
                self.positions.swap(0, cur_pos_idx);
            }
            if self.positions.len() > 2 {
                self.positions[1..].shuffle(&mut rand::thread_rng());
            }
            self.index = 0;
        } else {
            self.index = restore_uri_index.min(self.uris.len().saturating_sub(1));
        }
    }
}

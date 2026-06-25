use librespot::core::SpotifyUri;
use rand::seq::SliceRandom;

#[derive(Clone, Debug, PartialEq, Eq)]
enum QueueEntry {
    Manual(SpotifyUri),
    Context(usize),
}

/// Local playback queue with shuffle and repeat modes.
#[derive(Clone, Debug)]
pub(crate) struct QueueState {
    context_uris: Vec<SpotifyUri>,
    context_label: Option<String>,
    /// Index into [context_uris] for the track that was playing when manual queue
    /// items were inserted (advanced when context tracks finish).
    context_index: usize,
    manual_queue: Vec<SpotifyUri>,
    play_order: Vec<QueueEntry>,
    play_index: usize,
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

/// UI-facing queue: now playing, manual queue, and remaining context tracks.
#[derive(Clone, Debug, uniffi::Record)]
pub struct QueueSnapshot {
    pub now_playing_uri: Option<String>,
    pub next_in_queue: Vec<String>,
    pub context_label: Option<String>,
    pub next_from_context: Vec<String>,
}

impl Default for QueueState {
    fn default() -> Self {
        Self {
            context_uris: Vec::new(),
            context_label: None,
            context_index: 0,
            manual_queue: Vec::new(),
            play_order: Vec::new(),
            play_index: 0,
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

    pub fn set_queue(
        &mut self,
        uris: Vec<SpotifyUri>,
        start_index: usize,
        context_label: Option<String>,
    ) {
        self.context_uris = uris;
        self.context_label = context_label;
        self.context_index = start_index.min(self.context_uris.len().saturating_sub(1));
        self.manual_queue.clear();
        self.shuffle = false;
        self.repeat_context = false;
        self.repeat_track = false;
        self.rebuild_play_order();
        self.play_index = 0;
        self.position_ms = 0;
    }

    pub fn restore_linear(&mut self, uris: Vec<SpotifyUri>, uri_index: usize, position_ms: u32) {
        self.set_queue(uris, uri_index, None);
        self.position_ms = position_ms;
    }

    pub fn snapshot_resume(&self) -> Option<(Vec<SpotifyUri>, usize, u32)> {
        if self.context_uris.is_empty() {
            None
        } else {
            Some((self.context_uris.clone(), self.context_index, self.position_ms))
        }
    }

    pub fn current_uri(&self) -> Option<SpotifyUri> {
        self.play_order
            .get(self.play_index)
            .and_then(|entry| self.entry_uri(entry))
    }

    pub fn set_shuffle(&mut self, enabled: bool) {
        if self.shuffle == enabled {
            return;
        }
        self.shuffle = enabled;
        self.rebuild_play_order_preserve_position();
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
        self.advance_to_next()
    }

    pub fn skip_prev(&mut self, user_initiated: bool) -> Option<SpotifyUri> {
        if user_initiated && self.repeat_track {
            self.repeat_track = false;
        }
        if self.play_index > 0 {
            self.play_index -= 1;
            self.position_ms = 0;
            if let Some(QueueEntry::Context(idx)) = self.play_order.get(self.play_index) {
                self.context_index = *idx;
            }
            return self.current_uri();
        }
        None
    }

    pub fn end_of_track(&mut self) -> Option<SpotifyUri> {
        if self.repeat_track {
            self.position_ms = 0;
            return self.current_uri();
        }
        self.advance_to_next()
    }

    pub fn next_preload_uri(&self) -> Option<SpotifyUri> {
        if self.repeat_track {
            return self.current_uri();
        }
        self.play_order
            .get(self.play_index + 1)
            .and_then(|entry| self.entry_uri(entry))
            .or_else(|| {
                if self.repeat_context {
                    self.play_order
                        .first()
                        .and_then(|entry| self.entry_uri(entry))
                } else {
                    None
                }
            })
    }

    pub fn queue_snapshot(&self) -> QueueSnapshot {
        let now_playing_uri = self
            .current_uri()
            .and_then(|u| u.to_uri().ok());

        let mut next_in_queue = Vec::new();
        let mut next_from_context = Vec::new();

        for entry in self.play_order.iter().skip(self.play_index + 1) {
            match entry {
                QueueEntry::Manual(uri) => {
                    next_in_queue.push(uri.to_uri().unwrap_or_default());
                }
                QueueEntry::Context(idx) => {
                    if let Some(uri) = self.context_uris.get(*idx) {
                        next_from_context.push(uri.to_uri().unwrap_or_default());
                    }
                }
            }
        }

        QueueSnapshot {
            now_playing_uri,
            next_in_queue,
            context_label: self.context_label.clone(),
            next_from_context,
        }
    }

    pub fn add_to_queue(&mut self, uri: SpotifyUri) -> bool {
        let uri_string = uri.to_uri().unwrap_or_default();
        if self
            .manual_queue
            .iter()
            .any(|u| u.to_uri().ok().as_deref() == Some(uri_string.as_str()))
        {
            return false;
        }
        self.manual_queue.push(uri.clone());
        let mut insert_at = self.play_index + 1;
        while insert_at < self.play_order.len()
            && matches!(self.play_order[insert_at], QueueEntry::Manual(_))
        {
            insert_at += 1;
        }
        self.play_order.insert(insert_at, QueueEntry::Manual(uri));
        true
    }

    pub fn clear_manual_queue(&mut self) {
        self.manual_queue.clear();
        self.play_order
            .retain(|entry| !matches!(entry, QueueEntry::Manual(_)));
        if self.play_index >= self.play_order.len() {
            self.play_index = self.play_order.len().saturating_sub(1);
        }
    }

    /// Reorder the manual queue. `index` is into [QueueSnapshot::next_in_queue].
    pub fn move_manual_up(&mut self, index: usize) -> Result<(), ()> {
        if index == 0 || index >= self.manual_queue.len() {
            return Err(());
        }
        self.manual_queue.swap(index, index - 1);
        self.sync_manual_play_order();
        Ok(())
    }

    pub fn move_manual_down(&mut self, index: usize) -> Result<(), ()> {
        if index + 1 >= self.manual_queue.len() {
            return Err(());
        }
        self.manual_queue.swap(index, index + 1);
        self.sync_manual_play_order();
        Ok(())
    }

    /// Reorder upcoming context tracks. `index` is into [QueueSnapshot::next_from_context].
    pub fn move_context_up(&mut self, index: usize) -> Result<(), ()> {
        let slots = self.upcoming_context_play_indices();
        if index == 0 || index >= slots.len() {
            return Err(());
        }
        self.play_order.swap(slots[index], slots[index - 1]);
        Ok(())
    }

    pub fn move_context_down(&mut self, index: usize) -> Result<(), ()> {
        let slots = self.upcoming_context_play_indices();
        if index + 1 >= slots.len() {
            return Err(());
        }
        self.play_order.swap(slots[index], slots[index + 1]);
        Ok(())
    }

    fn upcoming_context_play_indices(&self) -> Vec<usize> {
        self.play_order
            .iter()
            .enumerate()
            .skip(self.play_index + 1)
            .filter_map(|(pos, entry)| {
                if matches!(entry, QueueEntry::Context(_)) {
                    Some(pos)
                } else {
                    None
                }
            })
            .collect()
    }

    fn advance_to_next(&mut self) -> Option<SpotifyUri> {
        if self.play_index + 1 < self.play_order.len() {
            self.play_index += 1;
            self.position_ms = 0;
            if let Some(QueueEntry::Context(idx)) = self.play_order.get(self.play_index) {
                self.context_index = *idx;
            }
            return self.current_uri();
        }
        if self.repeat_context {
            self.play_index = 0;
            self.position_ms = 0;
            if let Some(QueueEntry::Context(idx)) = self.play_order.first() {
                self.context_index = *idx;
            }
            return self.current_uri();
        }
        None
    }

    fn entry_uri(&self, entry: &QueueEntry) -> Option<SpotifyUri> {
        match entry {
            QueueEntry::Manual(uri) => Some(uri.clone()),
            QueueEntry::Context(idx) => self.context_uris.get(*idx).cloned(),
        }
    }

    fn rebuild_play_order(&mut self) {
        self.play_order.clear();
        if self.context_uris.is_empty() {
            return;
        }
        self.play_order
            .push(QueueEntry::Context(self.context_index));
        for uri in &self.manual_queue {
            self.play_order.push(QueueEntry::Manual(uri.clone()));
        }
        self.append_remaining_context();
    }

    fn rebuild_play_order_preserve_position(&mut self) {
        let current = self.current_uri();
        self.rebuild_play_order();
        if let Some(uri) = current {
            if let Some(idx) = self
                .play_order
                .iter()
                .position(|entry| self.entry_uri(entry).as_ref() == Some(&uri))
            {
                self.play_index = idx;
            }
        }
    }

    fn append_remaining_context(&mut self) {
        let mut remaining: Vec<usize> =
            (self.context_index + 1..self.context_uris.len()).collect();
        if self.shuffle && remaining.len() > 1 {
            remaining.shuffle(&mut rand::thread_rng());
        }
        for idx in remaining {
            self.play_order.push(QueueEntry::Context(idx));
        }
    }

    fn sync_manual_play_order(&mut self) {
        let current = self.current_uri();
        self.play_order
            .retain(|entry| !matches!(entry, QueueEntry::Manual(_)));
        let mut insert_at = self.play_index + 1;
        for uri in &self.manual_queue {
            self.play_order.insert(insert_at, QueueEntry::Manual(uri.clone()));
            insert_at += 1;
        }
        if let Some(uri) = current {
            if let Some(idx) = self
                .play_order
                .iter()
                .position(|entry| self.entry_uri(entry).as_ref() == Some(&uri))
            {
                self.play_index = idx;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track_uri(id: &str) -> SpotifyUri {
        SpotifyUri::from_uri(&format!("spotify:track:{id}")).unwrap()
    }

    fn uri_string(uri: &SpotifyUri) -> String {
        uri.to_uri().unwrap()
    }

    #[test]
    fn set_queue_builds_context_only_snapshot() {
        let uris = vec![
            track_uri("4iV5W9uYEdYUVa79Axb7Rh"),
            track_uri("2ZEPR21rR29EXOpF35cHY1"),
            track_uri("6rqhFgbbKwnb9MLmUQDhG6"),
        ];
        let mut q = QueueState::default();
        q.set_queue(uris.clone(), 1, Some("Test Album".into()));
        let snap = q.queue_snapshot();
        assert_eq!(
            snap.now_playing_uri.as_deref(),
            Some(uri_string(&uris[1]).as_str())
        );
        assert!(snap.next_in_queue.is_empty());
        assert_eq!(snap.context_label.as_deref(), Some("Test Album"));
        assert_eq!(snap.next_from_context.len(), 1);
        assert_eq!(snap.next_from_context[0], uri_string(&uris[2]));
    }

    #[test]
    fn add_to_queue_inserts_before_context_tail() {
        let uris = vec![
            track_uri("4iV5W9uYEdYUVa79Axb7Rh"),
            track_uri("2ZEPR21rR29EXOpF35cHY1"),
            track_uri("6rqhFgbbKwnb9MLmUQDhG6"),
        ];
        let mut q = QueueState::default();
        q.set_queue(uris.clone(), 0, Some("Album".into()));
        let extra = track_uri("3n3Ppam7vgaVa1iaRUc9Lp");
        assert!(q.add_to_queue(extra.clone()));
        let snap = q.queue_snapshot();
        assert_eq!(snap.next_in_queue, vec![uri_string(&extra)]);
        assert_eq!(snap.next_from_context.len(), 2);
    }

    #[test]
    fn clear_manual_queue() {
        let uris = vec![
            track_uri("4iV5W9uYEdYUVa79Axb7Rh"),
            track_uri("2ZEPR21rR29EXOpF35cHY1"),
        ];
        let mut q = QueueState::default();
        q.set_queue(uris, 0, Some("Album".into()));
        q.add_to_queue(track_uri("6rqhFgbbKwnb9MLmUQDhG6"));
        q.clear_manual_queue();
        assert!(q.queue_snapshot().next_in_queue.is_empty());
    }

    #[test]
    fn next_preload_follows_manual_insert() {
        let uris = vec![
            track_uri("4iV5W9uYEdYUVa79Axb7Rh"),
            track_uri("2ZEPR21rR29EXOpF35cHY1"),
            track_uri("6rqhFgbbKwnb9MLmUQDhG6"),
        ];
        let mut q = QueueState::default();
        q.set_queue(uris.clone(), 0, Some("Album".into()));
        assert_eq!(
            q.next_preload_uri().map(|u| uri_string(&u)),
            Some(uri_string(&uris[1]))
        );
        let queued = track_uri("3n3Ppam7vgaVa1iaRUc9Lp");
        q.add_to_queue(queued.clone());
        assert_eq!(
            q.next_preload_uri().map(|u| uri_string(&u)),
            Some(uri_string(&queued))
        );
    }

    #[test]
    fn move_manual_reorders() {
        let uris = vec![track_uri("4iV5W9uYEdYUVa79Axb7Rh")];
        let mut q = QueueState::default();
        q.set_queue(uris, 0, None);
        let a = track_uri("2ZEPR21rR29EXOpF35cHY1");
        let b = track_uri("6rqhFgbbKwnb9MLmUQDhG6");
        q.add_to_queue(a.clone());
        q.add_to_queue(b.clone());
        assert!(q.move_manual_down(0).is_ok());
        let snap = q.queue_snapshot();
        assert_eq!(snap.next_in_queue[0], uri_string(&b));
        assert_eq!(snap.next_in_queue[1], uri_string(&a));
    }

    #[test]
    fn move_context_reorders_play_order() {
        let uris = vec![
            track_uri("4iV5W9uYEdYUVa79Axb7Rh"),
            track_uri("2ZEPR21rR29EXOpF35cHY1"),
            track_uri("6rqhFgbbKwnb9MLmUQDhG6"),
        ];
        let mut q = QueueState::default();
        q.set_queue(uris.clone(), 0, Some("Album".into()));
        assert!(q.move_context_down(0).is_ok());
        let snap = q.queue_snapshot();
        assert_eq!(snap.next_from_context[0], uri_string(&uris[2]));
        assert_eq!(snap.next_from_context[1], uri_string(&uris[1]));
    }
}

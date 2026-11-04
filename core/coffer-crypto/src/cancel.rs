// SPDX-License-Identifier: GPL-3.0-or-later
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

/// Cooperative cancellation flag shared between the caller and long-running
/// operations (key derivation, header search). A child token is cancelled when
/// either itself or any ancestor is cancelled.
#[derive(Clone, Debug, Default)]
pub struct CancelToken {
    own: Arc<AtomicBool>,
    parent: Option<Arc<CancelToken>>,
}

impl CancelToken {
    pub fn new() -> Self {
        Self::default()
    }

    /// Token that is cancelled by `self` but can also be cancelled on its own.
    pub fn child(&self) -> Self {
        Self {
            own: Arc::new(AtomicBool::new(false)),
            parent: Some(Arc::new(self.clone())),
        }
    }

    pub fn cancel(&self) {
        self.own.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.own.load(Ordering::Relaxed) || self.parent.as_ref().is_some_and(|p| p.is_cancelled())
    }
}

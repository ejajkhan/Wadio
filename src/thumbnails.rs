use crossbeam_channel::{bounded, Receiver, Sender, TrySendError};
use egui::{ColorImage, Context, TextureHandle, TextureOptions};
use image::{AnimationDecoder, ImageReader};
use lru::LruCache;
use std::collections::{HashMap, HashSet, VecDeque};
use std::num::NonZeroUsize;
use std::path::PathBuf;
use std::thread;
use std::time::Duration;
use zeroize::Zeroize;

const THUMB_EDGE: u32 = 96;
const MAX_GIF_FRAMES: usize = 20;
const MAX_SOURCE_PIXELS: u64 = 50_000_000;

pub struct ThumbnailFrame {
    pub image: ColorImage,
    pub delay: Duration,
}

pub struct ThumbnailResponse {
    pub id: String,
    pub frames: Option<Vec<ThumbnailFrame>>,
    pub epoch: u64,
}

struct CachedThumbnail {
    frames: Vec<TextureHandle>,
    delays: Vec<Duration>,
    frame_index: usize,
    next_frame_at: f64,
}

impl CachedThumbnail {
    fn current(&self) -> Option<&TextureHandle> { self.frames.get(self.frame_index) }
    fn advance(&mut self, now: f64) -> bool {
        if self.frames.len() <= 1 || now < self.next_frame_at { return false; }
        let mut advanced = false;
        let mut steps = 0;
        while now >= self.next_frame_at && steps < self.frames.len() {
            self.frame_index = (self.frame_index + 1) % self.frames.len();
            self.next_frame_at += self.delays.get(self.frame_index).copied().unwrap_or(Duration::from_millis(100)).as_secs_f64();
            steps += 1;
            advanced = true;
        }
        advanced
    }
    fn next_delay(&self, now: f64) -> Duration {
        Duration::from_secs_f64((self.next_frame_at - now).max(0.01).min(1.0))
    }
}

fn to_color_image(mut pixels: image::RgbaImage) -> ColorImage {
    let dimensions = [pixels.width() as usize, pixels.height() as usize];
    // egui copies into its own Color32 storage; borrowing the raw pixels avoids an
    // additional full-size Vec<u8> clone during thumbnail conversion.
    let color = ColorImage::from_rgba_unmultiplied(dimensions, pixels.as_raw());
    pixels.as_mut().zeroize();
    color
}

fn decode_thumbnail(path: &PathBuf) -> Option<Vec<ThumbnailFrame>> {
    let (w, h) = image::image_dimensions(path).ok()?;
    if w == 0 || h == 0 || w > 30000 || h > 30000 || (w as u64).saturating_mul(h as u64) > MAX_SOURCE_PIXELS {
        return None;
    }
    if path.extension().and_then(|s| s.to_str()).map(|s| s.eq_ignore_ascii_case("gif")).unwrap_or(false) {
        // Count first, then sample frames evenly across the entire animation. The GIF
        // remains fully animated without retaining every decoded frame in memory.
        let count_file = std::fs::File::open(path).ok()?;
        let count_decoder = image::codecs::gif::GifDecoder::new(std::io::BufReader::new(count_file)).ok()?;
        let counted_frames = count_decoder.into_frames().take(10001).count();
        let total_frames = counted_frames.min(10000);
        if total_frames == 0 { return None; }
        let sample_count = total_frames.min(MAX_GIF_FRAMES);
        let selected: HashSet<usize> = if sample_count == 1 {
            [0usize].into_iter().collect()
        } else {
            (0..sample_count).map(|i| i * (total_frames - 1) / (sample_count - 1)).collect()
        };
        let file = std::fs::File::open(path).ok()?;
        let decoder = image::codecs::gif::GifDecoder::new(std::io::BufReader::new(file)).ok()?;
        let mut result = Vec::with_capacity(sample_count);
        for (index, frame_result) in decoder.into_frames().take(10000).enumerate() {
            let frame = frame_result.ok()?;
            let (num, den) = frame.delay().numer_denom_ms();
            let mut pixels = frame.into_buffer();
            if !selected.contains(&index) {
                pixels.as_mut().zeroize();
                continue;
            }
            let delay_ms = if den == 0 { 100 } else { (num / den).clamp(40, 1000) };
            let thumb = image::imageops::thumbnail(&pixels, THUMB_EDGE, THUMB_EDGE);
            pixels.as_mut().zeroize();
            result.push(ThumbnailFrame { image: to_color_image(thumb), delay: Duration::from_millis(delay_ms as u64) });
        }
        if result.is_empty() { None } else { Some(result) }
    } else {
        let decoded = ImageReader::open(path).ok()?.decode().ok()?;
        let thumb = decoded.thumbnail(THUMB_EDGE, THUMB_EDGE).to_rgba8();
        Some(vec![ThumbnailFrame { image: to_color_image(thumb), delay: Duration::from_millis(1000) }])
    }
}

pub struct ThumbnailPool {
    request_tx: Sender<(String, PathBuf, u64)>,
    result_rx: Receiver<ThumbnailResponse>,
    cache: LruCache<String, CachedThumbnail>,
    in_flight: HashMap<String, u64>,
    failed: HashSet<String>,
    failed_order: VecDeque<String>,
    failed_capacity: usize,
    epoch: u64,
}

impl ThumbnailPool {
    pub fn new(capacity: usize) -> Self {
        let (req_tx, req_rx) = bounded::<(String, PathBuf, u64)>(64);
        let (res_tx, res_rx) = bounded::<ThumbnailResponse>(32);
        // A single decode worker prevents multiple 30–50 MP source frames from
        // being expanded into large RGBA buffers concurrently.
        for _ in 0..1 {
            let rx = req_rx.clone();
            let tx = res_tx.clone();
            thread::spawn(move || {
                while let Ok((id, path, epoch)) = rx.recv() {
                    let frames = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| decode_thumbnail(&path))).ok().flatten();
                    // Bounded delivery prevents workers from accumulating decoded animations.
                    // If the UI is locked or overwhelmed, discard rather than block indefinitely.
                    let response = ThumbnailResponse { id, frames, epoch };
                    match tx.send(response) {
                        Ok(()) => {},
                        Err(mut error) => {
                            if let Some(frames) = error.0.frames.as_mut() {
                                for frame in frames { for pixel in &mut frame.image.pixels { *pixel = egui::Color32::TRANSPARENT; } }
                            }
                            break;
                        }
                    }
                }
            });
        }
        Self {
            request_tx: req_tx,
            result_rx: res_rx,
            cache: LruCache::new(NonZeroUsize::new(capacity.min(48).max(1)).unwrap()),
            in_flight: HashMap::new(),
            failed: HashSet::new(),
            failed_order: VecDeque::new(),
            failed_capacity: capacity.max(1),
            epoch: 0,
        }
    }

    pub fn poll_results(&mut self, ctx: &Context) {
        let mut changed = false;
        while let Ok(mut response) = self.result_rx.try_recv() {
            if self.in_flight.get(&response.id) == Some(&response.epoch) { self.in_flight.remove(&response.id); }
            if response.epoch != self.epoch {
                if let Some(frames) = response.frames.as_mut() {
                    for frame in frames { for pixel in &mut frame.image.pixels { *pixel = egui::Color32::TRANSPARENT; } }
                }
                changed = true;
                continue;
            }
            if let Some(frames) = response.frames.take() {
                let mut textures = Vec::with_capacity(frames.len());
                let mut delays = Vec::with_capacity(frames.len());
                for mut frame in frames {
                    let handle = ctx.load_texture(&response.id, frame.image.clone(), TextureOptions::LINEAR);
                    for pixel in &mut frame.image.pixels { *pixel = egui::Color32::TRANSPARENT; }
                    textures.push(handle);
                    delays.push(frame.delay.max(Duration::from_millis(40)));
                }
                if !textures.is_empty() {
                    let first_delay = delays.first().copied().unwrap_or(Duration::from_millis(100));
                    self.cache.put(response.id, CachedThumbnail {
                        frames: textures, delays, frame_index: 0,
                        next_frame_at: ctx.input(|i| i.time) + first_delay.as_secs_f64(),
                    });
                }
            } else if self.failed.insert(response.id.clone()) {
                self.failed_order.push_back(response.id.clone());
                while self.failed_order.len() > self.failed_capacity {
                    if let Some(oldest) = self.failed_order.pop_front() { self.failed.remove(&oldest); }
                }
            }
            changed = true;
        }
        if changed { ctx.request_repaint(); }
    }

    pub fn clear(&mut self) {
        self.cache.clear();
        self.failed.clear();
        self.failed_order.clear();
        self.in_flight.clear();
        self.epoch = self.epoch.wrapping_add(1);
        while let Ok(mut response) = self.result_rx.try_recv() {
            if let Some(frames) = response.frames.as_mut() {
                for frame in frames { for pixel in &mut frame.image.pixels { *pixel = egui::Color32::TRANSPARENT; } }
            }
        }
    }

    pub fn get_or_request(&mut self, id: &str, path: PathBuf, ctx: &Context) -> Option<&TextureHandle> {
        let now = ctx.input(|i| i.time);
        if let Some(entry) = self.cache.get_mut(id) {
            if entry.advance(now) { ctx.request_repaint(); }
            if entry.frames.len() > 1 { ctx.request_repaint_after(entry.next_delay(now)); }
            return entry.current();
        }
        if self.failed.contains(id) { return None; }
        if !self.in_flight.contains_key(id) {
            let epoch = self.epoch;
            self.in_flight.insert(id.to_string(), epoch);
            match self.request_tx.try_send((id.to_string(), path, epoch)) {
                Ok(()) => ctx.request_repaint_after(Duration::from_millis(50)),
                Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
                    if self.in_flight.get(id) == Some(&epoch) { self.in_flight.remove(id); }
                }
            }
        }
        None
    }
}

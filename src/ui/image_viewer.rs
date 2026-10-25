use egui::{Color32, ColorImage, Context, TextureHandle, TextureOptions, Ui, Vec2};
use image::{imageops, GenericImageView, ImageDecoder, RgbaImage};
use std::path::Path;
use std::time::Duration;
use image::AnimationDecoder;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{atomic::{AtomicBool, Ordering}, Arc};
use crossbeam_channel::{bounded, Receiver, Sender, TrySendError};
use zeroize::Zeroize;

struct ViewerLoadResult {
    result: Result<(ColorImage, (u32, u32)), String>,
    rotation: u8,
    delay: Duration,
}

pub struct ImageViewerState {
    pub record_id: String,
    source_path: std::path::PathBuf,
    dimensions: Option<(u32, u32)>,
    texture: Option<TextureHandle>,
    pub zoom: f32,
    pub pan: Vec2,
    pub rotation: u8,
    pub fit_to_screen: bool,
    loading: bool,
    loading_started_at: f64,
    error: Option<String>,
    result_tx: Sender<ViewerLoadResult>,
    result_rx: Receiver<ViewerLoadResult>,
    cancelled: Arc<AtomicBool>,
}

fn zeroize_image(image: &mut RgbaImage) {
    // Zeroize the contiguous byte buffer in one call. This still performs volatile
    // writes, but avoids per-pixel iteration while the user is locking the vault.
    image.as_mut().zeroize();
}

fn zeroize_color_image(image: &mut ColorImage) {
    for pixel in &mut image.pixels {
        *pixel = Color32::TRANSPARENT;
    }
}

fn zeroize_result(result: &mut Result<(ColorImage, (u32, u32)), String>) {
    if let Ok((color, _)) = result {
        zeroize_color_image(color);
    }
}

fn make_color_image(pixels: &RgbaImage) -> ColorImage {
    ColorImage::from_rgba_unmultiplied(
        [pixels.width() as usize, pixels.height() as usize],
        pixels.as_raw(),
    )
}

fn decode_image(path: &Path) -> Result<RgbaImage, String> {
    let (w, h) = image::image_dimensions(path).map_err(|e| e.to_string())?;
    if w == 0 || h == 0 || w > 30000 || h > 30000
        || (w as u64).saturating_mul(h as u64) > 50_000_000 {
        return Err("Image is too large to open safely on this device".into());
    }
    let image = image::ImageReader::open(path)
        .map_err(|e| e.to_string())?
        .decode()
        .map_err(|e| e.to_string())?;
    let (decoded_w, decoded_h) = image.dimensions();
    if decoded_w == 0 || decoded_h == 0 || decoded_w > 30000 || decoded_h > 30000
        || (decoded_w as u64).saturating_mul(decoded_h as u64) > 50_000_000 {
        return Err("Decoded image dimensions exceed the safety limit".into());
    }
    // Keep decoded CPU memory bounded before creating egui's second pixel buffer.
    // The decoded-pixel cap also rejects decompression bombs before full decode.
    let resized = if decoded_w.max(decoded_h) > 2048 {
        image.resize(2048, 2048, imageops::FilterType::Triangle)
    } else {
        image
    };
    Ok(resized.to_rgba8())
}

fn decode_color_image(path: &Path, rotation: u8) -> Result<(ColorImage, (u32, u32)), String> {
    let mut pixels = decode_image(path)?;
    if rotation != 0 {
        let rotated = match rotation % 4 {
            1 => imageops::rotate90(&pixels),
            2 => imageops::rotate180(&pixels),
            3 => imageops::rotate270(&pixels),
            _ => unreachable!(),
        };
        zeroize_image(&mut pixels);
        pixels = rotated;
    }
    let dimensions = (pixels.width(), pixels.height());
    let color = make_color_image(&pixels);
    // Do not retain a second full-size RGBA buffer in viewer state. The egui texture
    // is the display copy; rotation re-decodes the source instead of caching raw pixels.
    zeroize_image(&mut pixels);
    Ok((color, dimensions))
}

fn send_worker_result(
    tx: &Sender<ViewerLoadResult>,
    cancelled: &AtomicBool,
    mut result: ViewerLoadResult,
) {
    if cancelled.load(Ordering::Acquire) {
        zeroize_result(&mut result.result);
        return;
    }
    match tx.try_send(result) {
        Ok(()) => {}
        Err(TrySendError::Full(mut unsent)) => {
            eprintln!("wadio: viewer result dropped because the result channel is full");
            zeroize_result(&mut unsent.result);
        }
        Err(TrySendError::Disconnected(mut unsent)) => {
            zeroize_result(&mut unsent.result);
        }
    }
}

fn send_animation_frame(tx: &Sender<ViewerLoadResult>, cancelled: &AtomicBool, mut response: ViewerLoadResult) -> bool {
    while !cancelled.load(Ordering::Acquire) {
        match tx.send_timeout(response, Duration::from_millis(50)) {
            Ok(()) => return true,
            Err(crossbeam_channel::SendTimeoutError::Timeout(unsent)) => response = unsent,
            Err(crossbeam_channel::SendTimeoutError::Disconnected(mut unsent)) => {
                zeroize_result(&mut unsent.result);
                return false;
            }
        }
    }
    zeroize_result(&mut response.result);
    false
}

fn run_gif_animation(path: std::path::PathBuf, tx: Sender<ViewerLoadResult>, cancelled: Arc<AtomicBool>) {
    use std::io::BufReader;
    while !cancelled.load(Ordering::Acquire) {
        let file = match std::fs::File::open(&path) {
            Ok(file) => file,
            Err(err) => {
                let _ = send_animation_frame(&tx, &cancelled, ViewerLoadResult { result: Err(err.to_string()), rotation: 0, delay: Duration::ZERO });
                return;
            }
        };
        let decoder = match image::codecs::gif::GifDecoder::new(BufReader::new(file)) {
            Ok(decoder) => decoder,
            Err(err) => {
                let _ = send_animation_frame(&tx, &cancelled, ViewerLoadResult { result: Err(err.to_string()), rotation: 0, delay: Duration::ZERO });
                return;
            }
        };
        let (width, height) = decoder.dimensions();
        if width == 0 || height == 0 || width > 30000 || height > 30000 || (width as u64).saturating_mul(height as u64) > 50_000_000 {
            let _ = send_animation_frame(&tx, &cancelled, ViewerLoadResult { result: Err("GIF dimensions exceed Wadio's 50-megapixel safety limit".into()), rotation: 0, delay: Duration::ZERO });
            return;
        }
        let mut emitted = false;
        for frame_result in decoder.into_frames() {
            if cancelled.load(Ordering::Acquire) { return; }
            let frame = match frame_result {
                Ok(frame) => frame,
                Err(err) => {
                    let _ = send_animation_frame(&tx, &cancelled, ViewerLoadResult { result: Err(err.to_string()), rotation: 0, delay: Duration::ZERO });
                    return;
                }
            };
            let (num, den) = frame.delay().numer_denom_ms();
            let delay_ms = if den == 0 { 100 } else { (num / den).clamp(40, 2000) };
            let mut pixels = frame.into_buffer();
            let pixels = if pixels.width().max(pixels.height()) > 2048 {
                let resized = imageops::resize(&pixels, ((pixels.width() as f64 * (2048.0 / pixels.width().max(pixels.height()) as f64)).round() as u32).max(1), ((pixels.height() as f64 * (2048.0 / pixels.width().max(pixels.height()) as f64)).round() as u32).max(1), imageops::FilterType::Triangle);
                pixels.as_mut().zeroize();
                resized
            } else { pixels };
            let dims = (pixels.width(), pixels.height());
            let color = make_color_image(&pixels);
            let mut pixels = pixels;
            zeroize_image(&mut pixels);
            let response = ViewerLoadResult { result: Ok((color, dims)), rotation: 0, delay: Duration::from_millis(delay_ms as u64) };
            if !send_animation_frame(&tx, &cancelled, response) { return; }
            emitted = true;
            // Sleep in short slices so close/lock can stop the animation promptly.
            let mut remaining = delay_ms as u64;
            while remaining > 0 && !cancelled.load(Ordering::Acquire) {
                let slice = remaining.min(25);
                std::thread::sleep(Duration::from_millis(slice));
                remaining -= slice;
            }
        }
        if !emitted {
            let _ = send_animation_frame(&tx, &cancelled, ViewerLoadResult { result: Err("GIF contains no decodable frames".into()), rotation: 0, delay: Duration::ZERO });
            return;
        }
    }
}

impl ImageViewerState {
    /// Starts loading asynchronously. Decode errors and decoder panics are reported in the viewer.
    pub fn load(ctx: &Context, record_id: String, path: &Path) -> Self {
        let (result_tx, result_rx) = bounded(1);
        let cancelled = Arc::new(AtomicBool::new(false));
        let worker_cancelled = cancelled.clone();
        let worker_tx = result_tx.clone();
        let path = path.to_path_buf();
        let worker_path = path.clone();
        let panic_tx = worker_tx.clone();
        let panic_cancelled = worker_cancelled.clone();
        std::thread::spawn(move || {
            let result = catch_unwind(AssertUnwindSafe(move || {
                if worker_path.extension().and_then(|e| e.to_str()).map(|e| e.eq_ignore_ascii_case("gif")).unwrap_or(false) {
                    run_gif_animation(worker_path, worker_tx, worker_cancelled);
                    return;
                }
                let result = decode_color_image(&worker_path, 0);
                send_worker_result(&worker_tx, &worker_cancelled, ViewerLoadResult { result, rotation: 0, delay: Duration::ZERO });
            }));
            if result.is_err() {
                send_worker_result(&panic_tx, &panic_cancelled, ViewerLoadResult {
                    result: Err("Image decoder failed unexpectedly; the app was protected from a decoder panic".into()),
                    rotation: 0, delay: Duration::ZERO,
                });
            }
        });
        ctx.request_repaint_after(std::time::Duration::from_millis(50));
        Self {
            record_id,
            source_path: path,
            dimensions: None,
            texture: None,
            zoom: 1.0,
            pan: Vec2::ZERO,
            rotation: 0,
            fit_to_screen: true,
            loading: true,
            loading_started_at: ctx.input(|i| i.time),
            error: None,
            result_tx,
            result_rx,
            cancelled,
        }
    }

    pub fn poll(&mut self, ctx: &Context) {
        while let Ok(mut response) = self.result_rx.try_recv() {
            if self.cancelled.load(Ordering::Acquire) {
                zeroize_result(&mut response.result);
                continue;
            }
            self.loading = false;
            match response.result {
                Ok((color, dimensions)) => {
                    // GPU upload remains on egui's UI thread. Only the renderer's texture is
                    // retained; CPU pixel buffers are zeroized by the worker after conversion.
                    self.texture = Some(ctx.load_texture(
                        format!("viewer-{}-{}", self.record_id, response.rotation),
                        color,
                        TextureOptions::LINEAR,
                    ));
                    self.dimensions = Some(dimensions);
                    self.rotation = response.rotation;
                    self.error = None;
                    if !response.delay.is_zero() { ctx.request_repaint_after(response.delay); }
                }
                Err(error) => {
                    self.error = Some(error);
                    self.texture = None;
                    self.dimensions = None;
                }
            }
            ctx.request_repaint();
        }
        if self.loading {
            let now = ctx.input(|i| i.time);
            if now - self.loading_started_at >= 30.0 {
                self.loading = false;
                self.texture = None;
                self.dimensions = None;
                self.error = Some("Image loading timed out. Close and reopen the image to retry.".into());
            } else {
                ctx.request_repaint_after(std::time::Duration::from_millis(50));
            }
        }
    }

    pub fn zeroize_sensitive(&mut self) {
        self.cancelled.store(true, Ordering::Release);
        self.dimensions = None;
        self.source_path = std::path::PathBuf::new();
        self.record_id.zeroize();
        if let Some(mut error) = self.error.take() { error.zeroize(); }
        self.texture = None;
        while let Ok(mut response) = self.result_rx.try_recv() {
            zeroize_result(&mut response.result);
        }
    }

    pub fn rotate(&mut self, ctx: &Context, clockwise: bool) {
        if self.loading || self.dimensions.is_none() { return; }
        self.texture = None;
        self.loading = true;
        self.loading_started_at = ctx.input(|i| i.time);
        self.error = None;
        let next_rotation = (self.rotation + if clockwise { 1 } else { 3 }) % 4;
        let tx = self.result_tx.clone();
        let cancelled = self.cancelled.clone();
        let path = self.source_path.clone();
        std::thread::spawn(move || {
            let result = catch_unwind(AssertUnwindSafe(|| decode_color_image(&path, next_rotation)))
                .unwrap_or_else(|_| Err("Image rotation failed unexpectedly".to_string()));
            send_worker_result(&tx, &cancelled, ViewerLoadResult { result, rotation: next_rotation, delay: Duration::ZERO });
        });
        self.pan = Vec2::ZERO;
        ctx.request_repaint_after(std::time::Duration::from_millis(50));
    }

    pub fn render(&mut self, ui: &mut Ui, ctx: &Context, filename: &str, file_bytes: u64, can_prev: bool, can_next: bool) -> (bool, bool, bool) {
        let mut close = false;
        let mut prev = false;
        let mut next = false;
        let is_gif = self.source_path.extension().and_then(|e| e.to_str()).map(|e| e.eq_ignore_ascii_case("gif")).unwrap_or(false);
        ui.horizontal_wrapped(|ui| {
            if ui.button("← Back to gallery").clicked() { close = true; }
            if ui.add_enabled(can_prev, egui::Button::new("◀ Previous")).clicked() { prev = true; }
            if ui.add_enabled(can_next, egui::Button::new("Next ▶")).clicked() { next = true; }
            ui.separator();
            if ui.add_enabled(!is_gif && !self.loading && self.dimensions.is_some(), egui::Button::new("↶ Rotate left")).clicked() { self.rotate(ctx, false); }
            if ui.add_enabled(!is_gif && !self.loading && self.dimensions.is_some(), egui::Button::new("↷ Rotate right")).clicked() { self.rotate(ctx, true); }
            if ui.add_enabled(!self.loading && self.dimensions.is_some(), egui::Button::new("Fit")).clicked() { self.fit_to_screen = true; self.zoom = 1.0; self.pan = Vec2::ZERO; }
            if ui.add_enabled(!self.loading && self.dimensions.is_some(), egui::Button::new("100%")).clicked() { self.fit_to_screen = false; self.zoom = 1.0; self.pan = Vec2::ZERO; }
        });
        if self.loading {
            ui.horizontal(|ui| { ui.spinner(); ui.label("Loading image…"); });
        }
        if let Some(error) = &self.error {
            ui.colored_label(Color32::RED, error);
        }
        let dimensions = self.dimensions;
        if let Some((w, h)) = dimensions {
            ui.label(format!("{} • {} × {} • {:.2} MB", filename, w, h, file_bytes as f64 / 1_048_576.0));
            ui.horizontal(|ui| {
                ui.label("Zoom");
                if ui.add_enabled(!self.loading, egui::Slider::new(&mut self.zoom, 0.1..=5.0).logarithmic(true).suffix("×")).changed() { self.fit_to_screen = false; }
                if ui.add_enabled(!self.loading, egui::Button::new("−")).clicked() { self.zoom = (self.zoom / 1.2).max(0.1); self.fit_to_screen = false; }
                if ui.add_enabled(!self.loading, egui::Button::new("+")).clicked() { self.zoom = (self.zoom * 1.2).min(5.0); self.fit_to_screen = false; }
            });
        }
        let available = ui.available_size().max(Vec2::splat(1.0));
        let (rect, response) = ui.allocate_exact_size(available, egui::Sense::drag());
        if response.dragged() && !self.loading { self.pan += ctx.input(|i| i.pointer.delta()); self.fit_to_screen = false; }
        ui.painter().rect_filled(rect, 0.0, Color32::BLACK);
        if let (Some((w, h)), Some(texture)) = (self.dimensions, &self.texture) {
            let iw = w as f32;
            let ih = h as f32;
            let fit = (rect.width() / iw).min(rect.height() / ih).max(0.0001);
            let scale = fit * if self.fit_to_screen { self.zoom.min(1.0) } else { self.zoom };
            let size = Vec2::new(iw * scale, ih * scale);
            let max_pan = ((size - rect.size()) / 2.0).max(Vec2::ZERO);
            self.pan = self.pan.clamp(-max_pan, max_pan);
            let center = rect.center() + self.pan;
            let image_rect = egui::Rect::from_center_size(center, size);
            ui.painter().image(texture.id(), image_rect, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), Color32::WHITE);
        }
        (close, prev, next)
    }
}

impl Drop for ImageViewerState {
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
        self.dimensions = None;
        self.texture = None;
        self.source_path = std::path::PathBuf::new();
        while let Ok(mut response) = self.result_rx.try_recv() {
            zeroize_result(&mut response.result);
        }
        // Best-effort second drain closes the common cancellation/send race; a worker may
        // still be finishing concurrently, so this is hygiene rather than a hard guarantee.
        std::thread::yield_now();
        while let Ok(mut response) = self.result_rx.try_recv() {
            zeroize_result(&mut response.result);
        }
        self.record_id.zeroize();
        // If per-viewer CPU caches are added later, zeroize them here as well.
    }
}

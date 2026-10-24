use crate::auth::AuthEngine;
use crate::db::{AlbumRecord, MediaRecord, VaultDb};
use crate::importer::ImportCoordinator;
use crate::platform::audio::AndroidAudioController;
use crate::platform::video::play_video;
use crate::thumbnails::ThumbnailPool;
use crate::ui::audio_view::render_audio_controls;
use crate::ui::gallery_view::render_media_grid;
use crate::ui::image_viewer::ImageViewerState;
use crossbeam_channel::{bounded, Receiver, Sender, TryRecvError};

use eframe::egui::{self, Color32, Key, Layout, RichText, TextEdit, Ui};
use parking_lot::Mutex;
use std::sync::Arc;
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::time::Instant;
use std::sync::atomic::Ordering;

struct AuthWorkerResult { request_id: u64, result: Result<String, ()> }
use zeroize::Zeroize;

#[derive(PartialEq, Eq)]
pub enum ActiveTab {
    Audio,
    Pictures,
    Videos,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum MediaSortMode { Name, Size, DateCreated }

pub struct WadioApp {
    audio: AndroidAudioController,
    auth: AuthEngine,
    db: Arc<Mutex<VaultDb>>,
    importer: Arc<ImportCoordinator>,
    thumbs: ThumbnailPool,

    tab: ActiveTab,
    pass_input: String,
    confirm_pass_input: String,
    auth_error: Option<String>,
    album_error: Option<String>,
    import_notice: Option<String>,
    import_notice_since: Option<Instant>,
    last_import_epoch: u64,

    cached_media: Vec<MediaRecord>,
    cached_albums: Vec<AlbumRecord>,
    album_covers: HashMap<String, MediaRecord>,
    selected_media_ids: HashSet<String>,
    selection_mode: bool,
    long_press_start: Option<(String, f64)>,
    suppress_click_id: Option<String>,
    selection_destination: String,
    confirm_delete_selected: bool,
    selected_album: String,
    new_album_input: String,
    show_new_album_modal: bool,
    auth_pending: bool,
    auth_request_id: u64,
    pin_failed_attempts: u32,
    pin_lock_until_unix: i64,
    last_activity: f64,
    auth_tx: Sender<AuthWorkerResult>,
    auth_rx: Receiver<AuthWorkerResult>,
    viewer: Option<ImageViewerState>,
    sort_mode: MediaSortMode,
    sort_ascending: bool,
}

impl WadioApp {
    pub fn new(
        audio: AndroidAudioController,
        auth: AuthEngine,
        db: Arc<Mutex<VaultDb>>,
        importer: Arc<ImportCoordinator>,
    ) -> Self {
        let albums = db.lock().get_albums().unwrap_or_default();
        let pin_failed_attempts = db.lock().get_meta("pin_fail_count").ok().flatten().and_then(|v| v.parse::<u32>().ok()).unwrap_or(0);
        let pin_lock_until_unix = db.lock().get_meta("pin_lock_until").ok().flatten().and_then(|v| v.parse::<i64>().ok()).unwrap_or(0);
        let (auth_tx, auth_rx) = bounded(1);
        Self {
            audio,
            auth,
            db,
            importer,
            thumbs: ThumbnailPool::new(120),
            tab: ActiveTab::Audio,
            pass_input: String::new(),
            confirm_pass_input: String::new(),
            auth_error: None,
            album_error: None,
            import_notice: None,
            import_notice_since: None,
            last_import_epoch: crate::current_import_epoch(),
            cached_media: Vec::new(),
            cached_albums: albums,
            album_covers: HashMap::new(),
            selected_media_ids: HashSet::new(),
            selection_mode: false,
            long_press_start: None,
            suppress_click_id: None,
            selection_destination: String::new(),
            confirm_delete_selected: false,
            selected_album: "default".to_string(),
            new_album_input: String::new(),
            show_new_album_modal: false,
            auth_pending: false,
            auth_request_id: 0,
            pin_failed_attempts,
            pin_lock_until_unix,
            last_activity: 0.0,
            auth_tx,
            auth_rx,
            viewer: None,
            sort_mode: MediaSortMode::DateCreated,
            sort_ascending: false,
        }
    }

    fn reload_active_tab(&mut self) {
        if let Ok(records) = self.db.lock().get_media_by_album(&self.selected_album, 10000, 0) {
            self.cached_media = match self.tab {
                ActiveTab::Pictures => records.into_iter().filter(|r| r.media_type == "Image").collect(),
                ActiveTab::Videos => records.into_iter().filter(|r| r.media_type == "Video").collect(),
                _ => Vec::new(),
            };
        }
        self.sort_cached_media();
        self.reload_album_covers();
    }

    fn sort_cached_media(&mut self) {
        match self.sort_mode {
            MediaSortMode::Name => self.cached_media.sort_by(|a, b| a.file_name.to_lowercase().cmp(&b.file_name.to_lowercase())),
            MediaSortMode::Size => self.cached_media.sort_by_key(|item| item.byte_size),
            // The database records the time an item was imported; it does not retain the
            // source file's original creation timestamp.
            MediaSortMode::DateCreated => self.cached_media.sort_by_key(|item| item.imported_at),
        }
        if !self.sort_ascending {
            self.cached_media.reverse();
        }
    }

    fn reload_album_covers(&mut self) {
        let db = self.db.lock();
        let mut covers = HashMap::new();
        for album in &self.cached_albums {
            if let Ok(Some(record)) = db.get_album_cover(&album.id) {
                covers.insert(album.id.clone(), record);
            }
        }
        self.album_covers = covers;
    }

    fn trigger_biometric(&self) {
        #[cfg(target_os = "android")]
        {
            let ctx = ndk_context::android_context();
            let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
            let Ok(mut env) = vm.attach_current_thread() else { return };
            let activity = unsafe { jni::objects::JObject::from_raw(ctx.context().cast()) };
            let _ = env.call_method(&activity, "launchBiometricPrompt", "()V", &[]);
        }
    }

    fn lock_vault(&mut self) {
        if let Some(viewer) = self.viewer.as_mut() { viewer.zeroize_sensitive(); }
        self.viewer = None;
        for record in &mut self.cached_media {
            record.id.zeroize();
            record.album_id.zeroize();
            record.media_type.zeroize();
            record.file_name.zeroize();
            record.sha256.zeroize();
            record.relative_path.zeroize();
        }
        self.cached_media.clear();
        self.cached_media.shrink_to_fit();
        for mut id in self.selected_media_ids.drain() { id.zeroize(); }
        self.selection_mode = false;
        if let Some((id, _)) = self.long_press_start.as_mut() { id.zeroize(); }
        self.long_press_start = None;
        if let Some(id) = self.suppress_click_id.as_mut() { id.zeroize(); }
        self.suppress_click_id = None;
        self.confirm_delete_selected = false;
        for record in self.album_covers.values_mut() {
            record.id.zeroize(); record.album_id.zeroize(); record.media_type.zeroize();
            record.file_name.zeroize(); record.sha256.zeroize(); record.relative_path.zeroize();
        }
        self.album_covers.clear();
        self.selection_destination.zeroize();
        self.selection_destination.clear();
        for album in &mut self.cached_albums {
            album.id.zeroize();
            album.name.zeroize();
        }
        self.cached_albums.clear();
        self.cached_albums.shrink_to_fit();
        self.selected_album.zeroize();
        self.selected_album = "default".to_string();
        self.new_album_input.zeroize();
        self.thumbs.clear();
        self.auth_error = None;
        self.album_error = None;
        self.import_notice = None;
        self.import_notice_since = None;
        self.show_new_album_modal = false;
        self.pass_input.zeroize();
        self.confirm_pass_input.zeroize();
        // Invalidate any in-flight Argon2 result so backgrounding cannot unlock later.
        self.auth_request_id = self.auth_request_id.wrapping_add(1);
        self.auth_pending = false;
        self.auth.lock();
        self.last_activity = 0.0; // Reseed idle tracking on the next frame after unlocking.
        self.tab = ActiveTab::Audio;
    }

    fn trigger_import(&self) {
        let ctx = ndk_context::android_context();
        let Ok(vm) = (unsafe { jni::JavaVM::from_raw(ctx.vm().cast()) }) else { return };
        let Ok(mut env) = vm.attach_current_thread() else { return };
        let activity = unsafe { jni::objects::JObject::from_raw(ctx.context().cast()) };
        let Ok(album_id) = env.new_string(&self.selected_album) else { return };
        let _ = env.call_method(&activity, "launchSystemPicker", "(Ljava/lang/String;)V", &[jni::objects::JValue::Object(&album_id)]);
    }
}

impl eframe::App for WadioApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let (now, user_activity) = ctx.input(|i| (i.time, !i.events.is_empty() || i.pointer.any_down()));
        if crate::IDLE_RESET_REQUESTED.swap(false, Ordering::AcqRel) {
            self.last_activity = now;
            self.auth.refresh_window_security();
        }
        let viewing_photo = self.tab == ActiveTab::Pictures && self.viewer.is_some();
        if self.last_activity == 0.0 || user_activity || viewing_photo {
            // Passive photo viewing counts as activity, including the interval before closing it.
            self.last_activity = now;
        }
        let force_lock = crate::FORCE_LOCK_REQUESTED.swap(false, Ordering::AcqRel);
        let biometric_unlock = crate::BIOMETRIC_UNLOCK_REQUESTED.swap(false, Ordering::AcqRel);
        if force_lock {
            // Discard any simultaneous/stale biometric callback; backgrounding always wins.
            self.lock_vault();
            ctx.request_repaint();
        } else if biometric_unlock {
            if self.auth.unlock_with_biometric() {
                self.last_activity = now;
                self.pin_failed_attempts = 0;
                self.pin_lock_until_unix = 0;
                let db = self.db.lock();
                let _ = db.set_meta("pin_fail_count", "0");
                let _ = db.set_meta("pin_lock_until", "0");
                drop(db);
                self.auth_error = None;
                self.cached_albums = self.db.lock().get_albums().unwrap_or_default();
                if !self.cached_albums.iter().any(|a| a.id == self.selected_album) {
                    self.selected_album = self.cached_albums.first().map(|a| a.id.clone()).unwrap_or_else(|| "default".to_string());
                }
                self.tab = ActiveTab::Pictures;
                self.reload_active_tab();
                self.reload_album_covers();
            } else {
                self.auth_error = Some("Set up a PIN before using biometric unlock".into());
            }
            ctx.request_repaint();
        } else if self.auth.is_unlocked() && !viewing_photo && now - self.last_activity >= 60.0 {
            // Do not interrupt passive photo viewing. Idle timeout still applies in the gallery/grid.
            self.lock_vault();
        }
        self.thumbs.poll_results(ctx);
        if let Some(viewer) = self.viewer.as_mut() { viewer.poll(ctx); }
        if let Some((epoch, added, duplicates, failed, pending)) = crate::take_import_summary(self.last_import_epoch) {
            self.last_import_epoch = epoch;
            self.reload_active_tab();
            self.cached_albums = self.db.lock().get_albums().unwrap_or_default();
            self.reload_album_covers();
            self.import_notice = Some(format!(
                "Import results: {added} added, {duplicates} duplicate(s) skipped, {pending} pending recovery, {failed} failed."
            ));
            self.import_notice_since = Some(Instant::now());
            ctx.request_repaint();
        }
        if self.import_notice_since.map(|t| t.elapsed() >= std::time::Duration::from_secs(4)).unwrap_or(false) {
            self.import_notice = None;
            self.import_notice_since = None;
        }
        match self.auth_rx.try_recv() {
            Ok(mut result) => {
                if result.request_id != self.auth_request_id || !self.auth_pending {
                    if let Ok(hash) = &mut result.result { hash.zeroize(); }
                } else {
                self.auth_pending = false;
                match result.result {
                    Ok(mut hash) => {
                        // Persist the verifier before changing the in-memory auth state. A failed
                        // first-run DB write must never leave an unusable PIN configured in RAM.
                        let save_result = self.db.lock().set_meta("vault_pw", &hash);
                        if save_result.is_err() {
                            hash.zeroize();
                            self.auth_error = Some("Could not save PIN. Please try again.".into());
                        } else {
                            self.auth.unlock_with_hash(hash.clone());
                            hash.zeroize();
                            self.pin_failed_attempts = 0;
                            self.pin_lock_until_unix = 0;
                            let db = self.db.lock();
                            let _ = db.set_meta("pin_fail_count", "0");
                            let _ = db.set_meta("pin_lock_until", "0");
                            drop(db);
                            self.auth_error = None;
                            self.cached_albums = self.db.lock().get_albums().unwrap_or_default();
                            if !self.cached_albums.iter().any(|album| album.id == self.selected_album) {
                                self.selected_album = self.cached_albums.first()
                                    .map(|album| album.id.clone())
                                    .unwrap_or_else(|| "default".to_string());
                            }
                            self.tab = ActiveTab::Pictures;
                            self.reload_active_tab();
                            self.reload_album_covers();
                        }
                    }
                    Err(()) => {
                        self.pin_failed_attempts = self.pin_failed_attempts.saturating_add(1);
                        let delay = 2u64.saturating_pow(self.pin_failed_attempts.min(6)).min(60);
                        let now_unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
                        self.pin_lock_until_unix = now_unix.saturating_add(delay as i64);
                        let db = self.db.lock();
                        let _ = db.set_meta("pin_fail_count", &self.pin_failed_attempts.to_string());
                        let _ = db.set_meta("pin_lock_until", &self.pin_lock_until_unix.to_string());
                        self.auth_error = Some(format!("Incorrect PIN. Try again in {delay} seconds."));
                    },
                }
                }
            }
            Err(TryRecvError::Empty) => { if self.auth_pending { ctx.request_repaint_after(std::time::Duration::from_millis(50)); } }
            Err(TryRecvError::Disconnected) => { self.auth_pending = false; self.auth_error = Some("Authentication worker stopped".into()); }
        }

        if self.tab == ActiveTab::Audio {
            self.audio.poll_system_volumes();
            ctx.request_repaint_after(std::time::Duration::from_millis(500));
        } else {
            // Poll the import epoch while a gallery is visible so background JNI imports
            // trigger a refresh even when no thumbnail or input event requests a repaint.
            ctx.request_repaint_after(std::time::Duration::from_millis(500));
        }

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.horizontal(|ui| {
                let title = ui.add(
                    egui::Label::new(
                        RichText::new("Wadio")
                            .size(24.0)
                            .strong()
                            .color(Color32::from_rgb(230, 230, 230)),
                    )
                    .sense(egui::Sense::drag()),
                );

                let now = ctx.input(|i| i.time);
                let down = title.hovered() && ctx.input(|i| i.pointer.primary_down());
                self.auth.update_header_touch(down, now);

                if self.auth.is_unlocked() {
                    ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.button("🔒 Lock").clicked() {
                            self.lock_vault();
                        }
                    });
                }
            });

            ui.separator();

            if self.auth.should_show_prompt() {
                egui::Window::new("Authentication")
                    .collapsible(false)
                    .resizable(false)
                    .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                    .show(ctx, |ui| {
                        let is_setup = self.auth.needs_password_setup();
                        ui.label(if is_setup { "Create a 4-digit PIN:" } else { "Enter your 4-digit PIN:" });
                        let edit = ui.add(TextEdit::singleline(&mut self.pass_input).password(true).char_limit(4));
                        self.pass_input.retain(|c| c.is_ascii_digit());
                        let mut confirm_edit_changed = false;
                        if is_setup {
                            ui.label("Confirm PIN:");
                            let confirm_edit = ui.add(TextEdit::singleline(&mut self.confirm_pass_input).password(true).char_limit(4));
                            confirm_edit_changed = confirm_edit.changed();
                            self.confirm_pass_input.retain(|c| c.is_ascii_digit());
                        }
                        let submitted = edit.lost_focus() && ui.input(|i| i.key_pressed(Key::Enter));
                        let now_unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
                        let pin_locked = !is_setup && now_unix < self.pin_lock_until_unix;
                        if pin_locked {
                            ui.colored_label(Color32::YELLOW, format!("PIN temporarily locked for {} more second(s)", self.pin_lock_until_unix - now_unix));
                            ctx.request_repaint_after(std::time::Duration::from_secs(1));
                        }
                        let auto_ready = if is_setup {
                            confirm_edit_changed && self.pass_input.len() == 4 && self.confirm_pass_input.len() == 4
                        } else {
                            edit.changed() && self.pass_input.len() == 4
                        };
                        let submit_clicked = !self.auth_pending && !pin_locked && auto_ready;
                        if submit_clicked || (ui.add_enabled(!self.auth_pending && !pin_locked, egui::Button::new(if self.auth_pending { "Checking…" } else if is_setup { "Create PIN" } else { "Unlock with PIN" })).clicked() || (submitted && !self.auth_pending && !pin_locked)) {
                            if self.pass_input.len() != 4 || !self.pass_input.bytes().all(|b| b.is_ascii_digit()) {
                                self.auth_error = Some("PIN must contain exactly 4 digits".into());
                            } else if is_setup && (self.confirm_pass_input.len() != 4 || self.pass_input != self.confirm_pass_input) {
                                self.auth_error = Some(if self.confirm_pass_input.len() != 4 { "Enter the same 4-digit PIN in confirmation".into() } else { "PINs do not match".into() });
                                self.confirm_pass_input.zeroize();
                            } else {
                                let entered = std::mem::take(&mut self.pass_input).into_bytes();
                                self.confirm_pass_input.zeroize();
                                let persisted = self.auth.get_hash_for_storage();
                                let tx = self.auth_tx.clone();
                                self.auth_request_id = self.auth_request_id.wrapping_add(1);
                                let request_id = self.auth_request_id;
                                self.auth_pending = true;
                                self.auth_error = None;
                                std::thread::spawn(move || {
                                    let result = AuthEngine::check_password(persisted, entered);
                                    let _ = tx.send(AuthWorkerResult { request_id, result });
                                });
                                ctx.request_repaint_after(std::time::Duration::from_millis(50));
                            }
                        }
                        if edit.changed() || confirm_edit_changed { self.auth_error = None; }
                        ui.separator();
                        if is_setup {
                            ui.add_enabled(false, egui::Button::new("Fingerprint / device screen lock (set PIN first)"));
                        } else if ui.add_enabled(!self.auth_pending, egui::Button::new("Use fingerprint / device screen lock")).clicked() {
                            self.trigger_biometric();
                        }
                        if let Some(ref err) = self.auth_error { ui.colored_label(Color32::RED, err); }
                        if self.auth_pending { ui.label("Verifying PIN securely…"); }
                        if ui.add_enabled(!self.auth_pending, egui::Button::new("Cancel")).clicked() {
                            self.pass_input.zeroize();
                            self.confirm_pass_input.zeroize();
                            self.auth.dismiss_prompt();
                            self.auth_error = None;
                        }
                    });
            }

            match self.tab {
                ActiveTab::Audio => {
                    let states = self.audio.get_states();
                    render_audio_controls(ui, &states, |stream, val| {
                        self.audio.set_volume(stream, val);
                    });

                    if self.auth.is_unlocked() {
                        ui.add_space(20.0);
                        ui.separator();
                        ui.horizontal(|ui| {
                            if ui.button("📷 Pictures").clicked() {
                                self.tab = ActiveTab::Pictures;
                                self.reload_active_tab();
                            }
                            if ui.button("🎥 Videos").clicked() {
                                self.tab = ActiveTab::Videos;
                                self.reload_active_tab();
                            }
                        });
                    }
                }
                ActiveTab::Pictures | ActiveTab::Videos => {
                    let base = self.importer.base_dir.clone();
                    ui.horizontal(|ui| {
                        if ui.button("⬅ Audio").clicked() {
                            self.tab = ActiveTab::Audio;
                        }

                        let title = if self.tab == ActiveTab::Pictures { "Pictures" } else { "Videos" };
                        ui.heading(title);

                        ui.with_layout(Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.button("➕ Import").clicked() {
                                self.trigger_import();
                            }
                            if ui.button("📁 New Album").clicked() {
                                self.album_error = None;
                                self.show_new_album_modal = true;
                            }
                        });
                    });

                    ui.label("Albums (cover = first image in album):");
                    let mut chosen_album: Option<String> = None;
                    ui.horizontal_wrapped(|ui| {
                        for album in &self.cached_albums {
                            ui.vertical(|ui| {
                                let (rect, response) = ui.allocate_exact_size(egui::vec2(68.0, 68.0), egui::Sense::click());
                                if let Some(cover) = self.album_covers.get(&album.id) {
                                    let original = base.join(&cover.relative_path);
                                    let gif_thumb = base.join(".thumbnails").join(format!("{}.gif", cover.id));
                                    let thumb_path = if cover.file_name.to_ascii_lowercase().ends_with(".gif") && gif_thumb.is_file() { gif_thumb } else { original };
                                    if let Some(texture) = self.thumbs.get_or_request(&cover.id, thumb_path, ctx) {
                                        ui.painter().image(texture.id(), rect, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), Color32::WHITE);
                                    } else {
                                        ui.painter().rect_filled(rect, 3.0, Color32::from_gray(30));
                                    }
                                } else {
                                    ui.painter().rect_filled(rect, 3.0, Color32::from_gray(35));
                                    ui.painter().text(rect.center(), egui::Align2::CENTER_CENTER, "Empty", egui::FontId::proportional(11.0), Color32::GRAY);
                                }
                                if self.selected_album == album.id {
                                    ui.painter().rect_stroke(rect, 3.0, egui::Stroke::new(2.0, Color32::from_rgb(80, 170, 255)));
                                }
                                if response.clicked() { chosen_album = Some(album.id.clone()); }
                                if ui.selectable_label(self.selected_album == album.id, &album.name).clicked() {
                                    chosen_album = Some(album.id.clone());
                                }
                            });
                        }
                    });
                    if let Some(album_id) = chosen_album {
                        if album_id != self.selected_album {
                            self.selected_album = album_id;
                            self.selected_media_ids.clear();
                            self.selection_mode = false;
                            self.reload_active_tab();
                        }
                    }

                    ui.horizontal_wrapped(|ui| {
                        ui.label("Sort by:");
                        let current_sort = match self.sort_mode { MediaSortMode::Name => "Name", MediaSortMode::Size => "Size", MediaSortMode::DateCreated => "Date created" };
                        egui::ComboBox::from_id_source("media_sort_mode").selected_text(current_sort).show_ui(ui, |ui| {
                            if ui.selectable_label(self.sort_mode == MediaSortMode::Name, "Name").clicked() { self.sort_mode = MediaSortMode::Name; self.sort_cached_media(); }
                            if ui.selectable_label(self.sort_mode == MediaSortMode::Size, "Size").clicked() { self.sort_mode = MediaSortMode::Size; self.sort_cached_media(); }
                            if ui.selectable_label(self.sort_mode == MediaSortMode::DateCreated, "Date created").clicked() { self.sort_mode = MediaSortMode::DateCreated; self.sort_cached_media(); }
                        });
                        if ui.button(if self.sort_ascending { "Ascending ↑" } else { "Descending ↓" }).clicked() { self.sort_ascending = !self.sort_ascending; self.sort_cached_media(); }
                    });
                    if let Some(notice) = &self.import_notice { ui.label(notice); }
                    if self.selection_mode {
                        ui.horizontal_wrapped(|ui| {
                            ui.label(format!("{} selected", self.selected_media_ids.len()));
                            let destination_name = self.cached_albums.iter().find(|a| a.id == self.selection_destination && a.id != self.selected_album).map(|a| a.name.as_str()).unwrap_or("Choose destination");
                            egui::ComboBox::from_id_source("selection_destination_album")
                                .selected_text(destination_name)
                                .show_ui(ui, |ui| {
                                    for album in &self.cached_albums {
                                        if album.id != self.selected_album && ui.selectable_label(self.selection_destination == album.id, &album.name).clicked() {
                                            self.selection_destination = album.id.clone();
                                        }
                                    }
                                });
                            let has_destination = !self.selection_destination.is_empty() && self.selection_destination != self.selected_album && self.cached_albums.iter().any(|a| a.id == self.selection_destination);
                            if ui.add_enabled(has_destination && !self.selected_media_ids.is_empty(), egui::Button::new("Copy to")).clicked() {
                                let ids: Vec<String> = self.selected_media_ids.iter().cloned().collect();
                                let base = self.importer.base_dir.clone();
                                let mut db = self.db.lock();
                                let mut errors = 0;
                                for id in ids {
                                    let new_id = uuid::Uuid::new_v4().to_string();
                                    let prepared = db.prepare_media_copy(&id, &self.selection_destination, &new_id);
                                    match prepared {
                                        Ok((record, source_path, target_path)) => {
                                            let parent_ready = match target_path.parent() {
                                                Some(parent) => std::fs::create_dir_all(parent).is_ok(),
                                                None => false,
                                            };
                                            let copied = parent_ready && std::fs::copy(&source_path, &target_path).is_ok();
                                            if !copied {
                                                errors += 1;
                                                continue;
                                            }
                                            if db.insert_copied_media(&record).is_err() {
                                                let _ = std::fs::remove_file(&target_path);
                                                errors += 1;
                                                continue;
                                            }
                                            let source_thumb = base.join(".thumbnails").join(format!("{id}.gif"));
                                            let target_thumb = base.join(".thumbnails").join(format!("{new_id}.gif"));
                                            if source_thumb.is_file() { let _ = std::fs::copy(source_thumb, target_thumb); }
                                        }
                                        Err(_) => errors += 1,
                                    }
                                }
                                drop(db);
                                self.import_notice = Some(if errors == 0 { "Selected items copied to album".into() } else { format!("Some items could not be copied ({errors})") });
                                self.cached_albums = self.db.lock().get_albums().unwrap_or_default();
                                self.reload_album_covers();
                            }
                            if ui.add_enabled(has_destination && !self.selected_media_ids.is_empty(), egui::Button::new("Move to")).clicked() {
                                let ids: Vec<String> = self.selected_media_ids.iter().cloned().collect();
                                let moves_result = self.db.lock().move_media_to_album(&ids, &self.selection_destination);
                                match moves_result {
                                    Ok(moves) => {
                                        let mut moved: Vec<(std::path::PathBuf, std::path::PathBuf)> = Vec::new();
                                        let mut failed = false;
                                        for (old_path, new_path) in &moves {
                                            if let Some(parent) = new_path.parent() {
                                                if std::fs::create_dir_all(parent).is_err() { failed = true; break; }
                                            }
                                            if std::fs::rename(old_path, new_path).is_err() { failed = true; break; }
                                            moved.push((old_path.clone(), new_path.clone()));
                                        }
                                        if failed {
                                            for (old_path, new_path) in moved.iter().rev() { let _ = std::fs::rename(new_path, old_path); }
                                            let _ = self.db.lock().rollback_media_moves(&moves);
                                            self.album_error = Some("Some items could not be moved; database paths were rolled back".into());
                                        } else {
                                            self.selected_album = self.selection_destination.clone();
                                            self.selected_media_ids.clear();
                                            self.selection_mode = false;
                                            self.reload_active_tab();
                                            self.reload_album_covers();
                                        }
                                    }
                                    Err(_) => self.album_error = Some("Some items could not be moved".into()),
                                }
                            }
                            if ui.add_enabled(!self.selected_media_ids.is_empty(), egui::Button::new("Delete")).clicked() { self.confirm_delete_selected = true; }
                            if ui.button("Cancel selection").clicked() { self.selected_media_ids.clear(); self.selection_mode = false; }
                        });
                    } else {
                        ui.label("Tip: hold a photo for 2 seconds to select multiple items.");
                    }
                    ui.separator();

                    if self.tab == ActiveTab::Pictures && self.viewer.is_some() {
                        let index = self.cached_media.iter().position(|r| self.viewer.as_ref().map(|v| v.record_id == r.id).unwrap_or(false));
                        if let Some(index) = index {
                            let record = self.cached_media[index].clone();
                            let mut close = false; let mut prev = false; let mut next = false;
                            if let Some(viewer) = self.viewer.as_mut() {
                                let available = ui.available_size();
                                ui.allocate_ui_with_layout(available, Layout::top_down(egui::Align::Min), |ui| {
                                    (close, prev, next) = viewer.render(ui, ctx, &record.file_name, record.byte_size, index > 0, index + 1 < self.cached_media.len());
                                });
                            }
                            if close {
                                if let Some(viewer) = self.viewer.as_mut() { viewer.zeroize_sensitive(); }
                                self.viewer = None;
                                                    }
                            else if prev || next {
                                let new_index = if prev { index.saturating_sub(1) } else { (index + 1).min(self.cached_media.len().saturating_sub(1)) };
                                let target = self.cached_media[new_index].clone();
                                if let Some(viewer) = self.viewer.as_mut() { viewer.zeroize_sensitive(); }
                                self.viewer = Some(ImageViewerState::load(ctx, target.id.clone(), &base.join(&target.relative_path)));
                                                    }
                        } else {
                            if let Some(viewer) = self.viewer.as_mut() { viewer.zeroize_sensitive(); }
                            self.viewer = None;
                        }
                    } else {
                        let mut clicked_record: Option<MediaRecord> = None;
                        let mut long_pressed_record: Option<MediaRecord> = None;
                        render_media_grid(
                            ui, &self.cached_media, &base, &mut self.thumbs, 3, 105.0, 4.0,
                            &self.selected_media_ids,
                            &mut self.long_press_start, &mut self.suppress_click_id,
                            |record| clicked_record = Some(record.clone()),
                            |record| long_pressed_record = Some(record.clone()),
                        );
                        if let Some(record) = long_pressed_record {
                            self.selection_mode = true;
                            self.selected_media_ids.insert(record.id.clone());
                        }
                        if let Some(record) = clicked_record {
                            if self.selection_mode {
                                if !self.selected_media_ids.remove(&record.id) { self.selected_media_ids.insert(record.id.clone()); }
                                if self.selected_media_ids.is_empty() { self.selection_mode = false; }
                            } else if record.media_type == "Video" {
                                play_video(&record.relative_path);
                            } else {
                                if let Some(viewer) = self.viewer.as_mut() { viewer.zeroize_sensitive(); }
                                self.viewer = Some(ImageViewerState::load(ctx, record.id.clone(), &base.join(&record.relative_path)));
                            }
                        }
                    }
                }
            }

            if self.confirm_delete_selected {
                egui::Window::new("Delete selected media?")
                    .collapsible(false)
                    .resizable(false)
                    .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                    .show(ctx, |ui| {
                        ui.label(format!("Delete {} selected item(s)? This cannot be undone.", self.selected_media_ids.len()));
                        ui.horizontal(|ui| {
                            if ui.button("Delete permanently").clicked() {
                                let ids: Vec<String> = self.selected_media_ids.iter().cloned().collect();
                                let paths = self.db.lock().delete_media_items(&ids);
                                match paths {
                                    Ok(paths) => {
                                        for id in &ids { let _ = std::fs::remove_file(base_join_thumbnail(&self.importer.base_dir, id)); }
                                        for rel in paths {
                                            if is_safe_media_relative_path(&rel) { let _ = std::fs::remove_file(self.importer.base_dir.join(rel)); }
                                        }
                                        self.selected_media_ids.clear();
                                        self.selection_mode = false;
                                        self.confirm_delete_selected = false;
                                        self.thumbs.clear();
                                        self.reload_active_tab();
                                        self.reload_album_covers();
                                        self.import_notice = Some("Selected items deleted".into());
                                    }
                                    Err(_) => { self.album_error = Some("Delete failed; media records were not fully removed".into()); self.confirm_delete_selected = false; }
                                }
                            }
                            if ui.button("Cancel").clicked() { self.confirm_delete_selected = false; }
                        });
                    });
            }

            if self.show_new_album_modal {
                egui::Window::new("New Album")
                    .collapsible(false)
                    .anchor(egui::Align2::CENTER_CENTER, [0.0, 0.0])
                    .show(ctx, |ui| {
                        ui.text_edit_singleline(&mut self.new_album_input);
                        if let Some(ref err) = self.album_error {
                            ui.colored_label(Color32::RED, err);
                        }
                        ui.horizontal(|ui| {
                            if ui.button("Create").clicked() {
                                let name = self.new_album_input.trim();
                                if !name.is_empty() {
                                    let id = uuid::Uuid::new_v4().to_string();
                                    let create_result = { self.db.lock().create_album(&id, name) };
                                    match create_result {
                                        Ok(()) => {
                                            self.cached_albums = self.db.lock().get_albums().unwrap_or_default();
                                            self.selected_album = id.clone();
                                            self.selection_destination = "default".to_string();
                                            self.new_album_input.clear();
                                            self.show_new_album_modal = false;
                                            self.album_error = None;
                                            self.reload_active_tab();
                                            self.reload_album_covers();
                                        }
                                        Err(_) => self.album_error = Some("Album could not be created".into()),
                                    }
                                }
                            }
                            if ui.button("Cancel").clicked() {
                                self.show_new_album_modal = false;
                                self.album_error = None;
                            }
                        });
                    });
            }
        });

        if self.auth.is_pressing_header() {
            ctx.request_repaint();
        }
    }
}

fn base_join_thumbnail(base: &Path, id: &str) -> std::path::PathBuf {
    base.join(".thumbnails").join(format!("{id}.gif"))
}

fn is_safe_media_relative_path(value: &str) -> bool {
    let mut components = Path::new(value).components();
    matches!(components.next(), Some(std::path::Component::Normal(first)) if first == "media")
        && components.all(|part| matches!(part, std::path::Component::Normal(c) if !c.to_string_lossy().contains('\0')))
}

use crate::db::{InsertMediaError, MediaRecord, VaultDb};
use fs2::available_space;
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::{BufReader, Write};
use std::path::{Path, PathBuf};
use std::panic::{catch_unwind, AssertUnwindSafe};
use uuid::Uuid;

pub struct ImportSession {
    pub id: String,
    pub filename: String,
    pub staging_path: PathBuf,
    pub file: File,
    pub hasher: Sha256,
    pub written: u64,
    pub expected_size: u64,
    pub album_id: String,
}

pub struct ImportCoordinator {
    pub base_dir: PathBuf,
    pub staging_dir: PathBuf,
    pub media_dir: PathBuf,
    pub pending_dir: PathBuf,
}

impl ImportCoordinator {
    pub fn new(vault_root: PathBuf) -> Self {
        let staging_dir = vault_root.join(".staging");
        let media_dir = vault_root.join("media");
        let pending_dir = vault_root.join(".pending");
        fs::create_dir_all(&staging_dir).expect("create import staging directory");
        fs::create_dir_all(&media_dir).expect("create media directory");
        fs::create_dir_all(&pending_dir).expect("create pending import directory");

        let coordinator = Self {
            base_dir: vault_root,
            staging_dir,
            media_dir,
            pending_dir,
        };
        coordinator.recover_interrupted_imports();
        coordinator
    }

    fn is_safe_record_id(id: &str) -> bool {
        !id.is_empty() && id != "." && id != ".."
            && !id.contains('/') && !id.contains('\\') && !id.contains('\0')
    }

    fn is_safe_relative_media_path(relative_path: &str) -> bool {
        let path = Path::new(relative_path);
        let mut components = path.components();
        matches!(components.next(), Some(std::path::Component::Normal(first)) if first == "media")
            && components.all(|part| {
                matches!(part, std::path::Component::Normal(component)
                    if !component.to_string_lossy().contains('\0'))
            })
    }

    pub fn write_pending_record(&self, record: &MediaRecord) -> Result<(), String> {
        fs::create_dir_all(&self.pending_dir).map_err(|e| e.to_string())?;
        if !Self::is_safe_record_id(&record.id) {
            return Err("Unsafe pending record id".into());
        }
        if !Self::is_safe_relative_media_path(&record.relative_path) {
            return Err("Unsafe pending media path".into());
        }
        let final_path = self.pending_dir.join(format!("{}.json", record.id));
        let temp_path = self.pending_dir.join(format!("{}.json.tmp", record.id));
        let bytes = serde_json::to_vec(record).map_err(|e| e.to_string())?;
        let write_result = (|| -> Result<(), std::io::Error> {
            let mut file = std::fs::OpenOptions::new().write(true).create(true).truncate(true).open(&temp_path)?;
            file.write_all(&bytes)?;
            file.sync_all()?;
            Ok(())
        })();
        if let Err(err) = write_result {
            let _ = fs::remove_file(&temp_path);
            return Err(err.to_string());
        }
        fs::rename(&temp_path, &final_path).map_err(|e| {
            let _ = fs::remove_file(&temp_path);
            e.to_string()
        })
    }

    pub fn remove_pending_record(&self, id: &str) {
        let _ = fs::remove_file(self.pending_dir.join(format!("{id}.json")));
    }

    fn recover_pending_marker_temps(&self) {
        let Ok(entries) = fs::read_dir(&self.pending_dir) else { return; };
        for entry in entries.flatten() {
            if !entry.file_type().map(|t| t.is_file()).unwrap_or(false)
                || entry.path().extension().and_then(|e| e.to_str()) != Some("tmp") {
                continue;
            }
            let path = entry.path();
            let Ok(bytes) = fs::read(&path) else { continue; };
            if let Ok(record) = serde_json::from_slice::<MediaRecord>(&bytes) {
                let expected_temp = format!("{}.json.tmp", record.id);
                if Self::is_safe_record_id(&record.id)
                    && path.file_name().and_then(|n| n.to_str()) == Some(expected_temp.as_str())
                    && Self::is_safe_relative_media_path(&record.relative_path) {
                    let final_path = self.pending_dir.join(format!("{}.json", record.id));
                    if !final_path.exists() {
                        let _ = fs::rename(&path, final_path);
                    } else {
                        let _ = fs::remove_file(path);
                    }
                    continue;
                }
            }
            // Only remove malformed temporary marker files after the same grace period
            // used for abandoned staging data.
            let stale = entry.metadata().ok().and_then(|m| m.modified().ok())
                .and_then(|modified| std::time::SystemTime::now().duration_since(modified).ok())
                .map(|age| age >= std::time::Duration::from_secs(24 * 60 * 60)).unwrap_or(false);
            if stale { let _ = fs::remove_file(path); }
        }
    }

    fn read_pending_records(&self) -> Vec<(PathBuf, MediaRecord)> {
        let mut pending = Vec::new();
        let Ok(entries) = fs::read_dir(&self.pending_dir) else { return pending; };
        for entry in entries.flatten() {
            if !entry.file_type().map(|t| t.is_file()).unwrap_or(false)
                || entry.path().extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let Ok(bytes) = fs::read(entry.path()) else { continue; };
            let Ok(record) = serde_json::from_slice::<MediaRecord>(&bytes) else { continue; };
            let marker_id_matches = entry.path().file_stem().and_then(|s| s.to_str()) == Some(record.id.as_str());
            if Self::is_safe_record_id(&record.id) && marker_id_matches
                && Self::is_safe_relative_media_path(&record.relative_path) {
                pending.push((entry.path(), record));
            }
        }
        pending
    }

    /// Report unresolved pending imports without logging filenames or other media metadata.
    pub fn log_pending_summary(&self) {
        let pending = self.read_pending_records();
        let bytes: u64 = pending.iter().filter_map(|(path, _)| fs::metadata(path).ok())
            .map(|meta| meta.len()).sum();
        if !pending.is_empty() {
            eprintln!("wadio: {} pending recovery record(s), {} marker bytes", pending.len(), bytes);
        }
    }

    pub fn recover_pending_media(&self, db: &mut VaultDb) {
        for (marker, record) in self.read_pending_records() {
            let media_path = self.base_dir.join(&record.relative_path);
            if !media_path.is_file() {
                // A crash may have happened after the durable marker was written but before
                // the staging file was atomically renamed into media/.
                let staging_path = self.staging_dir.join(format!("{}.tmp", record.id));
                if staging_path.is_file() {
                    if let Some(parent) = media_path.parent() {
                        if fs::create_dir_all(parent).is_err() || fs::rename(&staging_path, &media_path).is_err() {
                            continue;
                        }
                    } else {
                        continue;
                    }
                } else {
                    // Keep the marker for diagnosis/recovery instead of fabricating a DB row.
                    continue;
                }
            }
            match db.insert_media(&record) {
                Ok(()) => { let _ = fs::remove_file(marker); }
                Err(InsertMediaError::Duplicate) => match db.is_record_committed(&record) {
                    Ok(true) => { let _ = fs::remove_file(marker); }
                    Ok(false) => {
                        let _ = fs::remove_file(&media_path);
                        let _ = fs::remove_file(self.base_dir.join(".thumbnails").join(format!("{}.gif", record.id)));
                        let _ = fs::remove_file(marker);
                    }
                    Err(err) => eprintln!("wadio: cannot resolve duplicate pending record {}: {err}", record.relative_path),
                },
                Err(InsertMediaError::Sql(err)) => {
                    eprintln!("wadio: pending media DB recovery failed for {}: {err}", record.relative_path);
                }
            }
        }
    }

    fn pending_relative_paths(&self) -> std::collections::HashSet<String> {
        self.read_pending_records().into_iter().map(|(_, record)| record.relative_path).collect()
    }

    fn cleanup_expired_orphans(&self) {
        const ORPHAN_MAX_AGE: std::time::Duration = std::time::Duration::from_secs(30 * 24 * 60 * 60);
        self.cleanup_expired_orphans_older_than(ORPHAN_MAX_AGE);
    }

    fn cleanup_expired_orphans_older_than(&self, max_age: std::time::Duration) {
        fn visit(dir: &Path, now: std::time::SystemTime, max_age: std::time::Duration) {
            let Ok(entries) = fs::read_dir(dir) else { return; };
            for entry in entries.flatten() {
                let path = entry.path();
                let Ok(kind) = entry.file_type() else { continue; };
                if kind.is_dir() {
                    visit(&path, now, max_age);
                    let _ = fs::remove_dir(&path); // succeeds only when empty
                } else if kind.is_file() {
                    let expired = entry.metadata().ok().and_then(|m| m.modified().ok())
                        .and_then(|modified| now.duration_since(modified).ok())
                        .map(|age| age >= max_age).unwrap_or(false);
                    if expired { let _ = fs::remove_file(path); }
                }
            }
        }
        let recovery_root = self.base_dir.join(".orphaned");
        if recovery_root.is_dir() {
            visit(&recovery_root, std::time::SystemTime::now(), max_age);
        }
    }

    /// Reconcile one physical folder per DB album without destructively deleting user data.
    /// Missing folders are recreated. Unknown folders are moved into `.orphaned` only when
    /// the database contains multiple albums, which reduces the risk of treating a missing or
    /// partially restored database as authoritative. Recovery folders are never overwritten.
    pub fn sync_album_folders(&self, album_ids: &std::collections::HashSet<String>) {
        let _ = fs::create_dir_all(&self.media_dir);
        for id in album_ids {
            if Self::is_safe_record_id(id) {
                let _ = fs::create_dir_all(self.media_dir.join(id));
            }
        }

        // A fresh, missing, or partially restored DB may report only the default album.
        // Preserve all other folders in that case rather than guessing that they are stale.
        if album_ids.len() <= 1 {
            return;
        }

        let recovery_root = self.base_dir.join(".orphaned");
        if let Ok(entries) = fs::read_dir(&self.media_dir) {
            for entry in entries.flatten() {
                if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) { continue; }
                let name = entry.file_name().to_string_lossy().into_owned();
                if album_ids.contains(&name) { continue; }

                if let Err(err) = fs::create_dir_all(&recovery_root) {
                    eprintln!("wadio: could not create orphan recovery directory: {err}");
                    continue;
                }

                // Preserve the complete folder by renaming it, never remove_dir_all it.
                // Use a unique destination if a prior recovery already used this name.
                let mut destination = recovery_root.join(&name);
                let mut suffix = 1u64;
                while destination.exists() {
                    destination = recovery_root.join(format!("{name}-{suffix}"));
                    suffix = suffix.saturating_add(1);
                    if suffix == u64::MAX && destination.exists() {
                        eprintln!("wadio: no unique recovery name available for album folder {name}");
                        destination = PathBuf::new();
                        break;
                    }
                }
                if destination.as_os_str().is_empty() { continue; }
                if let Err(err) = fs::rename(entry.path(), &destination) {
                    eprintln!("wadio: could not preserve orphan album folder {name}: {err}");
                }
            }
        }
    }

    pub fn recover_orphaned_media(&self, referenced_paths: &std::collections::HashSet<String>) {
        self.cleanup_expired_orphans();
        let pending_paths = self.pending_relative_paths();
        // An empty DB may mean a fresh install or a partial restore. Never infer that
        // all media is orphaned in that case. Preserve recoverable files instead of deleting them.
        if referenced_paths.is_empty() {
            return;
        }
        const ORPHAN_TTL: std::time::Duration = std::time::Duration::from_secs(24 * 60 * 60);
        let now = std::time::SystemTime::now();
        let recovery_root = self.base_dir.join(".orphaned");
        if let Ok(albums) = fs::read_dir(&self.media_dir) {
            for album in albums.flatten() {
                if !album.file_type().map(|t| t.is_dir()).unwrap_or(false) { continue; }
                if let Ok(files) = fs::read_dir(album.path()) {
                    for file in files.flatten() {
                        if !file.file_type().map(|t| t.is_file()).unwrap_or(false) { continue; }
                        let path = file.path();
                        let Ok(relative) = path.strip_prefix(&self.base_dir).map(|p| p.to_string_lossy().replace('\\', "/")) else { continue; };
                        if referenced_paths.contains(&relative) || pending_paths.contains(&relative) { continue; }
                        let old_enough = file.metadata().ok().and_then(|m| m.modified().ok())
                            .and_then(|modified| now.duration_since(modified).ok())
                            .map(|age| age >= ORPHAN_TTL).unwrap_or(false);
                        if !old_enough { continue; }
                        let destination = recovery_root.join(&relative);
                        let Some(parent) = destination.parent() else { continue; };
                        if fs::create_dir_all(parent).is_ok() {
                            // Preserve every orphan even if a prior recovery already used this path.
                            let mut candidate = destination.clone();
                            let mut suffix = 1u64;
                            while candidate.exists() {
                                let stem = destination.file_stem()
                                    .and_then(|s| s.to_str()).unwrap_or("orphan");
                                let extension = destination.extension().and_then(|s| s.to_str());
                                let name = match extension {
                                    Some(ext) if !ext.is_empty() => format!("{stem}-{suffix}.{ext}"),
                                    _ => format!("{stem}-{suffix}"),
                                };
                                candidate = parent.join(name);
                                suffix = suffix.saturating_add(1);
                                if suffix == u64::MAX && candidate.exists() { break; }
                            }
                            // Rename only; never delete a user media file during recovery.
                            let _ = fs::rename(&path, candidate);
                        }
                    }
                }
            }
        }
    }

    pub fn recover_interrupted_imports(&self) {
        // Remove only abandoned .tmp files older than 24 hours. Recent files may be
        // interrupted imports worth preserving; committed media is never touched here.
        const MAX_STAGING_AGE: std::time::Duration = std::time::Duration::from_secs(24 * 60 * 60);
        let _ = fs::create_dir_all(&self.staging_dir);
        self.recover_pending_marker_temps();
        let mut pending_ids: std::collections::HashSet<String> = self.read_pending_records()
            .into_iter().map(|(_, record)| record.id).collect();
        // A valid marker temp that could not yet be promoted must also protect its
        // matching staging file from the 24-hour cleanup.
        if let Ok(entries) = fs::read_dir(&self.pending_dir) {
            for entry in entries.flatten() {
                if entry.path().extension().and_then(|s| s.to_str()) != Some("tmp") { continue; }
                let Ok(bytes) = fs::read(entry.path()) else { continue; };
                if let Ok(record) = serde_json::from_slice::<MediaRecord>(&bytes) {
                    if Self::is_safe_relative_media_path(&record.relative_path) {
                        pending_ids.insert(record.id);
                    }
                }
            }
        }
        let now = std::time::SystemTime::now();
        if let Ok(entries) = fs::read_dir(&self.staging_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                let is_tmp = path.extension().and_then(|s| s.to_str()) == Some("tmp");
                let is_regular_file = entry.file_type().map(|t| t.is_file()).unwrap_or(false);
                if !is_tmp || !is_regular_file { continue; }
                let staging_id = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
                if pending_ids.contains(staging_id) { continue; }
                let stale = entry.metadata().ok().and_then(|m| m.modified().ok())
                    .and_then(|modified| now.duration_since(modified).ok())
                    .map(|age| age >= MAX_STAGING_AGE).unwrap_or(false);
                if stale { let _ = fs::remove_file(path); }
            }
        }
        // Discard only stale temporary thumbnail encodes; completed .gif files are retained.
        let thumbnail_dir = self.base_dir.join(".thumbnails");
        if let Ok(entries) = fs::read_dir(&thumbnail_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().and_then(|s| s.to_str()) != Some("tmp")
                    || !entry.file_type().map(|t| t.is_file()).unwrap_or(false) { continue; }
                let stale = entry.metadata().ok().and_then(|m| m.modified().ok())
                    .and_then(|modified| now.duration_since(modified).ok())
                    .map(|age| age >= MAX_STAGING_AGE).unwrap_or(false);
                if stale { let _ = fs::remove_file(path); }
            }
        }
    }

    pub fn start_stream(&self, declared_size: u64, original_name: &str) -> Result<ImportSession, String> {
        self.start_stream_for_album(declared_size, original_name, "default")
    }

    pub fn start_stream_for_album(&self, declared_size: u64, original_name: &str, album_id: &str) -> Result<ImportSession, String> {
        if album_id.is_empty() || album_id.contains('/') || album_id.contains('\\') || album_id == "." || album_id == ".." {
            return Err("Invalid album identifier".into());
        }
        let free = available_space(&self.base_dir).map_err(|e| e.to_string())?;
        let required = (declared_size as f64 * 1.15) as u64 + (15 * 1024 * 1024);

        if free < required {
            return Err("Insufficient storage capacity in app storage.".into());
        }

        let media_id = Uuid::new_v4().to_string();
        let staging_path = self.staging_dir.join(format!("{}.tmp", &media_id));
        let file = File::create(&staging_path).map_err(|e| e.to_string())?;

        Ok(ImportSession {
            id: media_id,
            filename: original_name.to_string(),
            staging_path,
            file,
            hasher: Sha256::new(),
            written: 0,
            expected_size: declared_size,
            album_id: album_id.to_string(),
        })
    }

    pub fn write_chunk(&self, session: &mut ImportSession, chunk: &[u8]) -> Result<(), String> {
        let next_written = session.written.checked_add(chunk.len() as u64)
            .ok_or_else(|| "Import size overflow".to_string())?;
        if session.expected_size > 0 && next_written > session.expected_size {
            return Err("Import chunk exceeds declared file size".into());
        }
        session.file.write_all(chunk).map_err(|e| e.to_string())?;
        session.hasher.update(chunk);
        session.written = next_written;
        Ok(())
    }

    fn create_animated_gif_thumbnail(&self, source: &Path, id: &str) -> Result<(), String> {
        use image::AnimationDecoder;
        let (w, h) = image::image_dimensions(source).map_err(|e| e.to_string())?;
        if w == 0 || h == 0 || w > 30000 || h > 30000 || (w as u64).saturating_mul(h as u64) > 50_000_000 {
            return Err("GIF exceeds Wadio's 50-megapixel thumbnail safety limit".into());
        }
        // Count a bounded number of frames first, then sample at most 20 frames evenly
        // across the animation. A hostile GIF with more than 10,000 frames won't cause an
        // unbounded thumbnail-generation pass; the original media remains intact.
        let count_decoder = image::codecs::gif::GifDecoder::new(BufReader::new(File::open(source).map_err(|e| e.to_string())?))
            .map_err(|e| e.to_string())?;
        let counted_frames = count_decoder.into_frames().take(10001).count();
        let total_frames = counted_frames.min(10000);
        if total_frames == 0 { return Err("GIF has no decodable frames".into()); }
        let sample_count = total_frames.min(20);
        let selected: std::collections::HashSet<usize> = if sample_count == 1 { [0usize].into_iter().collect() }
            else { (0..sample_count).map(|i| i * (total_frames - 1) / (sample_count - 1)).collect() };
        let decoder = image::codecs::gif::GifDecoder::new(BufReader::new(File::open(source).map_err(|e| e.to_string())?))
            .map_err(|e| e.to_string())?;
        let mut output_frames = Vec::with_capacity(sample_count);
        for (index, frame_result) in decoder.into_frames().take(10000).enumerate() {
            let frame = frame_result.map_err(|e| e.to_string())?;
            let (numer, denom) = frame.delay().numer_denom_ms();
            let delay_ms = if denom == 0 { 100 } else { (numer / denom).clamp(40, 1000) };
            let delay = image::Delay::from_numer_denom_ms(delay_ms, 1);
            let mut image = frame.into_buffer();
            if !selected.contains(&index) {
                image.as_mut().zeroize();
                continue;
            }
            let scaled = image::imageops::thumbnail(&image, 96, 96);
            image.as_mut().zeroize();
            output_frames.push(image::Frame::from_parts(scaled, 0, 0, delay));
        }
        if output_frames.is_empty() { return Err("GIF has no decodable frames".into()); }
        let thumb_dir = self.base_dir.join(".thumbnails");
        fs::create_dir_all(&thumb_dir).map_err(|e| e.to_string())?;
        let path = thumb_dir.join(format!("{id}.gif"));
        let temp_path = thumb_dir.join(format!("{id}.gif.tmp"));
        let file = File::create(&temp_path).map_err(|e| e.to_string())?;
        if let Err(err) = image::codecs::gif::GifEncoder::new(file).encode_frames(output_frames.into_iter()) {
            let _ = fs::remove_file(&temp_path);
            return Err(err.to_string());
        }
        if let Err(err) = File::open(&temp_path).and_then(|f| f.sync_all()) {
            let _ = fs::remove_file(&temp_path);
            return Err(err.to_string());
        }
        fs::rename(&temp_path, &path).map_err(|e| { let _ = fs::remove_file(&temp_path); e.to_string() })
    }

    pub fn finish_stream(&self, mut session: ImportSession, album_id: &str) -> Result<MediaRecord, String> {
        if session.written == 0 {
            return Err("Cannot import an empty file".into());
        }
        if session.expected_size > 0 && session.written != session.expected_size {
            return Err(format!("Incomplete import: expected {} bytes, received {}", session.expected_size, session.written));
        }
        session.file.sync_all().map_err(|e| e.to_string())?;
        drop(session.file);

        let hash_str = format!("{:x}", session.hasher.finalize());
        let ext = Path::new(&session.filename)
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| e.to_ascii_lowercase())
            .ok_or_else(|| "Unsupported media file: missing extension".to_string())?;
        if ext.len() > 10 || !ext.bytes().all(|b| b.is_ascii_alphanumeric()) {
            return Err("Unsupported media file extension".into());
        }
        let (media_type, width, height) = match ext.as_str() {
            "jpg" | "jpeg" | "jpe" | "jfif" | "png" | "webp" | "gif" | "bmp" | "dds" | "exr" | "ff" | "hdr" | "ico" | "pnm" | "pbm" | "pgm" | "ppm" | "pam" | "qoi" | "tga" | "tif" | "tiff" | "avif" => {
                // Staging files deliberately use a `.tmp` extension, so infer the
                // image format from its contents rather than the staging filename.
                let reader = image::ImageReader::open(&session.staging_path)
                    .and_then(|reader| reader.with_guessed_format())
                    .map_err(|_| "Image file is invalid or unsupported".to_string())?;
                let (w, h) = reader.into_dimensions()
                    .map_err(|_| "Image file is invalid or unsupported".to_string())?;
                if w == 0 || h == 0 || w > 30000 || h > 30000 || (w as u64).saturating_mul(h as u64) > 50_000_000 {
                    return Err("Image dimensions exceed Wadio's 50-megapixel import safety limit".into());
                }
                ("Image".to_string(), Some(w), Some(h))
            }
            "mp4" | "m4v" | "mov" | "webm" | "mkv" | "3gp" =>
                ("Video".to_string(), None, None),
            _ => return Err("Unsupported media type. Choose a supported image or video.".into()),
        };

        let final_album_dir = self.media_dir.join(album_id);
        fs::create_dir_all(&final_album_dir).map_err(|e| e.to_string())?;

        let final_filename = format!("{}.{}", session.id, ext);
        let final_destination = final_album_dir.join(&final_filename);

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let record = MediaRecord {
            id: session.id.clone(),
            album_id: album_id.to_string(),
            media_type,
            file_name: session.filename.clone(),
            byte_size: session.written,
            sha256: hash_str,
            width,
            height,
            duration_ms: None,
            imported_at: now,
            relative_path: format!("media/{}/{}", album_id, final_filename),
        };

        // Write a recovery marker before the filesystem commit so a process death in
        // between can resume the staging-to-media rename on the next launch.
        if let Err(err) = self.write_pending_record(&record) {
            eprintln!("wadio: could not write pre-commit recovery marker for {}: {err}", record.relative_path);
        }
        fs::rename(&session.staging_path, &final_destination)
            .map_err(|e| format!("Failed atomic commit; recovery marker retained when available: {}", e))?;

        // GIF thumbnails are smaller animated GIF files, not a single still frame.
        // This is best-effort: failure to generate a thumbnail never rejects the original media.
        if ext == "gif" {
            match catch_unwind(AssertUnwindSafe(|| self.create_animated_gif_thumbnail(&final_destination, &record.id))) {
                Ok(Ok(())) => {},
                Ok(Err(err)) => eprintln!("wadio: could not create animated GIF thumbnail for {}: {err}", record.id),
                Err(_) => eprintln!("wadio: GIF thumbnail decoder panicked for {}; original media was retained", record.id),
            }
        }
        Ok(record)
    }
}


#[cfg(test)]
mod recovery_tests {
    use super::*;
    use std::io::{BufReader, Write};

    #[test]
    fn relative_media_path_rejects_embedded_nul() {
        assert!(!ImportCoordinator::is_safe_relative_media_path("media/default/bad\0name.jpg"));
        assert!(ImportCoordinator::is_safe_relative_media_path("media/default/good.jpg"));
    }

    #[test]
    fn pending_record_id_rejects_path_separators_and_special_names() {
        for id in ["", ".", "..", "../escape", "sub/path", "sub\\path", "bad\0id"] {
            assert!(!ImportCoordinator::is_safe_record_id(id), "accepted unsafe id: {id:?}");
        }
        assert!(ImportCoordinator::is_safe_record_id("record-id_123"));
    }

    #[test]
    fn pending_marker_filename_must_match_record_id() {
        let temp = tempfile::tempdir().unwrap();
        let coordinator = ImportCoordinator::new(temp.path().to_path_buf());
        let record = MediaRecord {
            id: "record-id".into(),
            album_id: "default".into(),
            media_type: "Image".into(),
            file_name: "photo.jpg".into(),
            byte_size: 1,
            sha256: "hash".into(),
            width: None,
            height: None,
            duration_ms: None,
            imported_at: 1,
            relative_path: "media/default/photo.jpg".into(),
        };
        fs::write(coordinator.pending_dir.join("different-id.json"), serde_json::to_vec(&record).unwrap()).unwrap();
        assert!(coordinator.read_pending_records().is_empty());
    }

    #[test]
    fn orphan_cleanup_obeys_the_configured_age_limit() {
        let temp = tempfile::tempdir().unwrap();
        let coordinator = ImportCoordinator::new(temp.path().to_path_buf());
        let orphan_root = coordinator.base_dir.join(".orphaned").join("media").join("default");
        fs::create_dir_all(&orphan_root).unwrap();
        let recent = orphan_root.join("recent.jpg");
        File::create(&recent).unwrap().write_all(b"recent").unwrap();
        coordinator.cleanup_expired_orphans_older_than(std::time::Duration::from_secs(0));
        assert!(!recent.exists(), "zero-age cleanup should remove regular orphan files");
    }
}

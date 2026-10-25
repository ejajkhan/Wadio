#[cfg(all(any(target_os = "android", target_os = "ios"), panic = "abort"))]
compile_error!("Wadio requires panic = \"unwind\" for image decoder guards; see Cargo.toml");

pub mod app;
pub mod auth;
pub mod db;
pub mod importer;
pub mod platform;
pub mod thumbnails;
pub mod ui;

use app::WadioApp;
use auth::AuthEngine;
use db::VaultDb;
use importer::{ImportCoordinator, ImportSession};
use platform::audio::AndroidAudioController;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use parking_lot::Mutex;
use std::collections::HashMap;
use std::sync::LazyLock;
use std::path::PathBuf;
use std::sync::{Arc, atomic::{AtomicBool, AtomicU64, Ordering}};

static GLOBAL_IMPORTER: parking_lot::Mutex<Option<Arc<ImportCoordinator>>> = parking_lot::Mutex::new(None);
static GLOBAL_DB: parking_lot::Mutex<Option<Arc<Mutex<VaultDb>>>> = parking_lot::Mutex::new(None);
static IMPORT_HANDLES: LazyLock<parking_lot::Mutex<HashMap<u64, ImportSession>>> =
    LazyLock::new(|| parking_lot::Mutex::new(HashMap::new()));
static NEXT_IMPORT_HANDLE: AtomicU64 = AtomicU64::new(1);
/// Set by Android lifecycle callbacks; consumed by the egui thread before rendering.
pub static FORCE_LOCK_REQUESTED: AtomicBool = AtomicBool::new(false);
/// Set when MainActivity resumes, e.g. after returning from the separate video task.
pub static IDLE_RESET_REQUESTED: AtomicBool = AtomicBool::new(false);
/// Set only after Android BiometricPrompt reports biometric or device-credential success.
pub static BIOMETRIC_UNLOCK_REQUESTED: AtomicBool = AtomicBool::new(false);
struct ImportSummaryState {
    epoch: u64,
    added: u64,
    duplicates: u64,
    failed: u64,
    pending: u64,
}

static IMPORT_SUMMARY: Mutex<ImportSummaryState> = Mutex::new(ImportSummaryState {
    epoch: 0,
    added: 0,
    duplicates: 0,
    failed: 0,
    pending: 0,
});

pub fn current_import_epoch() -> u64 {
    IMPORT_SUMMARY.lock().epoch
}

pub fn record_import_status(status: u8) {
    let mut summary = IMPORT_SUMMARY.lock();
    match status {
        1 => summary.added += 1,
        2 => summary.duplicates += 1,
        4 => summary.pending += 1,
        _ => summary.failed += 1,
    }
    summary.epoch = summary.epoch.wrapping_add(1);
}

pub fn take_import_summary(last_seen_epoch: u64) -> Option<(u64, u64, u64, u64, u64)> {
    let mut summary = IMPORT_SUMMARY.lock();
    if summary.epoch == last_seen_epoch {
        return None;
    }
    let result = (summary.epoch, summary.added, summary.duplicates, summary.failed, summary.pending);
    summary.added = 0;
    summary.duplicates = 0;
    summary.failed = 0;
    summary.pending = 0;
    Some(result)
}

#[cfg(target_os = "android")]
#[no_mangle]
pub fn android_main(app: winit::platform::android::activity::AndroidApp) {
    let internal_data_path = app.internal_data_path().unwrap_or_else(|| PathBuf::from("/data/data/com.wadio.app/files"));
    let vault_root = internal_data_path.join("vault");
    std::fs::create_dir_all(&vault_root).expect("Must have access to app internal storage");
    let db_path = vault_root.join("wadio_vault.db");
    let database = Arc::new(Mutex::new(VaultDb::init(db_path).expect("SQLite init must succeed")));
    let coordinator = Arc::new(ImportCoordinator::new(vault_root));
    let album_ids: std::collections::HashSet<String> = database.lock().get_albums().unwrap_or_default().into_iter().map(|album| album.id).collect();
    coordinator.sync_album_folders(&album_ids);
    {
        let mut db_guard = database.lock();
        coordinator.recover_pending_media(&mut db_guard);
    }
    coordinator.log_pending_summary();
    if let Ok(paths) = database.lock().get_all_relative_paths() {
        coordinator.recover_orphaned_media(&paths);
    }
    *GLOBAL_IMPORTER.lock() = Some(coordinator.clone());
    *GLOBAL_DB.lock() = Some(database.clone());
    let saved_pw = database.lock().get_meta("vault_pw").ok().flatten();
    let auth = AuthEngine::new(saved_pw);
    let audio = AndroidAudioController::new();
    let options = eframe::NativeOptions {
        // eframe 0.28 does not expose `NativeOptions::android_app`.
        // Pass the Android activity through its event-loop builder hook instead.
        event_loop_builder: Some(Box::new(move |event_loop_builder| {
            use winit::platform::android::EventLoopBuilderExtAndroid;
            event_loop_builder.with_android_app(app);
        })),
        ..Default::default()
    };
    let _ = eframe::run_native("Wadio", options, Box::new(|_cc| Ok(Box::new(WadioApp::new(audio, auth, database, coordinator)))));
}


#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeRequestLock(
    _env: JNIEnv, _this: JObject,
) {
    FORCE_LOCK_REQUESTED.store(true, Ordering::Release);
}

#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeResetIdle(
    _env: JNIEnv, _this: JObject,
) {
    IDLE_RESET_REQUESTED.store(true, Ordering::Release);
}

#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeBiometricSuccess(
    _env: JNIEnv, _this: JObject,
) {
    BIOMETRIC_UNLOCK_REQUESTED.store(true, Ordering::Release);
}

#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeBeginImport(
    mut env: JNIEnv, _this: JObject, declared_size: jlong, j_filename: JString, j_album_id: JString,
) -> jlong {
    if declared_size < 0 {
        record_import_status(3);
        return 0;
    }
    let filename: String = match env.get_string(&j_filename) {
        Ok(value) => value.into(),
        Err(_) => { record_import_status(3); return 0; }
    };
    let album_id: String = match env.get_string(&j_album_id) {
        Ok(value) => value.into(),
        Err(_) => { record_import_status(3); return 0; }
    };
    let Some(importer) = GLOBAL_IMPORTER.lock().clone() else {
        record_import_status(3);
        return 0;
    };
    let session = match importer.start_stream_for_album(declared_size as u64, &filename, &album_id) {
        Ok(session) => session,
        Err(_) => { record_import_status(3); return 0; }
    };
    let handle = NEXT_IMPORT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle == 0 || handle > i64::MAX as u64 {
        let path = session.staging_path.clone();
        drop(session);
        let _ = std::fs::remove_file(path);
        record_import_status(3);
        return 0;
    }
    IMPORT_HANDLES.lock().insert(handle, session);
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeFeedChunk(
    mut env: JNIEnv, _this: JObject, handle: jlong, chunk: JByteArray, size: jint,
) -> jboolean {
    if handle <= 0 || size <= 0 || size > 1024 * 1024 { return 0; }
    let Ok(array_len) = env.get_array_length(&chunk) else { return 0; };
    if size > array_len { return 0; }
    let mut buf = vec![0u8; size as usize];
    if env.get_byte_array_region(&chunk, 0, bytemuck_cast(&mut buf)).is_err() { return 0; }
    let Some(importer) = GLOBAL_IMPORTER.lock().clone() else { return 0; };
    let mut handles = IMPORT_HANDLES.lock();
    let Some(session) = handles.get_mut(&(handle as u64)) else { return 0; };
    match importer.write_chunk(session, &buf) { Ok(()) => 1, Err(_) => 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeAbortImport(
    _env: JNIEnv, _this: JObject, handle: jlong,
) {
    if handle <= 0 { return; }
    // Removal is atomic under the registry lock: only the first terminal call owns the session.
    if let Some(session) = IMPORT_HANDLES.lock().remove(&(handle as u64)) {
        let path = session.staging_path.clone();
        drop(session);
        let _ = std::fs::remove_file(path);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wadio_app_MainActivity_nativeFinishImport(
    _env: JNIEnv, _this: JObject, handle: jlong,
) {
    if handle <= 0 { return; }
    let Some(session) = IMPORT_HANDLES.lock().remove(&(handle as u64)) else { return; };
    let staging_path = session.staging_path.clone();
    let album_id = session.album_id.clone();
    let Some(importer) = GLOBAL_IMPORTER.lock().clone() else {
        let _ = std::fs::remove_file(staging_path);
        record_import_status(3);
        return;
    };
    let Some(db) = GLOBAL_DB.lock().clone() else {
        let _ = std::fs::remove_file(staging_path);
        record_import_status(3);
        return;
    };
    let status = match importer.finish_stream(session, &album_id) {
        Ok(record) => {
            // finish_stream writes the durable marker before the staging-to-media rename.
            // Do not rewrite and fsync the same marker a second time here.
            let marker_written = importer.pending_dir.join(format!("{}.json", record.id)).is_file();
            if !marker_written {
                eprintln!("wadio: committed media has no recovery marker: {}", record.relative_path);
            }
            let first_insert = { db.lock().insert_media(&record) };
            match first_insert {
                Ok(()) => {
                    importer.remove_pending_record(&record.id);
                    1
                }
                Err(crate::db::InsertMediaError::Duplicate) => match db.lock().is_record_committed(&record) {
                    Ok(true) => { importer.remove_pending_record(&record.id); 1 }
                    Ok(false) => {
                        importer.remove_pending_record(&record.id);
                        let _ = std::fs::remove_file(importer.base_dir.join(&record.relative_path));
                        let _ = std::fs::remove_file(importer.base_dir.join(".thumbnails").join(format!("{}.gif", record.id)));
                        2
                    }
                    Err(err) => {
                        eprintln!("wadio: cannot resolve duplicate import {}: {err}", record.relative_path);
                        if marker_written { 4 } else { 3 }
                    }
                },
                Err(crate::db::InsertMediaError::Sql(first_err)) => {
                    // A single retry handles transient SQLite/WAL pressure. The durable
                    // marker remains if the retry fails, for startup recovery.
                    let retry_insert = { db.lock().insert_media(&record) };
                    match retry_insert {
                        Ok(()) => {
                            importer.remove_pending_record(&record.id);
                            1
                        }
                        Err(crate::db::InsertMediaError::Duplicate) => match db.lock().is_record_committed(&record) {
                            Ok(true) => { importer.remove_pending_record(&record.id); 1 }
                            Ok(false) => {
                                importer.remove_pending_record(&record.id);
                                let _ = std::fs::remove_file(importer.base_dir.join(&record.relative_path));
                                let _ = std::fs::remove_file(importer.base_dir.join(".thumbnails").join(format!("{}.gif", record.id)));
                                2
                            }
                            Err(err) => {
                                eprintln!("wadio: cannot resolve duplicate import after retry {}: {err}", record.relative_path);
                                if marker_written { 4 } else { 3 }
                            }
                        },
                        Err(crate::db::InsertMediaError::Sql(retry_err)) => {
                            eprintln!("wadio: DB insert failed for {}: first={first_err}; retry={retry_err}; recovery_marker={marker_written}", record.relative_path);
                            if marker_written { 4 } else { 3 }
                        }
                    }
                }
            }
        }
        // Keep staging data on a finish error. If a pre-commit marker exists, startup
        // recovery can retry the atomic rename; otherwise stale staging cleanup handles it.
        Err(_) => {
            let has_recovery_marker = staging_path.file_stem().and_then(|id| id.to_str())
                .map(|id| importer.pending_dir.join(format!("{id}.json")).is_file())
                .unwrap_or(false);
            if has_recovery_marker { 4 } else { 3 }
        }
    };
    record_import_status(status);
}

fn bytemuck_cast(buf: &mut [u8]) -> &mut [i8] {
    unsafe { std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut i8, buf.len()) }
}

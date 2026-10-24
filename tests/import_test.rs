use std::fs::File;
use std::io::Write;
use tempfile::tempdir;
use wadio::db::VaultDb;
use wadio::importer::ImportCoordinator;

#[test]
fn test_interrupted_staging_is_preserved() {
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let staging = root.join(".staging");
    std::fs::create_dir_all(&staging).unwrap();

    let orphan = staging.join("broken.tmp");
    File::create(&orphan).unwrap().write_all(b"corrupted").unwrap();
    assert!(orphan.exists());

    let _coordinator = ImportCoordinator::new(root);
    assert!(orphan.exists(), "startup must not silently delete partial user imports");
}

#[test]
fn test_stream_import_and_hashing() {
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let coordinator = ImportCoordinator::new(root.clone());
    let mut db = VaultDb::init(root.join("test.db")).unwrap();

    let dummy_data: &[u8] = &[137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82,0,0,0,1,0,0,0,1,8,6,0,0,0,31,21,196,137,0,0,0,13,73,68,65,84,8,215,99,248,207,192,240,31,0,5,0,1,255,137,153,61,29,0,0,0,0,73,69,78,68,174,66,96,130];
    let mut session = coordinator.start_stream(dummy_data.len() as u64, "sample.png").unwrap();
    coordinator.write_chunk(&mut session, dummy_data).unwrap();
    let record = coordinator.finish_stream(session, "default").unwrap();

    assert_eq!(record.byte_size, dummy_data.len() as u64);
    assert!(db.insert_media(&record).is_ok());
    coordinator.remove_pending_record(&record.id);
    assert!(db.insert_media(&record).is_err(), "duplicate content hash must be rejected");

    let records = db.get_media_by_album("default", 10, 0).unwrap();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].sha256, record.sha256);
}

#[test]
fn test_orphan_recovery_never_deletes_media_when_database_is_empty() {
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let media_dir = root.join("media").join("default");
    std::fs::create_dir_all(&media_dir).unwrap();
    let media_file = media_dir.join("restored-photo.jpg");
    File::create(&media_file).unwrap().write_all(b"user media").unwrap();

    let coordinator = ImportCoordinator::new(root);
    coordinator.recover_orphaned_media(&std::collections::HashSet::new());
    assert!(media_file.exists(), "an empty database must never cause media deletion");
}


#[test]
fn test_pending_media_record_is_recovered_into_database() {
    use wadio::db::MediaRecord;
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let coordinator = ImportCoordinator::new(root.clone());
    let relative_path = "media/default/pending-record.png".to_string();
    let media_path = root.join(&relative_path);
    std::fs::create_dir_all(media_path.parent().unwrap()).unwrap();
    File::create(&media_path).unwrap().write_all(b"pending media bytes").unwrap();
    let record = MediaRecord {
        id: "pending-record".into(),
        album_id: "default".into(),
        media_type: "Image".into(),
        file_name: "pending-record.png".into(),
        byte_size: 19,
        sha256: "pending-test-hash".into(),
        width: Some(1),
        height: Some(1),
        duration_ms: None,
        imported_at: 1,
        relative_path: relative_path.clone(),
    };
    coordinator.write_pending_record(&record).unwrap();
    let mut db = VaultDb::init(root.join("pending-test.db")).unwrap();
    coordinator.recover_pending_media(&mut db);
    let records = db.get_media_by_album("default", 10, 0).unwrap();
    assert_eq!(records.len(), 1);
    assert_eq!(records[0].relative_path, relative_path);
    assert!(!coordinator.pending_dir.join("pending-record.json").exists());
    assert!(media_path.exists());
}


#[test]
fn test_pending_marker_recovers_staging_file_after_interrupted_commit() {
    use wadio::db::MediaRecord;
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let coordinator = ImportCoordinator::new(root.clone());
    let staging_path = coordinator.staging_dir.join("recover-me.tmp");
    File::create(&staging_path).unwrap().write_all(b"recover me").unwrap();
    let record = MediaRecord {
        id: "recover-me".into(),
        album_id: "default".into(),
        media_type: "Video".into(),
        file_name: "recover-me.mp4".into(),
        byte_size: 10,
        sha256: "recover-test-hash".into(),
        width: None,
        height: None,
        duration_ms: None,
        imported_at: 1,
        relative_path: "media/default/recover-me.mp4".into(),
    };
    coordinator.write_pending_record(&record).unwrap();
    let mut db = VaultDb::init(root.join("recover-test.db")).unwrap();
    coordinator.recover_pending_media(&mut db);
    let final_path = root.join(&record.relative_path);
    assert!(final_path.exists(), "recovery should finish the staging-to-media rename");
    assert!(!staging_path.exists());
    assert_eq!(db.get_media_by_album("default", 10, 0).unwrap().len(), 1);
}

#[test]
fn test_copy_move_delete_uses_independent_album_files() {
    use wadio::db::MediaRecord;
    let dir = tempdir().unwrap();
    let mut db = VaultDb::init(dir.path().join("albums.db")).unwrap();
    db.create_album("other", "Other").unwrap();
    let record = MediaRecord {
        id: "source-item".into(),
        album_id: "default".into(),
        media_type: "Image".into(),
        file_name: "photo.png".into(),
        byte_size: 12,
        sha256: "same-hash".into(),
        width: Some(2),
        height: Some(2),
        duration_ms: None,
        imported_at: 1,
        relative_path: "media/default/photo.png".into(),
    };
    db.insert_media(&record).unwrap();
    let source_path = dir.path().join(&record.relative_path);
    std::fs::create_dir_all(source_path.parent().unwrap()).unwrap();
    std::fs::write(&source_path, b"photo bytes").unwrap();
    let (copied_record, old_path, new_path) = db.prepare_media_copy("source-item", "other", "copy-item").unwrap();
    assert_eq!(old_path, source_path);
    std::fs::create_dir_all(new_path.parent().unwrap()).unwrap();
    std::fs::copy(&old_path, &new_path).unwrap();
    db.insert_copied_media(&copied_record).unwrap();
    assert_eq!(db.get_media_by_album("other", 10, 0).unwrap().len(), 1);
    assert_eq!(db.get_media_by_album("other", 10, 0).unwrap()[0].relative_path, "media/other/copy-item.png");
    let removed_source = db.delete_media_items(&["source-item".into()]).unwrap();
    assert_eq!(removed_source, vec!["media/default/photo.png".to_string()]);
    let removed_copy = db.delete_media_items(&["copy-item".into()]).unwrap();
    assert_eq!(removed_copy, vec!["media/other/copy-item.png".to_string()]);
}

#[test]
fn test_move_media_updates_physical_path_and_album_association() {
    use wadio::db::MediaRecord;
    let dir = tempdir().unwrap();
    let mut db = VaultDb::init(dir.path().join("move.db")).unwrap();
    db.create_album("other", "Other").unwrap();
    let record = MediaRecord {
        id: "move-item".into(), album_id: "default".into(), media_type: "Image".into(),
        file_name: "photo.png".into(), byte_size: 12, sha256: "move-hash".into(),
        width: Some(2), height: Some(2), duration_ms: None, imported_at: 1,
        relative_path: "media/default/photo.png".into(),
    };
    db.insert_media(&record).unwrap();
    let moves = db.move_media_to_album(&["move-item".into()], "other").unwrap();
    assert_eq!(moves.len(), 1);
    assert_eq!(moves[0].0, dir.path().join("media/default/photo.png"));
    assert_eq!(moves[0].1, dir.path().join("media/other/photo.png"));
    assert!(db.get_media_by_album("default", 10, 0).unwrap().is_empty());
    assert_eq!(db.get_media_by_album("other", 10, 0).unwrap()[0].relative_path, "media/other/photo.png");
}

#[test]
fn test_gif_import_generates_animated_thumbnail() {
    use image::{Delay, Frame, Rgba, RgbaImage};
    use image::codecs::gif::GifEncoder;
    use image::AnimationDecoder;
    use std::io::Cursor;

    let mut bytes = Vec::new();
    {
        let mut encoder = GifEncoder::new(Cursor::new(&mut bytes));
        let frames = vec![
            Frame::from_parts(RgbaImage::from_pixel(8, 8, Rgba([255, 0, 0, 255])), 0, 0, Delay::from_numer_denom_ms(100, 1)),
            Frame::from_parts(RgbaImage::from_pixel(8, 8, Rgba([0, 0, 255, 255])), 0, 0, Delay::from_numer_denom_ms(100, 1)),
        ];
        encoder.encode_frames(frames.into_iter()).unwrap();
    }
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let coordinator = ImportCoordinator::new(root.clone());
    let mut session = coordinator.start_stream(bytes.len() as u64, "animated.gif").unwrap();
    coordinator.write_chunk(&mut session, &bytes).unwrap();
    let record = coordinator.finish_stream(session, "default").unwrap();
    let thumbnail = root.join(".thumbnails").join(format!("{}.gif", record.id));
    assert!(thumbnail.exists(), "GIF import should create an animated thumbnail");
    let decoder = image::codecs::gif::GifDecoder::new(std::io::BufReader::new(File::open(thumbnail).unwrap())).unwrap();
    assert!(decoder.into_frames().count() >= 2, "generated thumbnail should preserve animation frames");
}


#[test]
fn test_album_sync_preserves_restored_folders_when_db_has_only_default_album() {
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let restored_dir = root.join("media").join("restored-album");
    std::fs::create_dir_all(&restored_dir).unwrap();
    let restored_file = restored_dir.join("important-photo.jpg");
    File::create(&restored_file).unwrap().write_all(b"restored photo").unwrap();

    let coordinator = ImportCoordinator::new(root.clone());
    let album_ids = std::collections::HashSet::from(["default".to_string()]);
    coordinator.sync_album_folders(&album_ids);

    assert!(restored_file.exists(), "folder sync must preserve restored files when only default album is known");
    assert!(!root.join(".orphaned").join("restored-album").exists());
}

#[test]
fn test_album_sync_moves_unknown_folders_to_recovery_without_deleting_them() {
    let dir = tempdir().unwrap();
    let root = dir.path().to_path_buf();
    let restored_dir = root.join("media").join("restored-album");
    std::fs::create_dir_all(&restored_dir).unwrap();
    let restored_file = restored_dir.join("important-photo.jpg");
    File::create(&restored_file).unwrap().write_all(b"restored photo").unwrap();

    let coordinator = ImportCoordinator::new(root.clone());
    let album_ids = std::collections::HashSet::from(["default".to_string(), "album-2".to_string()]);
    coordinator.sync_album_folders(&album_ids);

    let recovered_file = root.join(".orphaned").join("restored-album").join("important-photo.jpg");
    assert!(recovered_file.exists(), "unknown album folders must be moved to recovery, not deleted");
    assert!(root.join("media").join("default").is_dir());
    assert!(root.join("media").join("album-2").is_dir());
}

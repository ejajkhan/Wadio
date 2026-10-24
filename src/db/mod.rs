use rusqlite::{params, Connection, OptionalExtension, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub enum InsertMediaError { Duplicate, Sql(rusqlite::Error) }

impl From<rusqlite::Error> for InsertMediaError {
    fn from(value: rusqlite::Error) -> Self { Self::Sql(value) }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MediaRecord {
    pub id: String,
    pub album_id: String,
    pub media_type: String,
    pub file_name: String,
    pub byte_size: u64,
    pub sha256: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub duration_ms: Option<u64>,
    pub imported_at: i64,
    pub relative_path: String,
}

#[derive(Debug, Clone)]
pub struct AlbumRecord {
    pub id: String,
    pub name: String,
    pub created_at: i64,
}

pub struct VaultDb {
    conn: Connection,
    vault_root: PathBuf,
}

impl VaultDb {
    pub fn init<P: AsRef<Path>>(path: P) -> Result<Self> {
        let db_path = path.as_ref();
        let vault_root = db_path.parent().unwrap_or_else(|| Path::new(".")).to_path_buf();
        let conn = Connection::open(db_path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous = NORMAL;
             PRAGMA foreign_keys = ON;
             PRAGMA temp_store = MEMORY;"
        )?;

        let db = Self { conn, vault_root };
        db.run_migrations()?;
        Ok(db)
    }

    fn run_migrations(&self) -> Result<()> {
        self.conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                val TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS albums (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS media_items (
                id TEXT PRIMARY KEY,
                album_id TEXT NOT NULL,
                media_type TEXT CHECK(media_type IN ('Image', 'Video')) NOT NULL,
                file_name TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                width INTEGER,
                height INTEGER,
                duration_ms INTEGER,
                imported_at INTEGER NOT NULL,
                relative_path TEXT NOT NULL,
                FOREIGN KEY(album_id) REFERENCES albums(id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_media_album_time ON media_items (album_id, imported_at DESC);
            CREATE INDEX IF NOT EXISTS idx_media_sha ON media_items (sha256);
            
            INSERT OR IGNORE INTO albums (id, name, created_at) VALUES ('default', 'Default', 0);"
        )?;
        Ok(())
    }

    pub fn insert_media(&mut self, record: &MediaRecord) -> std::result::Result<(), InsertMediaError> {
        let duplicate: bool = self.conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM media_items WHERE sha256 = ?1)",
            params![record.sha256],
            |row| row.get(0),
        )?;
        if duplicate { return Err(InsertMediaError::Duplicate); }
        let tx = self.conn.transaction()?;
        tx.execute(
            "INSERT INTO media_items (
                id, album_id, media_type, file_name, byte_size, sha256,
                width, height, duration_ms, imported_at, relative_path
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![
                record.id,
                record.album_id,
                record.media_type,
                record.file_name,
                record.byte_size as i64,
                record.sha256,
                record.width,
                record.height,
                record.duration_ms.map(|d| d as i64),
                record.imported_at,
                record.relative_path,
            ],
        )?;
        tx.commit()
    }

    pub fn is_record_committed(&self, record: &MediaRecord) -> Result<bool> {
        self.conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM media_items WHERE id = ?1 AND relative_path = ?2 AND sha256 = ?3)",
            params![record.id, record.relative_path, record.sha256],
            |row| row.get(0),
        )
    }

    pub fn get_all_relative_paths(&self) -> Result<std::collections::HashSet<String>> {
        let mut stmt = self.conn.prepare("SELECT relative_path FROM media_items")?;
        let rows = stmt.query_map([], |row| row.get::<_, String>(0))?;
        let mut paths = std::collections::HashSet::new();
        for row in rows { paths.insert(row?); }
        Ok(paths)
    }

    pub fn get_media_by_album(&self, album_id: &str, limit: usize, offset: usize) -> Result<Vec<MediaRecord>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, album_id, media_type, file_name, byte_size, sha256,
                    width, height, duration_ms, imported_at, relative_path
             FROM media_items
             WHERE album_id = ?1
             ORDER BY imported_at DESC
             LIMIT ?2 OFFSET ?3",
        )?;

        let iter = stmt.query_map(params![album_id, limit as i64, offset as i64], |row| {
            Ok(MediaRecord {
                id: row.get(0)?,
                album_id: row.get(1)?,
                media_type: row.get(2)?,
                file_name: row.get(3)?,
                byte_size: row.get::<_, i64>(4)? as u64,
                sha256: row.get(5)?,
                width: row.get(6)?,
                height: row.get(7)?,
                duration_ms: row.get::<_, Option<i64>>(8)?.map(|d| d as u64),
                imported_at: row.get(9)?,
                relative_path: row.get(10)?,
            })
        })?;

        let mut res = Vec::new();
        for item in iter {
            res.push(item?);
        }
        Ok(res)
    }

    /// Returns the first image in gallery ordering for use as the album poster.
    pub fn get_album_cover(&self, album_id: &str) -> Result<Option<MediaRecord>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, album_id, media_type, file_name, byte_size, sha256,
                    width, height, duration_ms, imported_at, relative_path
             FROM media_items WHERE album_id = ?1 AND media_type = 'Image'
             ORDER BY imported_at DESC, id ASC LIMIT 1"
        )?;
        let mut rows = stmt.query(params![album_id])?;
        if let Some(row) = rows.next()? {
            Ok(Some(MediaRecord {
                id: row.get(0)?, album_id: row.get(1)?, media_type: row.get(2)?,
                file_name: row.get(3)?, byte_size: row.get::<_, i64>(4)? as u64,
                sha256: row.get(5)?, width: row.get(6)?, height: row.get(7)?,
                duration_ms: row.get::<_, Option<i64>>(8)?.map(|d| d as u64),
                imported_at: row.get(9)?, relative_path: row.get(10)?,
            }))
        } else { Ok(None) }
    }

    /// Prepare a physically independent copy. The caller must copy the file first, then
    /// commit the returned record with insert_copied_media; normal imports still reject duplicates.
    pub fn prepare_media_copy(&self, source_id: &str, destination_album: &str, new_id: &str) -> Result<(MediaRecord, PathBuf, PathBuf)> {
        if !Self::safe_album_id(destination_album) || !Self::safe_album_id(new_id) {
            return Err(rusqlite::Error::InvalidParameterName("unsafe album or media id".into()));
        }
        let mut record: MediaRecord = self.conn.query_row(
            "SELECT id, album_id, media_type, file_name, byte_size, sha256, width, height, duration_ms, imported_at, relative_path FROM media_items WHERE id = ?1",
            params![source_id],
            |row| Ok(MediaRecord {
                id: row.get(0)?, album_id: row.get(1)?, media_type: row.get(2)?, file_name: row.get(3)?,
                byte_size: row.get::<_, i64>(4)? as u64, sha256: row.get(5)?, width: row.get(6)?, height: row.get(7)?,
                duration_ms: row.get::<_, Option<i64>>(8)?.map(|d| d as u64), imported_at: row.get(9)?, relative_path: row.get(10)?,
            })?;
        record.id = new_id.to_string();
        record.album_id = destination_album.to_string();
        record.imported_at = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_secs() as i64;
        let extension = Path::new(&record.relative_path).extension().and_then(|v| v.to_str()).unwrap_or("bin");
        let filename = format!("{}.{}", new_id, extension);
        record.relative_path = format!("media/{}/{}", destination_album, filename);
        let old_path = self.vault_root.join(Path::new(&self.get_media_path(source_id)?));
        let new_path = self.vault_root.join(&record.relative_path);
        Ok((record, old_path, new_path))
    }

    /// Insert a copied row after its independent destination file has been created.
    /// Unlike a new import, a copy is allowed to share the same content hash.
    pub fn insert_copied_media(&mut self, record: &MediaRecord) -> Result<()> {
        self.conn.execute(
            "INSERT INTO media_items (id, album_id, media_type, file_name, byte_size, sha256, width, height, duration_ms, imported_at, relative_path) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
            params![record.id, record.album_id, record.media_type, record.file_name, record.byte_size as i64, record.sha256,
                record.width, record.height, record.duration_ms.map(|d| d as i64), record.imported_at, record.relative_path],
        )?;
        Ok(())
    }

    fn get_media_path(&self, id: &str) -> Result<String> {
        self.conn.query_row("SELECT relative_path FROM media_items WHERE id = ?1", params![id], |row| row.get(0))
    }

    fn safe_album_id(id: &str) -> bool {
        !id.is_empty() && id != "." && id != ".." && !id.contains('/') && !id.contains('\\') && !id.contains('\0')
    }

    /// Update album associations and relative paths, returning the physical rename plan.
    pub fn move_media_to_album(&mut self, ids: &[String], destination_album: &str) -> Result<Vec<(PathBuf, PathBuf)>> {
        if !Self::safe_album_id(destination_album) {
            return Err(rusqlite::Error::InvalidParameterName("unsafe destination album id".into()));
        }
        let exists: bool = self.conn.query_row("SELECT EXISTS(SELECT 1 FROM albums WHERE id = ?1)", params![destination_album], |r| r.get(0))?;
        if !exists { return Err(rusqlite::Error::InvalidParameterName("destination album does not exist".into())); }
        let tx = self.conn.transaction()?;
        let mut moves = Vec::new();
        for id in ids {
            let current: Option<(String, String)> = tx.query_row(
                "SELECT album_id, relative_path FROM media_items WHERE id = ?1", params![id],
                |row| Ok((row.get(0)?, row.get(1)?)),
            ).optional()?;
            let Some((old_album, old_rel)) = current else { continue; };
            if old_album == destination_album { continue; }
            let filename = Path::new(&old_rel).file_name().and_then(|v| v.to_str()).ok_or_else(|| rusqlite::Error::InvalidParameterName("invalid media path".into()))?;
            let new_rel = format!("media/{}/{}", destination_album, filename);
            let old_path = self.vault_root.join(&old_rel);
            let new_path = self.vault_root.join(&new_rel);
            if new_path.exists() { return Err(rusqlite::Error::InvalidParameterName("destination file already exists".into())); }
            tx.execute("UPDATE media_items SET album_id = ?1, relative_path = ?2 WHERE id = ?3", params![destination_album, new_rel, id])?;
            moves.push((old_path, new_path));
        }
        tx.commit()?;
        Ok(moves)
    }

    /// Restore DB paths if a physical move fails. The caller should rename moved files back first.
    pub fn rollback_media_moves(&mut self, moves: &[(PathBuf, PathBuf)]) -> Result<()> {
        let tx = self.conn.transaction()?;
        for (old_path, new_path) in moves {
            let Ok(old_rel) = old_path.strip_prefix(&self.vault_root) else { continue; };
            let Ok(new_rel) = new_path.strip_prefix(&self.vault_root) else { continue; };
            let old_rel = old_rel.to_string_lossy().replace('\\', "/");
            let new_rel = new_rel.to_string_lossy().replace('\\', "/");
            let old_album = Path::new(&old_rel).components().nth(1).and_then(|v| v.as_os_str().to_str()).unwrap_or("default");
            tx.execute("UPDATE media_items SET album_id = ?1, relative_path = ?2 WHERE relative_path = ?3", params![old_album, old_rel, new_rel])?;
        }
        tx.commit()
    }

    /// Create the album directory and database row as one best-effort operation.
    pub fn create_album(&mut self, id: &str, name: &str) -> Result<()> {
        if !Self::safe_album_id(id) {
            return Err(rusqlite::Error::InvalidParameterName("unsafe album id".into()));
        }
        let album_dir = self.vault_root.join("media").join(id);
        std::fs::create_dir_all(&album_dir).map_err(|_| rusqlite::Error::InvalidParameterName("could not create album folder".into()))?;
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        match self.conn.execute("INSERT INTO albums (id, name, created_at) VALUES (?1, ?2, ?3)", params![id, name, now]) {
            Ok(_) => Ok(()),
            Err(err) => { let _ = std::fs::remove_dir(&album_dir); Err(err) }
        }
    }

    /// Delete an album and its physical folder. The built-in Default album is protected.
    pub fn delete_album(&mut self, id: &str) -> Result<()> {
        if id == "default" || !Self::safe_album_id(id) {
            return Err(rusqlite::Error::InvalidParameterName("cannot delete this album".into()));
        }
        let exists: bool = self.conn.query_row("SELECT EXISTS(SELECT 1 FROM albums WHERE id = ?1)", params![id], |r| r.get(0))?;
        if !exists { return Err(rusqlite::Error::InvalidParameterName("album does not exist".into())); }
        let album_dir = self.vault_root.join("media").join(id);
        if album_dir.exists() {
            std::fs::remove_dir_all(&album_dir).map_err(|_| rusqlite::Error::InvalidParameterName("could not remove album folder".into()))?;
        }
        let tx = self.conn.transaction()?;
        tx.execute("DELETE FROM albums WHERE id = ?1", params![id])?;
        tx.commit()
    }

    pub fn sync_album_folders(&self, album_ids: &std::collections::HashSet<String>) -> std::io::Result<()> {
        let media_root = self.vault_root.join("media");
        std::fs::create_dir_all(&media_root)?;
        for id in album_ids {
            if Self::safe_album_id(id) { std::fs::create_dir_all(media_root.join(id))?; }
        }
        Ok(())
    }

    /// Delete selected rows transactionally and return only paths no longer referenced by any row.
    pub fn delete_media_items(&mut self, ids: &[String]) -> Result<Vec<String>> {
        let tx = self.conn.transaction()?;
        let mut candidate_paths = std::collections::HashSet::new();
        for id in ids {
            let path: Option<String> = tx.query_row("SELECT relative_path FROM media_items WHERE id = ?1", params![id], |r| r.get(0)).optional()?;
            if let Some(path) = path { candidate_paths.insert(path); }
            tx.execute("DELETE FROM media_items WHERE id = ?1", params![id])?;
        }
        let mut unreferenced = Vec::new();
        for path in candidate_paths {
            let referenced: bool = tx.query_row("SELECT EXISTS(SELECT 1 FROM media_items WHERE relative_path = ?1)", params![path], |r| r.get(0))?;
            if !referenced { unreferenced.push(path); }
        }
        tx.commit()?;
        Ok(unreferenced)
    }

    pub fn get_albums(&self) -> Result<Vec<AlbumRecord>> {
        let mut stmt = self.conn.prepare("SELECT id, name, created_at FROM albums ORDER BY created_at ASC")?;
        let iter = stmt.query_map([], |row| {
            Ok(AlbumRecord {
                id: row.get(0)?,
                name: row.get(1)?,
                created_at: row.get(2)?,
            })
        })?;

        let mut res = Vec::new();
        for a in iter {
            res.push(a?);
        }
        Ok(res)
    }

    pub fn get_meta(&self, key: &str) -> Result<Option<String>> {
        let mut stmt = self.conn.prepare("SELECT val FROM meta WHERE key = ?1")?;
        let mut rows = stmt.query(params![key])?;
        if let Some(r) = rows.next()? {
            Ok(Some(r.get(0)?))
        } else {
            Ok(None)
        }
    }

    pub fn set_meta(&self, key: &str, val: &str) -> Result<()> {
        self.conn.execute(
            "INSERT OR REPLACE INTO meta (key, val) VALUES (?1, ?2)",
            params![key, val],
        )?;
        Ok(())
    }
}

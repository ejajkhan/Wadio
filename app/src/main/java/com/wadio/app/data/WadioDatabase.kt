package com.wadio.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import java.io.File

/**
 * Raw SQLite — mirrors src/db/mod.rs exactly:
 * - WAL journal, NORMAL sync, FK on, temp_store MEMORY.
 * - meta / albums / media_items with the same CHECK and FK cascade.
 * - Default album ('default', 'Default', 0) is seeded on create.
 * All file mutations (create_album / delete_album / move) use the
 * caller's file operations; DB and FS are not transactional across each
 * other — recovery handles the divergence window (see ImportCoordinator).
 */
class WadioDatabase private constructor(
    context: Context,
    private val dbFile: File,
    private val vaultRoot: File,
) : SQLiteOpenHelper(context, dbFile.absolutePath, null, 1) {

    init {
        // Let SQLiteOpenHelper enable WAL using Android's supported API.
        // Executing PRAGMA journal_mode through execSQL can throw because it returns a row.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous = NORMAL")
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA temp_store = MEMORY")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                val TEXT NOT NULL
            );
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS albums (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            );
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_items (
                id TEXT PRIMARY KEY,
                album_id TEXT NOT NULL,
                media_type TEXT CHECK(media_type IN ('Image','Video')) NOT NULL,
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
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_album_time ON media_items (album_id, imported_at DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_sha ON media_items (sha256);")
        db.execSQL("INSERT OR IGNORE INTO albums (id, name, created_at) VALUES ('default', 'Default', 0);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* v1 */ }

    // ─── meta ─────────────────────────────────────────────────────────────
    fun getMeta(key: String): String? = readableDatabase.rawQuery(
        "SELECT val FROM meta WHERE key = ?", arrayOf(key)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO meta (key, val) VALUES (?, ?)",
            arrayOf(key, value)
        )
    }

    // ─── albums ───────────────────────────────────────────────────────────
    fun getAlbums(): List<AlbumRecord> = readableDatabase.rawQuery(
        "SELECT id, name, created_at FROM albums ORDER BY created_at ASC", null
    ).use { c -> buildList { while (c.moveToNext()) add(AlbumRecord(c.getString(0), c.getString(1), c.getLong(2))) } }

    /**
     * Create album folder first, then DB row. On DB failure, remove the
     * folder — matching VaultDB::create_album in the Rust source.
     */
    @Synchronized
    fun createAlbum(id: String, name: String): Boolean {
        if (!isSafeId(id)) return false
        val albumDir = File(File(vaultRoot, "media"), id)
        if (!albumDir.exists() && !albumDir.mkdirs()) return false
        val now = System.currentTimeMillis() / 1000L
        return try {
            writableDatabase.execSQL(
                "INSERT INTO albums (id, name, created_at) VALUES (?, ?, ?)",
                arrayOf(id, name, now)
            )
            true
        } catch (t: Throwable) {
            albumDir.delete()
            false
        }
    }

    /**
     * Protect "default". Delete physical folder first, then cascade DB rows.
     * Matches VaultDB::delete_album.
     */
    @Synchronized
    fun deleteAlbum(id: String): Boolean {
        if (id == "default" || !isSafeId(id)) return false
        val exists = readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM albums WHERE id = ?)", arrayOf(id)
        ).use { it.moveToFirst() && it.getInt(0) == 1 }
        if (!exists) return false
        val albumDir = File(File(vaultRoot, "media"), id)
        if (albumDir.exists()) {
            albumDir.deleteRecursively() // deletes contents too; matches remove_dir_all
        }
        return try {
            writableDatabase.beginTransaction()
            writableDatabase.execSQL("DELETE FROM albums WHERE id = ?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
            writableDatabase.endTransaction()
            true
        } catch (t: Throwable) { false }
    }

    // ─── media ────────────────────────────────────────────────────────────
    enum class InsertResult { Ok, Duplicate, SqlError }

    @Synchronized
    fun insertMedia(rec: MediaRecord): InsertResult {
        // Duplicate check by content hash — matches src/db/mod.rs.
        val dup = readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM media_items WHERE sha256 = ?)", arrayOf(rec.sha256)
        ).use { it.moveToFirst() && it.getInt(0) == 1 }
        if (dup) return InsertResult.Duplicate
        return try {
            writableDatabase.beginTransaction()
            writableDatabase.execSQL(
                """INSERT INTO media_items
                   (id, album_id, media_type, file_name, byte_size, sha256,
                    width, height, duration_ms, imported_at, relative_path)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf(
                    rec.id, rec.albumId, rec.mediaType, rec.fileName, rec.byteSize, rec.sha256,
                    rec.width, rec.height, rec.durationMs, rec.importedAt, rec.relativePath,
                )
            )
            writableDatabase.setTransactionSuccessful()
            writableDatabase.endTransaction()
            InsertResult.Ok
        } catch (t: Throwable) {
            runCatching { writableDatabase.endTransaction() }
            InsertResult.SqlError
        }
    }

    @Synchronized
    fun isRecordCommitted(rec: MediaRecord): Boolean =
        readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM media_items WHERE id = ? AND relative_path = ? AND sha256 = ?)",
            arrayOf(rec.id, rec.relativePath, rec.sha256)
        ).use { it.moveToFirst() && it.getInt(0) == 1 }

    fun getMediaByAlbum(albumId: String, limit: Int = 10_000): List<MediaRecord> =
        readableDatabase.rawQuery(
            """SELECT id, album_id, media_type, file_name, byte_size, sha256,
                      width, height, duration_ms, imported_at, relative_path
               FROM media_items WHERE album_id = ? ORDER BY imported_at DESC LIMIT ?""",
            arrayOf(albumId, limit.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    fun getAlbumCover(albumId: String): MediaRecord? =
        readableDatabase.rawQuery(
            """SELECT id, album_id, media_type, file_name, byte_size, sha256,
                      width, height, duration_ms, imported_at, relative_path
               FROM media_items WHERE album_id = ? AND media_type = 'Image'
               ORDER BY imported_at DESC, id ASC LIMIT 1""",
            arrayOf(albumId)
        ).use { c -> if (c.moveToFirst()) read(c) else null }

    fun getAllRelativePaths(): Set<String> =
        readableDatabase.rawQuery("SELECT relative_path FROM media_items", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /**
     * Prepare a physically independent copy under `destinationAlbum` with a
     * fresh UUID filename. The caller copies the file then calls
     * `insertCopiedMedia`. Duplicates by hash are allowed for copies
     * (only normal imports reject them).
     */
    fun prepareMediaCopy(sourceId: String, destinationAlbum: String, newId: String): Triple<MediaRecord, File, File>? {
        if (!isSafeId(destinationAlbum) || !isSafeId(newId)) return null
        val src = readableDatabase.rawQuery(
            """SELECT id, album_id, media_type, file_name, byte_size, sha256,
                      width, height, duration_ms, imported_at, relative_path
               FROM media_items WHERE id = ?""",
            arrayOf(sourceId)
        ).use { c -> if (c.moveToFirst()) read(c) else return null }
        val ext = src.relativePath.substringAfterLast('.', "bin")
        val newRel = "media/$destinationAlbum/$newId.$ext"
        val old = File(vaultRoot, src.relativePath)
        val new = File(vaultRoot, newRel)
        val now = System.currentTimeMillis() / 1000L
        val copied = src.copy(id = newId, albumId = destinationAlbum, relativePath = newRel, importedAt = now)
        return Triple(copied, old, new)
    }

    /** Bypass duplicate check (copies deliberately share hashes). */
    @Synchronized
    fun insertCopiedMedia(rec: MediaRecord): Boolean = try {
        writableDatabase.execSQL(
            """INSERT INTO media_items
               (id, album_id, media_type, file_name, byte_size, sha256,
                width, height, duration_ms, imported_at, relative_path)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(rec.id, rec.albumId, rec.mediaType, rec.fileName, rec.byteSize, rec.sha256,
                rec.width, rec.height, rec.durationMs, rec.importedAt, rec.relativePath)
        )
        true
    } catch (t: Throwable) { false }

    /** Move rows: update album_id and relative_path in one transaction. */
    @Synchronized
    fun moveMediaToAlbum(ids: List<String>, destinationAlbum: String): List<Pair<File, File>>? {
        if (!isSafeId(destinationAlbum)) return null
        val exists = readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM albums WHERE id = ?)", arrayOf(destinationAlbum)
        ).use { it.moveToFirst() && it.getInt(0) == 1 }
        if (!exists) return null

        val moves = mutableListOf<Pair<File, File>>()
        try {
            writableDatabase.beginTransaction()
            for (id in ids) {
                val current = readableDatabase.rawQuery(
                    "SELECT album_id, relative_path FROM media_items WHERE id = ?", arrayOf(id)
                ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
                    ?: continue
                val (oldAlbum, oldRel) = current
                if (oldAlbum == destinationAlbum) continue
                val filename = oldRel.substringAfterLast('/')
                val newRel = "media/$destinationAlbum/$filename"
                val oldPath = File(vaultRoot, oldRel)
                val newPath = File(vaultRoot, newRel)
                if (newPath.exists()) {
                    writableDatabase.endTransaction()
                    return null
                }
                writableDatabase.execSQL(
                    "UPDATE media_items SET album_id = ?, relative_path = ? WHERE id = ?",
                    arrayOf(destinationAlbum, newRel, id)
                )
                moves.add(oldPath to newPath)
            }
            writableDatabase.setTransactionSuccessful()
            writableDatabase.endTransaction()
            return moves
        } catch (t: Throwable) {
            runCatching { writableDatabase.endTransaction() }
            return null
        }
    }

    @Synchronized
    fun rollbackMediaMoves(moves: List<Pair<File, File>>) {
        writableDatabase.beginTransaction()
        try {
            for ((oldPath, newPath) in moves) {
                val oldRel = oldPath.relativeTo(vaultRoot).path.replace('\\', '/')
                val newRel = newPath.relativeTo(vaultRoot).path.replace('\\', '/')
                val oldAlbum = oldRel.split('/').getOrNull(1) ?: "default"
                writableDatabase.execSQL(
                    "UPDATE media_items SET album_id = ?, relative_path = ? WHERE relative_path = ?",
                    arrayOf(oldAlbum, oldRel, newRel)
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    /** Delete rows transactionally; return paths no longer referenced. */
    @Synchronized
    fun deleteMediaItems(ids: List<String>): List<String>? = try {
        writableDatabase.beginTransaction()
        val candidates = mutableSetOf<String>()
        for (id in ids) {
            readableDatabase.rawQuery(
                "SELECT relative_path FROM media_items WHERE id = ?", arrayOf(id)
            ).use { c -> if (c.moveToFirst()) candidates.add(c.getString(0)) }
            writableDatabase.execSQL("DELETE FROM media_items WHERE id = ?", arrayOf(id))
        }
        val unreferenced = candidates.filter { rel ->
            !readableDatabase.rawQuery(
                "SELECT EXISTS(SELECT 1 FROM media_items WHERE relative_path = ?)", arrayOf(rel)
            ).use { it.moveToFirst() && it.getInt(0) == 1 }
        }
        writableDatabase.setTransactionSuccessful()
        writableDatabase.endTransaction()
        unreferenced
    } catch (t: Throwable) {
        runCatching { writableDatabase.endTransaction() }
        null
    }

    // ─── helpers ──────────────────────────────────────────────────────────
    private fun read(c: Cursor): MediaRecord = MediaRecord(
        id = c.getString(0), albumId = c.getString(1), mediaType = c.getString(2),
        fileName = c.getString(3), byteSize = c.getLong(4), sha256 = c.getString(5),
        width = c.getInt(6).takeIf { !c.isNull(6) },
        height = c.getInt(7).takeIf { !c.isNull(7) },
        durationMs = c.getLong(8).takeIf { !c.isNull(8) },
        importedAt = c.getLong(9), relativePath = c.getString(10),
    )

    private fun MediaRecord.copy(
        id: String = this.id,
        albumId: String = this.albumId,
        relativePath: String = this.relativePath,
        importedAt: Long = this.importedAt,
    ) = MediaRecord(id, albumId, mediaType, fileName, byteSize, sha256,
        width, height, durationMs, importedAt, relativePath)

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && !id.contains('/') &&
            !id.contains('\\') && !id.contains('\u0000')

    companion object {
        fun open(context: Context, dbFile: File, vaultRoot: File): WadioDatabase =
            WadioDatabase(context.applicationContext, dbFile, vaultRoot)
    }
}

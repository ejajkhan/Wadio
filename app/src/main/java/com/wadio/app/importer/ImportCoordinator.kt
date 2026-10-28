package com.wadio.app.importer

import android.graphics.BitmapFactory
import com.wadio.app.util.ThumbnailStore
import android.util.Log
import com.wadio.app.data.MediaRecord
import com.wadio.app.data.WadioDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ImportSession(
    val id: String,
    val fileName: String,
    val albumId: String,
    val stagingPath: File,
    val expectedSize: Long,
    val digest: MessageDigest = MessageDigest.getInstance("SHA-256"),
) {
    var written: Long = 0
}

/**
 * Direct port of src/importer.rs.
 *
 * Disk layout (private internal storage):
 *   vault/
 *     media/photos/<album_id>/<uuid>.<ext>   committed photos
 *     media/videos/<album_id>/<uuid>.<ext>   committed videos
 *     .staging/<uuid>.tmp               in-flight imports
 *     .pending/<uuid>.json              durable pre-commit markers
 *     .orphaned/…                       recovery bucket (never deleted)
 *     .thumbnails/<id>.jpg              static grid thumbnails
 */
class ImportCoordinator(
    val baseDir: File,
    val stagingDir: File,
    val mediaDir: File,
    val pendingDir: File,
) {
    private val thumbDir = File(baseDir, ".thumbnails").apply { mkdirs() }
    private val orphanDir = File(baseDir, ".orphaned").apply { mkdirs() }

    companion object {
        private const val TAG = "WadioImport"
        private const val MAX_SOURCE_SIDE = 30_000
        private const val MAX_SOURCE_PIXELS = 50_000_000L
        private val IMAGE_EXTS = setOf(
            "jpg","jpeg","jpe","jfif","png","webp","gif","bmp","dds","exr","ff","hdr",
            "ico","pnm","pbm","pgm","ppm","pam","qoi","tga","tif","tiff","avif"
        )
        private val VIDEO_EXTS = setOf("mp4","m4v","mov","webm","mkv","3gp")
        private const val MAX_STAGING_AGE_MS = 24L * 60 * 60 * 1000
        private const val MAX_PENDING_TEMP_AGE_MS = 24L * 60 * 60 * 1000
        private const val ORPHAN_TTL_MS = 24L * 60 * 60 * 1000
        private const val ORPHAN_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    // ─── startup recovery ─────────────────────────────────────────────────
    fun recoverInterruptedImports() {
        stagingDir.mkdirs()
        pendingDir.mkdirs()
        recoverPendingMarkerTemps()

        // Protect staging files referenced by still-valid pending markers.
        val pendingIds = readPendingRecords().map { it.second.id }.toMutableSet()
        pendingDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".json.tmp")) {
                PendingRecordCodec.decode(f.readText())?.let { pendingIds.add(it.id) }
            }
        }

        val now = System.currentTimeMillis()
        stagingDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".tmp") || !f.isFile) return@forEach
            val stem = f.nameWithoutExtension
            if (pendingIds.contains(stem)) return@forEach
            val stale = (now - f.lastModified()) >= MAX_STAGING_AGE_MS
            if (stale) runCatching { f.delete() }
        }

        // Remove stale thumbnail temp files.
        thumbDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") && f.isFile) {
                if ((now - f.lastModified()) >= MAX_STAGING_AGE_MS) runCatching { f.delete() }
            }
        }
    }

    private fun recoverPendingMarkerTemps() {
        pendingDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".json.tmp") || !f.isFile) return@forEach
            val rec = PendingRecordCodec.decode(f.readText())
            if (rec != null &&
                isSafeRecordId(rec.id) &&
                isSafeRelativeMediaPath(rec.relativePath) &&
                f.name == "${rec.id}.json.tmp"
            ) {
                val finalFile = File(pendingDir, "${rec.id}.json")
                if (!finalFile.exists()) f.renameTo(finalFile) else f.delete()
                return@forEach
            }
            val stale = (System.currentTimeMillis() - f.lastModified()) >= MAX_PENDING_TEMP_AGE_MS
            if (stale) runCatching { f.delete() }
        }
    }

    fun readPendingRecords(): List<Pair<File, MediaRecord>> = buildList {
        pendingDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".json") || !f.isFile) return@forEach
            val rec = PendingRecordCodec.decode(runCatching { f.readText() }.getOrNull() ?: return@forEach)
                ?: return@forEach
            if (isSafeRecordId(rec.id) &&
                isSafeRelativeMediaPath(rec.relativePath) &&
                f.nameWithoutExtension == rec.id
            ) add(f to rec)
        }
    }

    fun logPendingSummary() {
        val pending = readPendingRecords()
        val bytes = pending.sumOf { it.first.length() }
        if (pending.isNotEmpty()) {
            Log.i(TAG, "${pending.size} pending recovery record(s), $bytes marker bytes")
        }
    }

    fun recoverPendingMedia(db: WadioDatabase) {
        for ((marker, originalRecord) in readPendingRecords()) {
            var rec = originalRecord
            // Recover pending imports written by v1 before the album split migration.
            val parts = rec.relativePath.split('/')
            if (parts.size == 3 && parts[0] == "media") {
                val category = if (rec.mediaType == "Video") "videos" else "photos"
                val album = if (rec.mediaType == "Video") {
                    if (rec.albumId == "default") "default-videos" else "${rec.albumId}-videos"
                } else rec.albumId
                val migratedRel = "media/$category/$album/${parts[2]}"
                val oldFile = File(baseDir, rec.relativePath)
                val newFile = File(baseDir, migratedRel)
                if (oldFile.isFile && !newFile.exists()) {
                    newFile.parentFile?.mkdirs()
                    oldFile.copyTo(newFile, overwrite = false)
                }
                rec = rec.copy(albumId = album, relativePath = migratedRel)
                writePendingRecord(rec)
            }
            val mediaPath = File(baseDir, rec.relativePath)
            if (!mediaPath.isFile) {
                val stagingPath = File(stagingDir, "${rec.id}.tmp")
                if (stagingPath.isFile) {
                    mediaPath.parentFile?.mkdirs()
                    if (!stagingPath.renameTo(mediaPath)) continue
                } else continue
            }
            when (db.insertMedia(rec)) {
                WadioDatabase.InsertResult.Ok -> marker.delete()
                WadioDatabase.InsertResult.Duplicate -> {
                    if (db.isRecordCommitted(rec)) marker.delete()
                    else { mediaPath.delete(); File(thumbDir, "${rec.id}.jpg").delete(); marker.delete() }
                }
                WadioDatabase.InsertResult.SqlError ->
                    Log.e(TAG, "pending media DB recovery failed for ${rec.relativePath}")
            }
        }
    }

    fun recoverOrphanedMedia(referencedPaths: Set<String>) {
        cleanupExpiredOrphans(ORPHAN_RETENTION_MS)
        if (referencedPaths.isEmpty()) return  // fresh install or partial restore
        val pendingPaths = readPendingRecords().map { it.second.relativePath }.toSet()
        val now = System.currentTimeMillis()
        fun scan(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) { scan(file); return@forEach }
                val rel = file.relativeTo(baseDir).path.replace('\\', '/')
                if (rel in referencedPaths || rel in pendingPaths) return@forEach
                if ((now - file.lastModified()) < ORPHAN_TTL_MS) return@forEach
                val dest = File(orphanDir, rel).uniqueChild()
                dest.parentFile?.mkdirs()
                file.renameTo(dest)
            }
        }
        scan(mediaDir)
    }

    private fun cleanupExpiredOrphans(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        fun visit(dir: File) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) { visit(f); runCatching { f.delete() } }
                else if ((now - f.lastModified()) >= maxAgeMs) runCatching { f.delete() }
            }
        }
        if (orphanDir.isDirectory) visit(orphanDir)
    }

    /**
     * Reconcile one physical folder per DB album.
     * If the DB reports only the default album (fresh install or partial restore),
     * preserve all folders rather than risk guessing they're stale.
     */
    fun syncAlbumFolders(albumIds: Set<String>) {
        mediaDir.mkdirs()
        File(mediaDir, "photos").mkdirs()
        File(mediaDir, "videos").mkdirs()
        albumIds.forEach { id ->
            if (isSafeRecordId(id)) {
                File(File(mediaDir, "photos"), id).mkdirs()
                File(File(mediaDir, "videos"), id).mkdirs()
            }
        }
    }

    // ─── streaming import ─────────────────────────────────────────────────
    fun startStream(declaredSize: Long, originalName: String, albumId: String): ImportSession? {
        if (!isSafeRecordId(albumId)) return null
        val free = baseDir.usableSpace
        val required = (declaredSize * 1.15).toLong() + 15L * 1024 * 1024
        if (free < required) return null

        val id = UUID.randomUUID().toString()
        val staging = File(stagingDir, "$id.tmp").apply { parentFile?.mkdirs() }
        if (!staging.exists() && !staging.createNewFile()) return null

        return ImportSession(id, originalName, albumId, staging, declaredSize)
    }

    fun writeChunk(session: ImportSession, chunk: ByteArray, size: Int): Boolean {
        if (size <= 0 || size > chunk.size) return false
        val next = session.written + size
        if (session.expectedSize > 0 && next > session.expectedSize) return false
        return try {
            session.stagingPath.appendBytes(chunk.copyOf(size))
            session.digest.update(chunk, 0, size)
            session.written = next
            true
        } catch (_: Throwable) { false }
    }

    fun abortStream(session: ImportSession) {
        runCatching { session.stagingPath.delete() }
    }

    /**
     * Finish import. Steps mirror ImportCoordinator::finish_stream:
     *   1. sync + hash.
     *   2. extension and dimension checks.
     *   3. write pre-commit marker (durable), then atomically rename staging→media.
     *   4. generate a static thumbnail (best effort).
     */
    fun finishStream(session: ImportSession, albumId: String): MediaRecord? {
        if (session.written == 0L) return null
        if (session.expectedSize > 0 && session.written != session.expectedSize) return null

        val hash = session.digest.digest().joinToString("") { "%02x".format(it) }
        val ext = session.fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext.length > 10 || !ext.all { it.isLetterOrDigit() }) return null

        val mediaType = when (ext) {
            in IMAGE_EXTS -> "Image"
            in VIDEO_EXTS -> "Video"
            else -> return null
        }

        var width: Int? = null
        var height: Int? = null
        if (mediaType == "Image") {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(session.stagingPath.absolutePath, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0 || w > MAX_SOURCE_SIDE || h > MAX_SOURCE_SIDE ||
                (w.toLong() * h.toLong()) > MAX_SOURCE_PIXELS
            ) return null
            width = w; height = h
        }

        val category = if (mediaType == "Video") "videos" else "photos"
        val albumDir = File(File(mediaDir, category), albumId).apply { mkdirs() }
        val finalName = "${session.id}.$ext"
        val finalFile = File(albumDir, finalName)
        if (finalFile.exists()) return null

        val nowUnix = System.currentTimeMillis() / 1000L
        val record = MediaRecord(
            id = session.id,
            albumId = albumId,
            mediaType = mediaType,
            fileName = session.fileName,
            byteSize = session.written,
            sha256 = hash,
            width = width, height = height, durationMs = null,
            importedAt = nowUnix,
            relativePath = "media/$category/$albumId/$finalName",
        )

        // Durable pre-commit marker before the rename — recovery relies on this.
        runCatching { writePendingRecord(record) }

        if (!session.stagingPath.renameTo(finalFile)) {
            // Keep marker if it exists so startup recovery can retry the rename.
            return null
        }

        // Best-effort thumbnail for grid + album covers.
        // Thumbnail creation is best-effort; the UI can create a missing one lazily.
        try { ThumbnailStore.getOrCreate(baseDir, finalFile, record.id, mediaType) } catch (_: Throwable) {}

        return record
    }

    fun writePendingRecord(record: MediaRecord) {
        pendingDir.mkdirs()
        if (!isSafeRecordId(record.id) || !isSafeRelativeMediaPath(record.relativePath)) return
        val finalFile = File(pendingDir, "${record.id}.json")
        val tempFile = File(pendingDir, "${record.id}.json.tmp")
        tempFile.writeText(PendingRecordCodec.encode(record))
        if (finalFile.exists()) finalFile.delete()
        tempFile.renameTo(finalFile)
    }

    fun removePendingRecord(id: String) {
        File(pendingDir, "$id.json").delete()
    }

    // ─── helpers ──────────────────────────────────────────────────────────
    private fun File.uniqueChild(): File {
        if (!exists()) return this
        val stem = nameWithoutExtension
        val ext = extension
        var i = 1L
        while (true) {
            val candidate = File(parentFile, if (ext.isEmpty()) "$stem-$i" else "$stem-$i.$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    fun isSafeRecordId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && !id.contains('/') &&
            !id.contains('\\') && !id.contains('\u0000')

    fun isSafeRelativeMediaPath(path: String): Boolean {
        val comps = path.split('/')
        if (comps.size < 3 || comps[0] != "media") return false
        return comps.all {
            it.isNotEmpty() && it != "." && it != ".." &&
                !it.contains('\u0000') && !it.contains('\\')
        }
    }
}

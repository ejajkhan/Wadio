package com.wadio.app.importer

import com.wadio.app.data.MediaRecord
import org.json.JSONObject

/** Durable pre-commit marker under .pending/{id}.json — matches src/importer.rs. */
object PendingRecordCodec {

    fun encode(rec: MediaRecord): String = JSONObject().apply {
        put("id", rec.id)
        put("album_id", rec.albumId)
        put("media_type", rec.mediaType)
        put("file_name", rec.fileName)
        put("byte_size", rec.byteSize)
        put("sha256", rec.sha256)
        rec.width?.let { put("width", it) }
        rec.height?.let { put("height", it) }
        rec.durationMs?.let { put("duration_ms", it) }
        put("imported_at", rec.importedAt)
        put("relative_path", rec.relativePath)
    }.toString()

    fun decode(json: String): MediaRecord? = try {
        val o = JSONObject(json)
        MediaRecord(
            id = o.getString("id"),
            albumId = o.getString("album_id"),
            mediaType = o.getString("media_type"),
            fileName = o.getString("file_name"),
            byteSize = o.getLong("byte_size"),
            sha256 = o.getString("sha256"),
            width = o.optInt("width", -1).takeIf { it >= 0 },
            height = o.optInt("height", -1).takeIf { it >= 0 },
            durationMs = o.optLong("duration_ms", -1L).takeIf { it >= 0 },
            importedAt = o.getLong("imported_at"),
            relativePath = o.getString("relative_path"),
        )
    } catch (_: Throwable) { null }
}

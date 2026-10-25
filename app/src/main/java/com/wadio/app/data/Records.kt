package com.wadio.app.data

data class MediaRecord(
    val id: String,
    val albumId: String,
    val mediaType: String,   // "Image" | "Video"
    val fileName: String,
    val byteSize: Long,
    val sha256: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val importedAt: Long,
    val relativePath: String,
)

data class AlbumRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
)

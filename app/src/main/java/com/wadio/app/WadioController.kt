package com.wadio.app

import android.app.Application
import android.util.Log
import com.wadio.app.audio.AudioVolumeController
import com.wadio.app.audio.StreamState
import com.wadio.app.auth.AuthEngine
import com.wadio.app.data.AlbumRecord
import com.wadio.app.data.MediaRecord
import com.wadio.app.data.WadioDatabase
import com.wadio.app.importer.ImportCoordinator
import com.wadio.app.importer.ImportSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ActiveTab { Audio, Pictures, Videos }
enum class MediaSortMode { Name, Size, DateCreated }

data class ImportSummary(
    val epoch: Long = 0, val added: Int = 0, val duplicates: Int = 0,
    val failed: Int = 0, val pending: Int = 0,
)

data class WadioUiState(
    val tab: ActiveTab = ActiveTab.Audio,
    val unlocked: Boolean = false,
    val hasPin: Boolean = false,
    val showPinPrompt: Boolean = false,
    val pinError: String? = null,
    val authPending: Boolean = false,
    val pinLockedUntilUnix: Long = 0L,

    val passInput: String = "",
    val confirmPassInput: String = "",

    val audioStates: List<StreamState> = emptyList(),

    val cachedMedia: List<MediaRecord> = emptyList(),
    val cachedAlbums: List<AlbumRecord> = emptyList(),
    val albumCovers: Map<String, MediaRecord> = emptyMap(),
    val selectedAlbum: String = "default",

    val selectionMode: Boolean = false,
    val selectedMediaIds: Set<String> = emptySet(),
    val selectionDestination: String = "",
    val confirmDeleteSelected: Boolean = false,

    val sortMode: MediaSortMode = MediaSortMode.DateCreated,
    val sortAscending: Boolean = false,

    val showNewAlbumModal: Boolean = false,
    val newAlbumInput: String = "",
    val albumError: String? = null,

    val importNotice: String? = null,
    val importNoticeEpoch: Long = 0,

    val viewerMediaId: String? = null,
)

class WadioController(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val baseDir = File(app.filesDir, "vault")
    private val stagingDir = File(baseDir, ".staging")
    private val mediaDir = File(baseDir, "media")
    private val pendingDir = File(baseDir, ".pending")

    val startupLogger = StartupLogger(File(app.filesDir, "wadio/logreport.txt"))

    lateinit var db: WadioDatabase; private set
    lateinit var importer: ImportCoordinator; private set
    lateinit var auth: AuthEngine; private set
    lateinit var audio: AudioVolumeController; private set

    @Volatile var biometricPromptActive: Boolean = false

    private val _state = MutableStateFlow(WadioUiState())
    val state: StateFlow<WadioUiState> = _state.asStateFlow()

    private val _biometricRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val biometricRequest: SharedFlow<Unit> = _biometricRequest.asSharedFlow()

    private val _lockRequest = AtomicBoolean(false)
    private val _idleResetRequest = AtomicBoolean(false)
    private val _biometricUnlockRequest = AtomicBoolean(false)

    private var lastActivityNanos = 0L

    private val importHandles = ConcurrentHashMap<Long, ImportSession>()
    private val nextHandle = AtomicLong(1)
    private val summary = MutableStateFlow(ImportSummary())
    private var lastSeenSummaryEpoch = 0L

    fun initialize() {
        startupLogger.log("=== Wadio Kotlin startup ===")
        installUncaughtHandler()
        try {
            mediaDir.mkdirs(); stagingDir.mkdirs(); pendingDir.mkdirs()

            db = WadioDatabase.open(app, File(baseDir, "wadio_vault.db"), baseDir)
            startupLogger.log("SQLite initialized")

            importer = ImportCoordinator(baseDir, stagingDir, mediaDir, pendingDir)
            importer.recoverInterruptedImports()
            startupLogger.log("Import coordinator recovered")

            val albumIds = db.getAlbums().map { it.id }.toHashSet()
            importer.syncAlbumFolders(albumIds)
            importer.recoverPendingMedia(db)
            importer.logPendingSummary()
            importer.recoverOrphanedMedia(db.getAllRelativePaths())
            startupLogger.log("Vault recovery complete")

            auth = AuthEngine(db)
            audio = AudioVolumeController(app)

            _state.update {
                it.copy(
                    cachedAlbums = db.getAlbums(),
                    hasPin = auth.hasPin,
                    audioStates = audio.getStates(),
                    pinLockedUntilUnix = auth.lockUntilUnix,
                )
            }
        } catch (t: Throwable) {
            startupLogger.log("FATAL: ${t.message}", t)
            throw t
        }
    }

    private fun installUncaughtHandler() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            startupLogger.log("PANIC: ${e.message}", e)
            default?.uncaughtException(t, e)
        }
    }

    // ─── Activity notifications ───────────────────────────────────────────
    fun onActivityResumed() {
        _idleResetRequest.set(true)
    }
    fun requestLock() { _lockRequest.set(true) }
    fun notifyBiometricSuccess() { _biometricUnlockRequest.set(true) }

    fun tick() {
        // Consume one-shot requests.
        if (_idleResetRequest.compareAndSet(true, false)) {
            lastActivityNanos = System.nanoTime()
        }
        if (_lockRequest.compareAndSet(true, false)) {
            lockVault()
        }
        if (_biometricUnlockRequest.compareAndSet(true, false)) {
            if (auth.unlockWithBiometric()) {
                lastActivityNanos = System.nanoTime()
                _state.update { it.copy(unlocked = true, showPinPrompt = false, pinError = null) }
                refreshVault()
            } else {
                _state.update { it.copy(pinError = "Set up a PIN before using biometric unlock") }
            }
        }

        val viewingPhoto = _state.value.tab == ActiveTab.Pictures && _state.value.viewerMediaId != null
        if (lastActivityNanos == 0L || viewingPhoto) lastActivityNanos = System.nanoTime()
        val idleSecs = (System.nanoTime() - lastActivityNanos) / 1_000_000_000.0
        if (auth.isUnlocked && !viewingPhoto && idleSecs >= 60.0) lockVault()

        if (_state.value.tab == ActiveTab.Audio) refreshAudio()
    }

    fun noteUserActivity() { lastActivityNanos = System.nanoTime() }

    fun lockVault() {
        scope.launch {
            _state.update {
                it.copy(
                    tab = ActiveTab.Audio, unlocked = false,
                    showPinPrompt = false, pinError = null, passInput = "", confirmPassInput = "",
                    viewerMediaId = null, selectionMode = false, selectedMediaIds = emptySet(),
                    confirmDeleteSelected = false, selectionDestination = "",
                    cachedMedia = emptyList(), albumCovers = emptyMap(),
                    selectedAlbum = "default", newAlbumInput = "",
                    showNewAlbumModal = false, albumError = null,
                )
            }
            auth.lock()
        }
    }

    fun onAuthPromptVisible(visible: Boolean) {
        _state.update { it.copy(showPinPrompt = visible) }
    }

    fun updateStealthTouch(isPressed: Boolean, now: Double) {
        auth.updateHeaderTouch(isPressed, now)
        if (auth.showPasswordModal) {
            _state.update { it.copy(showPinPrompt = true) }
        }
    }

    fun dismissAuthPrompt() {
        auth.dismissPrompt()
        _state.update { it.copy(showPinPrompt = false, pinError = null, passInput = "", confirmPassInput = "") }
    }

    // ─── audio ────────────────────────────────────────────────────────────
    fun refreshAudio() {
        _state.update { it.copy(audioStates = audio.getStates()) }
    }

    fun setVolume(streamType: Int, value: Int) {
        audio.setVolume(streamType, value)
        refreshAudio()
    }

    // ─── PIN entry ────────────────────────────────────────────────────────
    fun updatePinInput(input: String) {
        val filtered = input.filter { it.isDigit() }.take(4)
        _state.update { it.copy(passInput = filtered) }
    }
    fun updateConfirmInput(input: String) {
        val filtered = input.filter { it.isDigit() }.take(4)
        _state.update { it.copy(confirmPassInput = filtered) }
    }

    fun submitPin() {
        val s = _state.value
        if (s.authPending) return
        val nowUnix = System.currentTimeMillis() / 1000L
        if (!auth.hasPin) {
            // Setup path.
            if (s.passInput.length != 4) { _state.update { it.copy(pinError = "PIN must contain exactly 4 digits") }; return }
            if (s.confirmPassInput.length != 4) { _state.update { it.copy(pinError = "Enter the same 4-digit PIN in confirmation") }; return }
            if (s.passInput != s.confirmPassInput) {
                _state.update { it.copy(pinError = "PINs do not match", confirmPassInput = "") }; return
            }
            _state.update { it.copy(authPending = true, pinError = null) }
            scope.launch {
                val hash = withContext(Dispatchers.Default) { AuthEngine.hashPin(s.passInput) }
                db.setMeta("vault_pw", hash)
                withContext(Dispatchers.Main) {
                    auth.markUnlockedByHash(hash)
                    _state.update {
                        it.copy(
                            unlocked = true, hasPin = true, showPinPrompt = false,
                            authPending = false, pinError = null, passInput = "", confirmPassInput = "",
                        )
                    }
                    refreshVault()
                }
            }
            return
        }
        if (nowUnix < auth.lockUntilUnix) {
            val remaining = auth.lockUntilUnix - nowUnix
            _state.update { it.copy(pinError = "PIN temporarily locked for $remaining more second(s)") }
            return
        }
        if (s.passInput.length != 4) { _state.update { it.copy(pinError = "PIN must contain exactly 4 digits") }; return }
        _state.update { it.copy(authPending = true, pinError = null) }
        scope.launch {
            val ok = withContext(Dispatchers.Default) { auth.verify(s.passInput.toByteArray()) }
            withContext(Dispatchers.Main) {
                if (ok) {
                    _state.update {
                        it.copy(unlocked = true, showPinPrompt = false, authPending = false,
                            pinError = null, passInput = "", pinLockedUntilUnix = 0L)
                    }
                    refreshVault()
                } else {
                    auth.recordFailedAttempt()
                    _state.update {
                        it.copy(authPending = false, pinError = "Incorrect PIN. Try again later.",
                            passInput = "", pinLockedUntilUnix = auth.lockUntilUnix)
                    }
                }
            }
        }
    }

    fun requestBiometric() {
        scope.launch { _biometricRequest.emit(Unit) }
    }

    // ─── navigation ───────────────────────────────────────────────────────
    fun setTab(tab: ActiveTab) {
        if (tab != ActiveTab.Audio && !auth.isUnlocked) return
        _state.update { it.copy(tab = tab) }
        scope.launch {
            if (tab != ActiveTab.Audio) refreshVault()
        }
        noteUserActivity()
    }

    fun selectAlbum(id: String) {
        _state.update {
            it.copy(selectedAlbum = id, selectionMode = false, selectedMediaIds = emptySet())
        }
        refreshVault()
        noteUserActivity()
    }

    fun openNewAlbumModal() = _state.update { it.copy(showNewAlbumModal = true, albumError = null, newAlbumInput = "") }
    fun dismissNewAlbumModal() = _state.update { it.copy(showNewAlbumModal = false, albumError = null) }
    fun updateNewAlbumInput(t: String) = _state.update { it.copy(newAlbumInput = t) }

    fun createNewAlbum() {
        val name = _state.value.newAlbumInput.trim()
        if (name.isEmpty()) { _state.update { it.copy(albumError = "Enter an album name") }; return }
        val id = java.util.UUID.randomUUID().toString()
        if (db.createAlbum(id, name)) {
            _state.update {
                it.copy(selectedAlbum = id, showNewAlbumModal = false,
                    newAlbumInput = "", albumError = null, cachedAlbums = db.getAlbums())
            }
            refreshVault()
        } else {
            _state.update { it.copy(albumError = "Album could not be created") }
        }
    }

    fun deleteAlbum(id: String) {
        if (id == "default") return
        scope.launch(Dispatchers.IO) {
            if (db.deleteAlbum(id)) {
                _state.update {
                    it.copy(
                        selectedAlbum = if (it.selectedAlbum == id) "default" else it.selectedAlbum,
                        cachedAlbums = db.getAlbums(),
                    )
                }
                refreshVault()
            }
        }
    }

    // ─── gallery ──────────────────────────────────────────────────────────
    fun refreshVault() = scope.launch(Dispatchers.IO) {
        val albums = db.getAlbums()
        val selected = _state.value.selectedAlbum.ifEmpty { "default" }
        val effectiveAlbum = if (albums.any { it.id == selected }) selected
            else albums.firstOrNull()?.id ?: "default"
        val records = db.getMediaByAlbum(effectiveAlbum)
        val covers = albums.associate { it.id to db.getAlbumCover(it.id) }
            .filterValues { it != null }.mapValues { it.value!! }

        withContext(Dispatchers.Main) {
            val tab = _state.value.tab
            val filtered = if (tab == ActiveTab.Pictures)
                records.filter { it.mediaType == "Image" }
            else if (tab == ActiveTab.Videos)
                records.filter { it.mediaType == "Video" }
            else records
            _state.update {
                it.copy(
                    cachedAlbums = albums, albumCovers = covers,
                    selectedAlbum = effectiveAlbum, cachedMedia = sortMedia(filtered, it.sortMode, it.sortAscending),
                )
            }
        }
    }

    fun setSortMode(mode: MediaSortMode) {
        _state.update { it.copy(sortMode = mode, cachedMedia = sortMedia(it.cachedMedia, mode, it.sortAscending)) }
        noteUserActivity()
    }
    fun toggleSortDirection() {
        _state.update { it.copy(sortAscending = !it.sortAscending,
            cachedMedia = sortMedia(it.cachedMedia, it.sortMode, !it.sortAscending)) }
        noteUserActivity()
    }

    private fun sortMedia(items: List<MediaRecord>, mode: MediaSortMode, asc: Boolean): List<MediaRecord> {
        val sorted = when (mode) {
            MediaSortMode.Name -> items.sortedBy { it.fileName.lowercase() }
            MediaSortMode.Size -> items.sortedBy { it.byteSize }
            MediaSortMode.DateCreated -> items.sortedBy { it.importedAt }
        }
        return if (asc) sorted else sorted.reversed()
    }

    fun onLongPressSelect(id: String) {
        _state.update {
            it.copy(selectionMode = true, selectedMediaIds = it.selectedMediaIds + id)
        }
        noteUserActivity()
    }

    fun onMediaTap(record: MediaRecord) {
        val s = _state.value
        if (s.selectionMode) {
            val ids = s.selectedMediaIds.toMutableSet()
            if (!ids.remove(record.id)) ids.add(record.id)
            _state.update {
                it.copy(selectedMediaIds = ids, selectionMode = ids.isNotEmpty())
            }
        } else if (record.mediaType == "Video") {
            com.wadio.app.MainActivity.launchVideo(app, record.relativePath)
        } else {
            _state.update { it.copy(viewerMediaId = record.id) }
            noteUserActivity()
        }
    }

    fun closeViewer() = _state.update { it.copy(viewerMediaId = null) }
    fun viewerPrevNext(direction: Int) {
        val s = _state.value
        val index = s.cachedMedia.indexOfFirst { it.id == s.viewerMediaId }
        if (index < 0) return
        val next = (index + direction).coerceIn(0, s.cachedMedia.size - 1)
        _state.update { it.copy(viewerMediaId = s.cachedMedia[next].id) }
        noteUserActivity()
    }

    fun requestDeleteSelected() = _state.update { it.copy(confirmDeleteSelected = true) }
    fun cancelDeleteSelected() = _state.update { it.copy(confirmDeleteSelected = false) }

    fun updateSelectionDestination(id: String) {
        _state.update { it.copy(selectionDestination = id) }
    }

    fun performCopyTo() = scope.launch(Dispatchers.IO) {
        val s = _state.value
        val dest = s.selectionDestination
        if (dest.isEmpty() || dest == s.selectedAlbum) return@launch
        var errors = 0
        for (id in s.selectedMediaIds) {
            val triple = db.prepareMediaCopy(id, dest, java.util.UUID.randomUUID().toString()) ?: run { errors++; continue }
            val (rec, old, new) = triple
            new.parentFile?.mkdirs()
            val ok = runCatching { old.copyTo(new, overwrite = false); true }.getOrDefault(false)
            if (!ok) { errors++; continue }
            if (!db.insertCopiedMedia(rec)) { new.delete(); errors++; continue }
        }
        withContext(Dispatchers.Main) {
            _state.update { it.copy(
                selectionMode = false, selectedMediaIds = emptySet(), selectionDestination = "",
                importNotice = if (errors == 0) "Selected items copied to album" else "Some items could not be copied ($errors)",
                importNoticeEpoch = System.currentTimeMillis(),
            ) }
            refreshVault()
        }
    }

    fun performMoveTo() = scope.launch(Dispatchers.IO) {
        val s = _state.value
        val dest = s.selectionDestination
        if (dest.isEmpty() || dest == s.selectedAlbum) return@launch
        val moves = db.moveMediaToAlbum(s.selectedMediaIds.toList(), dest)
        if (moves == null) {
            withContext(Dispatchers.Main) {
                _state.update { it.copy(albumError = "Some items could not be moved") }
            }
            return@launch
        }
        var failed = false
        val done = mutableListOf<Pair<File, File>>()
        for ((old, new) in moves) {
            new.parentFile?.mkdirs()
            if (!old.renameTo(new)) { failed = true; break }
            done.add(old to new)
        }
        if (failed) {
            for ((old, new) in done.reversed()) new.renameTo(old)
            db.rollbackMediaMoves(moves)
        }
        withContext(Dispatchers.Main) {
            if (!failed) {
                _state.update { it.copy(
                    selectedAlbum = dest, selectionMode = false,
                    selectedMediaIds = emptySet(), selectionDestination = "",
                ) }
            } else {
                _state.update { it.copy(albumError = "Some items could not be moved; database rolled back") }
            }
            refreshVault()
        }
    }

    fun performDeleteSelected() = scope.launch(Dispatchers.IO) {
        val s = _state.value
        val ids = s.selectedMediaIds.toList()
        val unreferenced = db.deleteMediaItems(ids)
        if (unreferenced == null) {
            withContext(Dispatchers.Main) {
                _state.update { it.copy(albumError = "Delete failed; media records were not fully removed",
                    confirmDeleteSelected = false) }
            }
            return@launch
        }
        for (rel in unreferenced) {
            if (importer.isSafeRelativeMediaPath(rel)) File(baseDir, rel).delete()
        }
        withContext(Dispatchers.Main) {
            _state.update { it.copy(
                selectedMediaIds = emptySet(), selectionMode = false,
                confirmDeleteSelected = false, importNotice = "Selected items deleted",
                importNoticeEpoch = System.currentTimeMillis(),
            ) }
            refreshVault()
        }
    }

    fun consumeImportNotice() = _state.update { it.copy(importNotice = null) }

    // ─── import ───────────────────────────────────────────────────────────
    fun startImportFromUris(uris: List<android.net.Uri>) = scope.launch(Dispatchers.IO) {
        val albumId = _state.value.selectedAlbum
        val resolver = app.contentResolver
        var added = 0; var dup = 0; var failed = 0; var pending = 0
        for (uri in uris) {
            val (name, size) = queryDisplayNameAndSize(uri) ?: (null to 0L)
            if (name == null) { failed++; continue }
            val session = importer.startStream(size, name, albumId) ?: run { failed++; continue }
            try {
                val stream = resolver.openInputStream(uri) ?: run { failed++; continue }
                stream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (!importer.writeChunk(session, buf, n)) throw java.io.IOException("write failed")
                    }
                }
                val rec = importer.finishStream(session, albumId)
                if (rec == null) { failed++; importer.abortStream(session); continue }
                when (db.insertMedia(rec)) {
                    WadioDatabase.InsertResult.Ok -> { added++; importer.removePendingRecord(rec.id) }
                    WadioDatabase.InsertResult.Duplicate -> {
                        if (db.isRecordCommitted(rec)) { added++; importer.removePendingRecord(rec.id) }
                        else {
                            File(baseDir, rec.relativePath).delete()
                            importer.removePendingRecord(rec.id)
                            dup++
                        }
                    }
                    WadioDatabase.InsertResult.SqlError -> {
                        // Retry once, then leave marker for startup recovery.
                        when (db.insertMedia(rec)) {
                            WadioDatabase.InsertResult.Ok -> { added++; importer.removePendingRecord(rec.id) }
                            else -> { pending++ }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("WadioImport", "stream failed", t)
                failed++
                importer.abortStream(session)
            }
        }
        withContext(Dispatchers.Main) {
            val text = "Import results: $added added, $dup duplicate(s) skipped, $pending pending recovery, $failed failed."
            _state.update { it.copy(importNotice = text, importNoticeEpoch = System.currentTimeMillis()) }
            refreshVault()
        }
        // Auto-clear after 4 seconds, matching src/app.rs.
        kotlinx.coroutines.delay(4_000)
        withContext(Dispatchers.Main) { _state.update { it.copy(importNotice = null) } }
    }

    private fun queryDisplayNameAndSize(uri: android.net.Uri): Pair<String?, Long>? {
        return runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                name to size
            }
        }.getOrNull()
    }
}

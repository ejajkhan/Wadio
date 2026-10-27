package com.wadio.app

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.media.ExifInterface
import java.security.MessageDigest
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import android.util.Log
import com.wadio.app.audio.AudioVolumeController
import com.wadio.app.audio.StreamState
import com.wadio.app.auth.AuthEngine
import com.wadio.app.data.AlbumRecord
import com.wadio.app.data.MediaRecord
import com.wadio.app.data.WadioDatabase
import com.wadio.app.importer.ImportCoordinator
import com.wadio.app.importer.ImportSession
import com.wadio.app.util.ImageEnhancer
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
    val showMediaChooser: Boolean = false,
    val showAlbumList: Boolean = true,
    val pinError: String? = null,
    val authPending: Boolean = false,
    val pinLockedUntilUnix: Long = 0L,

    val passInput: String = "",
    val confirmPassInput: String = "",

    val audioStates: List<StreamState> = emptyList(),

    val cachedMedia: List<MediaRecord> = emptyList(),
    val cachedAlbums: List<AlbumRecord> = emptyList(),
    val albumCovers: Map<String, MediaRecord> = emptyMap(),
    val albumCounts: Map<String, Int> = emptyMap(),
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
    val enhancementInProgress: Boolean = false,
    val enhancementMessage: String? = null,
    val showSettings: Boolean = false,
    val autoLockSeconds: Int = 60,
    val confirmDeletes: Boolean = true,
    val allowScreenshots: Boolean = false,
    val authMode: String = "both",
    val biometricFailedAttempts: Int = 0,
    val pinFallbackAvailable: Boolean = false,
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
    @Volatile var externalPickerActive: Boolean = false
    @Volatile var importInProgress: Boolean = false

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

            val albumIds = (db.getAlbums("Image") + db.getAlbums("Video")).map { it.id }.toHashSet()
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
                    autoLockSeconds = app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).getInt("auto_lock_seconds", 60),
                    confirmDeletes = app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).getBoolean("confirm_deletes", true),
                    allowScreenshots = app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).getBoolean("allow_screenshots", false),
                    authMode = app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).getString("auth_mode", "both") ?: "both",
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
            startupLogger.log("UNCAUGHT CRASH: ${e.message}", e)
            // Persist a short marker so the next launch can show/share the log without ADB.
            runCatching {
                app.getSharedPreferences("wadio_startup", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", "${e.javaClass.name}: ${e.message ?: "No message"}\n${Log.getStackTraceString(e)}")
                    .commit()
            }
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
                _state.update { it.copy(unlocked = true, showPinPrompt = false, showMediaChooser = true, showAlbumList = true, pinError = null) }
                refreshVault()
            } else {
                _state.update { it.copy(pinError = "Set up a PIN before using biometric unlock") }
            }
        }

        val viewingPhoto = _state.value.tab == ActiveTab.Pictures && _state.value.viewerMediaId != null
        if (lastActivityNanos == 0L || viewingPhoto) lastActivityNanos = System.nanoTime()
        val idleSecs = (System.nanoTime() - lastActivityNanos) / 1_000_000_000.0
        if (auth.unlocked && !viewingPhoto && !externalPickerActive && !importInProgress && idleSecs >= _state.value.autoLockSeconds.toDouble()) lockVault()

        if (_state.value.tab == ActiveTab.Audio) refreshAudio()
    }

    fun noteUserActivity() { lastActivityNanos = System.nanoTime() }

    fun lockVault() {
        scope.launch {
            _state.update {
                it.copy(
                    tab = ActiveTab.Audio, unlocked = false,
                    showPinPrompt = false, showMediaChooser = false, showAlbumList = true, showSettings = false,
                    pinError = null, passInput = "", confirmPassInput = "",
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

    fun openAuthPrompt() {
        if (!auth.unlocked) {
            _state.update {
                it.copy(
                    showPinPrompt = true, pinError = null, passInput = "", confirmPassInput = "",
                    biometricFailedAttempts = 0,
                    pinFallbackAvailable = it.authMode != "biometric" || !it.hasPin,
                )
            }
        }
    }

    fun showMediaChooser() {
        if (!auth.unlocked) return
        _state.update { it.copy(showMediaChooser = true, showAlbumList = true, tab = ActiveTab.Audio, showSettings = false) }
        noteUserActivity()
    }

    fun goBack() {
        val s = _state.value
        when {
            s.showSettings -> _state.update { it.copy(showSettings = false) }
            s.viewerMediaId != null -> closeViewer()
            s.selectionMode -> _state.update { it.copy(selectionMode = false, selectedMediaIds = emptySet(), selectionDestination = "") }
            s.unlocked && s.showMediaChooser -> lockVault()
            s.unlocked && !s.showAlbumList -> showAlbumList()
            s.unlocked && s.tab != ActiveTab.Audio && s.showAlbumList -> showMediaChooser()
            s.showPinPrompt -> dismissAuthPrompt()
        }
        noteUserActivity()
    }

    fun openSettings() { _state.update { it.copy(showSettings = true) } }
    fun setAutoLockSeconds(seconds: Int) {
        val safe = seconds.coerceIn(15, 300)
        app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).edit().putInt("auto_lock_seconds", safe).apply()
        _state.update { it.copy(autoLockSeconds = safe) }
    }
    fun setConfirmDeletes(enabled: Boolean) {
        app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("confirm_deletes", enabled).apply()
        _state.update { it.copy(confirmDeletes = enabled) }
    }
    fun setAllowScreenshots(enabled: Boolean) {
        app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).edit().putBoolean("allow_screenshots", enabled).apply()
        _state.update { it.copy(allowScreenshots = enabled) }
    }
    fun setAuthMode(mode: String) {
        val safe = mode.takeIf { it in setOf("pin", "biometric", "both") } ?: "both"
        app.getSharedPreferences("wadio_settings", android.content.Context.MODE_PRIVATE).edit().putString("auth_mode", safe).apply()
        _state.update { it.copy(authMode = safe, biometricFailedAttempts = 0, pinFallbackAvailable = safe != "biometric" || !it.hasPin) }
    }


    fun showAlbumList() {
        _state.update { it.copy(showMediaChooser = false, showAlbumList = true, selectionMode = false, selectedMediaIds = emptySet()) }
        noteUserActivity()
        refreshVault()
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
        if (s.hasPin && s.authMode == "biometric" && !s.pinFallbackAvailable) return
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
                            showMediaChooser = true, showAlbumList = true,
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
                        it.copy(unlocked = true, showPinPrompt = false, showMediaChooser = true, showAlbumList = true, authPending = false,
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
        val s = _state.value
        if (s.authMode == "pin" || auth.unlocked || !s.showPinPrompt || !auth.hasPin) return
        scope.launch { _biometricRequest.emit(Unit) }
    }

    fun noteBiometricFailure() {
        _state.update { current ->
            val attempts = (current.biometricFailedAttempts + 1).coerceAtMost(3)
            current.copy(
                biometricFailedAttempts = attempts,
                pinFallbackAvailable = current.authMode != "biometric" || attempts >= 3,
            )
        }
    }

    fun enablePinFallback(message: String? = null) {
        _state.update {
            it.copy(pinFallbackAvailable = true, pinError = message ?: it.pinError)
        }
    }

    // ─── navigation ───────────────────────────────────────────────────────
    fun setTab(tab: ActiveTab) {
        if (tab != ActiveTab.Audio && !auth.unlocked) return
        _state.update { it.copy(
            tab = tab,
            showMediaChooser = false,
            showAlbumList = tab != ActiveTab.Audio,
            showSettings = false,
            selectedAlbum = if (tab == ActiveTab.Videos) "default-videos" else "default",
            selectionMode = false, selectedMediaIds = emptySet(),
        ) }
        scope.launch {
            if (tab != ActiveTab.Audio) refreshVault()
        }
        noteUserActivity()
    }

    fun selectAlbum(id: String) {
        _state.update {
            it.copy(selectedAlbum = id, showMediaChooser = false, showAlbumList = false,
                selectionMode = false, selectedMediaIds = emptySet())
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
        val mediaType = if (_state.value.tab == ActiveTab.Videos) "Video" else "Image"
        if (db.createAlbum(id, name, mediaType)) {
            _state.update {
                it.copy(selectedAlbum = id, showAlbumList = true, showMediaChooser = false,
                    showNewAlbumModal = false, newAlbumInput = "", albumError = null, cachedAlbums = db.getAlbums(mediaType))
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
                        cachedAlbums = db.getAlbums(if (it.tab == ActiveTab.Videos) "Video" else "Image"),
                    )
                }
                refreshVault()
            }
        }
    }

    fun renameAlbum(id: String, name: String) {
        scope.launch(Dispatchers.IO) {
            val ok = db.renameAlbum(id, name)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(importNotice = if (ok) "Album renamed" else "Could not rename album (name may already exist)", importNoticeEpoch = System.currentTimeMillis()) }
                refreshVault()
            }
        }
    }

    /** Export all album files to a folder chosen by the user. Originals are retained
     * until every exported file has been copied and verified, then the vault album is deleted. */
    fun releaseAlbumToFolder(albumId: String, treeUri: Uri) = scope.launch(Dispatchers.IO) {
        val mediaType = if (_state.value.tab == ActiveTab.Videos) "Video" else "Image"
        val album = db.getAlbums(mediaType).firstOrNull { it.id == albumId }
        if (album == null || albumId == "default" || albumId == "default-videos") {
            withContext(Dispatchers.Main) { _state.update { it.copy(importNotice = "The default album cannot be released", importNoticeEpoch = System.currentTimeMillis()) } }
            return@launch
        }
        val records = db.getMediaByAlbum(albumId)
        val resolver = app.contentResolver
        val treeDoc = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull()
        if (treeDoc == null) {
            withContext(Dispatchers.Main) { _state.update { it.copy(importNotice = "Could not access selected folder", importNoticeEpoch = System.currentTimeMillis()) } }
            return@launch
        }
        val createdUris = mutableListOf<Uri>()
        var failure: String? = null
        try {
            val targetDir = DocumentsContract.createDocument(resolver, treeDoc, DocumentsContract.Document.MIME_TYPE_DIR, album.name)
                ?: throw java.io.IOException("Could not create album folder in selected destination")
            createdUris += targetDir
            val usedNames = mutableSetOf<String>()
            for (record in records) {
                val src = File(baseDir, record.relativePath)
                if (!src.isFile) throw java.io.IOException("Missing vault file: ${record.fileName}")
                var outName = record.fileName.ifBlank { src.name }
                if (!usedNames.add(outName.lowercase())) {
                    val stem = outName.substringBeforeLast('.', outName)
                    val ext = outName.substringAfterLast('.', "").takeIf { it != outName }.orEmpty()
                    var n = 2
                    do { outName = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"; n++ }
                    while (!usedNames.add(outName.lowercase()))
                }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase())
                    ?: "application/octet-stream"
                val outUri = DocumentsContract.createDocument(resolver, targetDir, mime, outName)
                    ?: throw java.io.IOException("Could not create ${record.fileName}")
                createdUris += outUri
                val copied = resolver.openOutputStream(outUri, "w")?.use { output ->
                    src.inputStream().use { input -> input.copyTo(output) }
                } ?: throw java.io.IOException("Could not write ${record.fileName}")
                if (copied != src.length()) throw java.io.IOException("Verification failed for ${record.fileName}")
            }
            if (!db.deleteAlbumAfterRelease(albumId)) throw java.io.IOException("Files were exported, but Wadio could not remove the album")
            val physicalFolder = File(File(File(baseDir, "media"), if (album.mediaType == "Video") "videos" else "photos"), albumId)
            // At this point the exported copies are verified and the database row is gone.
            runCatching { physicalFolder.deleteRecursively() }
        } catch (t: Throwable) {
            failure = t.message ?: "Release failed"
            // Keep the exported copy if the DB row was already removed. Only roll back partial exports while originals remain in the vault.
            val albumStillExists = db.getAlbums(mediaType).any { it.id == albumId }
            if (albumStillExists) createdUris.asReversed().forEach { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
        withContext(Dispatchers.Main) {
            _state.update { it.copy(importNotice = failure?.let { "Release failed: $it" } ?: "Album released successfully", importNoticeEpoch = System.currentTimeMillis(), selectedAlbum = if (it.selectedAlbum == albumId) if (mediaType == "Video") "default-videos" else "default" else it.selectedAlbum) }
            refreshVault()
        }
    }

    fun rotateImagePermanently(record: MediaRecord, degrees: Int) = scope.launch(Dispatchers.IO) {
        val file = File(baseDir, record.relativePath)
        var ok = false
        var message = "Could not rotate this image format"
        try {
            if (!file.isFile || file.extension.equals("gif", true)) throw java.io.IOException("This image format cannot be rotated in place")
            val exif = ExifInterface(file.absolutePath)
            val current = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            val desired = ((current + degrees) % 360 + 360) % 360
            val orientation = when (desired) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
            val hash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            val dbOk = db.updateRotatedMedia(record.id, file.length(), hash, record.width, record.height)
            if (!dbOk) throw java.io.IOException("Could not update media metadata")
            ok = true
            message = "Image rotation saved"
        } catch (t: Throwable) { message = t.message ?: message }
        withContext(Dispatchers.Main) {
            _state.update { it.copy(importNotice = message, importNoticeEpoch = System.currentTimeMillis()) }
            refreshVault()
        }
    }

    /**
     * Enhance the selected vault photo only after the viewer has explicitly confirmed.
     * Processing is performed off the main thread. The original is backed up before the
     * same-directory replacement; the backup is restored if metadata commit fails.
     */
    fun enhanceImagePermanently(record: MediaRecord) {
        if (_state.value.enhancementInProgress) return
        if (record.mediaType != "Image") {
            _state.update { it.copy(enhancementMessage = "Only photos can be enhanced") }
            return
        }
        _state.update { it.copy(enhancementInProgress = true, enhancementMessage = null) }
        scope.launch(Dispatchers.IO) {
            val target = File(baseDir, record.relativePath)
            var temp: File? = null
            var backup: File? = null
            var backupCreated = false
            var replaced = false
            var committed = false
            var message: String
            try {
                val vaultRoot = baseDir.canonicalFile
                val canonicalTarget = target.canonicalFile
                if (!canonicalTarget.path.startsWith(vaultRoot.path + File.separator)) {
                    throw java.io.IOException("Invalid vault photo path")
                }
                if (!canonicalTarget.isFile) throw java.io.IOException("The original photo is missing")
                if (!ImageEnhancer.isSupported(canonicalTarget)) {
                    throw java.io.IOException("This image format is not supported for enhancement")
                }

                val ext = canonicalTarget.extension.lowercase()
                temp = File(canonicalTarget.parentFile, ".${canonicalTarget.nameWithoutExtension}.wadio-enhanced.$ext")
                backup = File(canonicalTarget.parentFile, "${canonicalTarget.name}.wadio-original-backup")
                if (backup.exists()) throw java.io.IOException("A previous backup exists; the original was preserved. Please resolve the backup before retrying.")
                if (temp.exists() && !temp.delete()) throw java.io.IOException("Could not clear an old temporary enhancement file")

                val result = ImageEnhancer.enhance(canonicalTarget, temp)
                FileOutputStream(temp, true).use { it.fd.sync() }
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(temp).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val hash = digest.digest().joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }

                Files.copy(canonicalTarget.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                backupCreated = true
                FileOutputStream(backup, true).use { it.fd.sync() }
                // From this point onward, any failure must attempt restoration from the backup.
                replaced = true
                try {
                    Files.move(temp.toPath(), canonicalTarget.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), canonicalTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                if (!db.updateRotatedMedia(record.id, canonicalTarget.length(), hash, result.width, result.height)) {
                    throw java.io.IOException("Could not update the photo database; restoring the original")
                }
                committed = true
                backup.delete()
                message = "Photo enhanced and original replaced successfully"
            } catch (t: Throwable) {
                message = t.message ?: "Photo enhancement failed"
                val backupFile = backup
                if (replaced && !committed && backupFile?.isFile == true) {
                    try {
                        Files.copy(backupFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        backupFile.delete()
                    } catch (_: Throwable) {
                        message = "Enhancement failed and automatic restore was unsuccessful. Your original backup is at: ${backupFile.absolutePath}"
                    }
                } else if (backupCreated && !replaced) {
                    // The original has not been touched, so an incomplete backup can be removed safely.
                    backupFile?.delete()
                }
            } finally {
                temp?.let { if (it.exists()) it.delete() }
            }
            withContext(Dispatchers.Main) {
                val stillViewingPhoto = _state.value.viewerMediaId == record.id
                _state.update {
                    it.copy(
                        enhancementInProgress = false,
                        enhancementMessage = if (stillViewingPhoto) message else null,
                    )
                }
                refreshVault()
            }
        }
    }

    fun dismissEnhancementMessage() {
        _state.update { it.copy(enhancementMessage = null) }
    }

    // ─── gallery ──────────────────────────────────────────────────────────
    fun refreshVault() = scope.launch(Dispatchers.IO) {
        val mediaType = if (_state.value.tab == ActiveTab.Videos) "Video" else "Image"
        val albums = db.getAlbums(mediaType)
        val selected = _state.value.selectedAlbum.ifEmpty { if (mediaType == "Video") "default-videos" else "default" }
        val effectiveAlbum = if (albums.any { it.id == selected }) selected
            else albums.firstOrNull()?.id ?: if (mediaType == "Video") "default-videos" else "default"
        val records = db.getMediaByAlbum(effectiveAlbum)
        val sortMode = _state.value.sortMode
        val sortAscending = _state.value.sortAscending
        // Recompute every cover from the first item in the active sort order.
        // This also refreshes covers after a move or deletion.
        val albumItemsById = albums.associate { album -> album.id to db.getMediaByAlbum(album.id) }
        val covers = albumItemsById.mapNotNull { (id, albumItems) ->
            val cover = sortMedia(albumItems, sortMode, sortAscending).firstOrNull()
            if (cover != null) id to cover else null
        }.toMap()
        val counts = albumItemsById.mapValues { it.value.size }

        withContext(Dispatchers.Main) {
            val tab = _state.value.tab
            val filtered = if (tab == ActiveTab.Pictures)
                records.filter { it.mediaType == "Image" }
            else if (tab == ActiveTab.Videos)
                records.filter { it.mediaType == "Video" }
            else records
            _state.update {
                it.copy(
                    cachedAlbums = albums, albumCovers = covers, albumCounts = counts,
                    selectedAlbum = effectiveAlbum, cachedMedia = sortMedia(filtered, it.sortMode, it.sortAscending),
                )
            }
        }
    }

    fun setSortMode(mode: MediaSortMode) {
        _state.update { it.copy(sortMode = mode, cachedMedia = sortMedia(it.cachedMedia, mode, it.sortAscending)) }
        noteUserActivity()
        refreshVault()
    }
    fun toggleSortDirection() {
        _state.update { it.copy(sortAscending = !it.sortAscending,
            cachedMedia = sortMedia(it.cachedMedia, it.sortMode, !it.sortAscending)) }
        noteUserActivity()
        refreshVault()
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

    fun selectAllMedia() {
        val ids = _state.value.cachedMedia.map { it.id }.toSet()
        _state.update {
            it.copy(selectionMode = ids.isNotEmpty(), selectedMediaIds = ids)
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
            com.wadio.app.MainActivity.launchVideo(app, s.cachedMedia.filter { it.mediaType == "Video" }.map { it.relativePath }, s.cachedMedia.filter { it.mediaType == "Video" }.indexOfFirst { it.id == record.id })
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
        if (s.cachedMedia.isEmpty()) return
        val next = (index + direction + s.cachedMedia.size) % s.cachedMedia.size
        _state.update { it.copy(viewerMediaId = s.cachedMedia[next].id) }
        noteUserActivity()
    }

    fun cancelSelection() = _state.update { it.copy(selectionMode = false, selectedMediaIds = emptySet(), selectionDestination = "") }
    fun requestDeleteSelected() {
        if (_state.value.confirmDeletes) _state.update { it.copy(confirmDeleteSelected = true) }
        else performDeleteSelected()
    }
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
            val triple = db.prepareMediaCopy(id, dest, java.util.UUID.randomUUID().toString())
            if (triple == null) {
                errors++
                continue
            }
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
                    selectionMode = false,
                    selectedMediaIds = emptySet(), selectionDestination = "",
                    importNotice = "Selected items moved to album",
                    importNoticeEpoch = System.currentTimeMillis(),
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
        importInProgress = true
        noteUserActivity()
        try {
        val albumId = _state.value.selectedAlbum
        val resolver = app.contentResolver
        var added = 0; var dup = 0; var failed = 0; var pending = 0
        for (uri in uris) {
            val (name, size) = queryDisplayNameAndSize(uri) ?: (null to 0L)
            if (name == null) { failed++; continue }
            val session = importer.startStream(size, name, albumId)
            if (session == null) {
                failed++
                continue
            }
            try {
                val stream = resolver.openInputStream(uri)
                if (stream == null) {
                    failed++
                    importer.abortStream(session)
                    continue
                }
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
                val expectedType = if (_state.value.tab == ActiveTab.Videos) "Video" else "Image"
                if (rec.mediaType != expectedType) {
                    File(baseDir, rec.relativePath).delete()
                    importer.removePendingRecord(rec.id)
                    failed++
                    continue
                }
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
        } finally {
            importInProgress = false
            noteUserActivity()
        }
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

package com.wordsmart.vocabulary

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.util.Locale
import java.io.BufferedInputStream
import java.util.concurrent.Executors
import java.util.concurrent.Callable

private var Ink = Color(0xFFF5F6FA)
private var Muted = Color(0xFFA5ACBB)
private var Blue = Color(0xFF79BFFF)
private var Canvas = Color(0xFF050608)
private var Line = Color(0xFF24262A)
private var Green = Color(0xFF8EE7B0)
private var SurfaceColor = Color(0xFF111318)
private var ExampleSurface = Color(0xFF28251D)
private var ExampleInk = Color(0xFFFFD889)

private fun applyPalette(dark: Boolean) {
    if (dark) {
        Ink = Color(0xFFF5F6FA); Muted = Color(0xFFA5ACBB); Blue = Color(0xFF79BFFF)
        Canvas = Color(0xFF050608); Line = Color(0xFF24262A); Green = Color(0xFF8EE7B0)
        SurfaceColor = Color(0xFF111318); ExampleSurface = Color(0xFF28251D); ExampleInk = Color(0xFFFFD889)
    } else {
        Ink = Color(0xFF17233B); Muted = Color(0xFF65718A); Blue = Color(0xFF3559E0)
        Canvas = Color(0xFFF5F7FC); Line = Color(0xFFE5E9F2); Green = Color(0xFF13795B)
        SurfaceColor = Color.White; ExampleSurface = Color(0xFFFFF8E8); ExampleInk = Color(0xFF936515)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = WordRepository(this)
        // Keep file copying and JSON parsing off the UI thread. Warm the in-memory
        // vocabulary cache once, then Compose screens can read without disk I/O.
        setContent {
            var ready by remember { mutableStateOf(false) }
            var loadFailed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // Render the real home screen immediately. Asset preparation and the
                // complete in-memory vocabulary cache are built off the UI thread.
                loadFailed = false
                try {
                    withContext(Dispatchers.IO) {
                        repository.prepare()
                        repository.warmCache()
                    }
                    ready = true
                } catch (_: Exception) {
                    loadFailed = true
                }
            }
            if (ready) {
                WordsmartApp(repository)
            } else {
                applyPalette(true)
                MaterialTheme(colorScheme = darkColorScheme(
                    background = Color(0xFF050608),
                    surface = Color(0xFF111318),
                    primary = Color(0xFF79BFFF),
                    onSurface = Color(0xFFF5F6FA)
                )) {
                    Scaffold(containerColor = Color(0xFF050608), bottomBar = {
                        HomeBottomBar(
                            onBookmarks = { Toast.makeText(this@MainActivity, if (loadFailed) "Vocabulary loading failed. Restart the app to retry." else "Preparing vocabulary…", Toast.LENGTH_SHORT).show() },
                            onSettings = { Toast.makeText(this@MainActivity, if (loadFailed) "Vocabulary loading failed. Restart the app to retry." else "Preparing vocabulary…", Toast.LENGTH_SHORT).show() }
                        )
                    }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            HomeScreen(
                                onChoose = { Toast.makeText(this@MainActivity, if (loadFailed) "Vocabulary loading failed. Restart the app to retry." else "Preparing vocabulary…", Toast.LENGTH_SHORT).show() },
                                onBookmarks = { Toast.makeText(this@MainActivity, "Preparing vocabulary…", Toast.LENGTH_SHORT).show() },
                                onSettings = { Toast.makeText(this@MainActivity, "Preparing vocabulary…", Toast.LENGTH_SHORT).show() },
                                surface = Color(0xFF111318)
                            )
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                if (loadFailed) Text("Could not load vocabulary. Restart to retry.", color = Color(0xFFFF8A80), fontSize = 12.sp)
                                else {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF79BFFF), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Text("Preparing vocabulary for fast offline use…", color = Color(0xFFA5ACBB), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class WordRepository(private val context: Context) {
    val root: File = File(context.filesDir, "words")
    private val collections = listOf("word_smart_1", "word_smart_2")
    // Parsed JSON and WordRecord instances are retained for the life of the process.
    // This avoids repeatedly opening/parsing the 68 vocabulary files during navigation.
    @Volatile private var cacheReady = false
    private val arraysByPath = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()
    private val wordsByGroup = java.util.concurrent.ConcurrentHashMap<String, List<WordRecord>>()
    private val summariesByCollection = java.util.concurrent.ConcurrentHashMap<String, List<GroupSummary>>()
    @Volatile private var allWordsCache: List<WordRecord> = emptyList()

    fun groupSummaries(collection: String): List<GroupSummary> =
        summariesByCollection[collection] ?: (1..34).map { group ->
            val groupWords = words(collection, group)
            GroupSummary(group, groupWords.size, groupWords.firstOrNull()?.word ?: "No words", groupWords.lastOrNull()?.word.orEmpty())
        }

    fun prepare() {
        if (File(root, ".initialized").exists()) return
        root.mkdirs()
        collections.forEach { collection ->
            File(root, collection).mkdirs()
            copyAssets("$collection")
        }
        File(root, ".initialized").writeText("1")
    }

    private fun copyAssets(path: String) {
        val children = context.assets.list(path) ?: return
        if (children.isEmpty()) return
        children.forEach { child ->
            val assetPath = "$path/$child"
            val nested = context.assets.list(assetPath)
            if (!nested.isNullOrEmpty()) {
                File(root, assetPath).mkdirs()
                copyAssets(assetPath)
            } else if (child.endsWith(".json", true)) {
                val destination = File(root, assetPath)
                destination.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }

    fun groupFiles(collection: String): List<File> = File(root, collection).listFiles { f -> f.isFile && f.extension.equals("json", true) }
        ?.sortedBy { it.name } ?: emptyList()

    @Synchronized
    fun warmCache() {
        val files = collections.flatMap { collection ->
            groupFiles(collection).map { file -> collection to file }
        }
        // Parse independent JSON files on a small bounded pool while the app is
        // still showing its startup screen, rather than serially parsing all files.
        // Use the device's available CPU parallelism while leaving one logical
        // processor free where possible so the already-visible home screen stays responsive.
        val availableCpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val workerCount = minOf(files.size.coerceAtLeast(1), 16)
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val futures = files.map { (collection, file) ->
                executor.submit(Callable {
                    val array = JSONArray(file.readText(Charsets.UTF_8))
                    val records = (0 until array.length()).mapNotNull { index ->
                        val obj = array.optJSONObject(index) ?: return@mapNotNull null
                        WordRecord(file, index, obj).also { record ->
                            // Precompute frequently rendered, immutable text fields off the UI thread.
                            record.posText
                            record.pronunciationText
                            record.meaningEnglish
                            record.meaningsBangla
                            record.examples
                        }
                    }
                    Triple(collection, file, Pair(array, records))
                })
            }
            val nextArrays = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()
            val nextGroups = java.util.concurrent.ConcurrentHashMap<String, List<WordRecord>>()
            val nextAll = ArrayList<WordRecord>(5000)
            val grouped = collections.associateWith { mutableListOf<GroupSummary>() }
            futures.forEach { future ->
                try {
                    val (collection, file, parsed) = future.get()
                    val (array, records) = parsed
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    nextArrays[path] = array
                    val groupNumber = file.nameWithoutExtension.takeLast(2)
                    nextGroups["$collection#$groupNumber"] = records
                    nextAll.addAll(records)
                    grouped[collection]?.add(
                        GroupSummary(
                            groupNumber.toIntOrNull() ?: 0,
                            records.size,
                            records.firstOrNull()?.word ?: "No words",
                            records.lastOrNull()?.word.orEmpty()
                        )
                    )
                } catch (_: Exception) {
                    // A damaged group stays unavailable rather than crashing startup.
                }
            }
            arraysByPath.clear()
            arraysByPath.putAll(nextArrays)
            wordsByGroup.clear()
            wordsByGroup.putAll(nextGroups)
            summariesByCollection.clear()
            grouped.forEach { (collection, summaries) ->
                summariesByCollection[collection] = summaries.sortedBy { it.group }
            }
            allWordsCache = nextAll
            cacheReady = true
        } finally {
            executor.shutdown()
        }
    }

    fun words(collection: String, group: Int): List<WordRecord> {
        val key = "$collection#%02d".format(group)
        if (cacheReady) return wordsByGroup[key] ?: emptyList()
        val file = groupFiles(collection).firstOrNull { it.nameWithoutExtension.endsWith("%02d".format(group)) } ?: return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).map { WordRecord(file, it, array.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun allWords(): List<WordRecord> {
        if (cacheReady) return allWordsCache
        return collections.flatMap { collection ->
            groupFiles(collection).flatMap { file -> try { val array = JSONArray(file.readText(Charsets.UTF_8)); (0 until array.length()).map { WordRecord(file, it, array.getJSONObject(it)) } } catch (_: Exception) { emptyList() } }
        }
    }

    fun count(collection: String, group: Int): Int = words(collection, group).size

    fun totalWords(collection: String): Int =
        if (cacheReady) (1..34).sumOf { words(collection, it).size }
        else groupFiles(collection).sumOf { file -> runCatching { JSONArray(file.readText(Charsets.UTF_8)).length() }.getOrDefault(0) }

    @Synchronized
    fun save(record: WordRecord) {
        val path = record.file.relativeTo(root).invariantSeparatorsPath
        val array = arraysByPath[path] ?: JSONArray(record.file.readText(Charsets.UTF_8)).also { arraysByPath[path] = it }
        array.put(record.index, record.json)
        record.refreshDisplayFields()
        // Vocabulary files are small; serialize the cached array, never re-read the file.
        record.file.writeText(array.toString(2), Charsets.UTF_8)
    }

    fun import(uri: Uri) {
        val expected = buildSet {
            (1..34).forEach { add("word_smart_1/ws1_group_%02d.json".format(it)) }
            (1..34).forEach { add("word_smart_2/ws2_group_%02d.json".format(it)) }
        }
        val staging = File(context.cacheDir, "wordsmart-import-staging").apply { deleteRecursively(); mkdirs() }
        val seen = mutableSetOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        val name = entry.name.replace('\\', '/')
                        require(name in expected) { "ZIP has an unsupported path or file: $name" }
                        require(seen.add(name)) { "ZIP contains a duplicate file: $name" }
                        val out = File(staging, name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                        val parsed = JSONArray(out.readText(Charsets.UTF_8))
                        for (i in 0 until parsed.length()) require(parsed.optJSONObject(i) != null) { "Invalid word data in $name" }
                        zip.closeEntry()
                    }
                }
            } ?: error("Could not open the selected ZIP file")
            require(seen == expected) { "This ZIP does not match the Wordsmart format. Expected all 68 group JSON files." }
            expected.forEach { relative ->
                val destination = File(root, relative)
                destination.parentFile?.mkdirs()
                File(staging, relative).copyTo(destination, overwrite = true)
            }
        } finally { staging.deleteRecursively() }
    }

    fun export(uri: Uri) {
        val resolver = context.contentResolver
        resolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                root.walkTopDown().filter { it.isFile && it.extension.equals("json", true) }.sortedBy { it.relativeTo(root).path }.forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Could not open the selected export destination")
    }
}

private data class GroupSummary(
    val group: Int,
    val count: Int,
    val firstWord: String,
    val lastWord: String
)

private class WordRecord(val file: File, val index: Int, val json: JSONObject) {
    // Stable display fields are computed once when the in-memory cache warms.
    val word: String = json.optString("word", "Untitled")
    val number: Int = json.optInt("no", index + 1)
    val posText: String = json.optJSONArray("pos")?.let { a ->
        (0 until a.length()).joinToString(", ") { a.optString(it) }
    }.orEmpty()
    val pronunciationText: String = json.optString("pronunciation_bn", "").ifBlank {
        json.optString("pronunciation", "")
    }
    val meaningEnglish: String = json.optString("meaning_en", "No English meaning")
    @Volatile var meaningsBangla: String = ""
        private set
    @Volatile var synonymPreview: String = ""
        private set
    @Volatile var pronunciationDisplay: String = ""
        private set

    init { refreshDisplayFields() }

    // Called after load and after edits/imports so row rendering never repeatedly
    // traverses JSON arrays or allocates preview strings during Compose recomposition.
    fun refreshDisplayFields() {
        meaningsBangla = json.optJSONArray("meaning_bn")?.let { a ->
            (0 until a.length()).joinToString("; ") { a.optString(it) }
        }.orEmpty()
        synonymPreview = json.optJSONArray("synonyms")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val obj = a.optJSONObject(i) ?: return@mapNotNull null
                val en = obj.optString("en", "").trim()
                if (en.isBlank()) null else en + obj.optString("bn", "").takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
            }.joinToString(", ")
        }.orEmpty()
        pronunciationDisplay = json.optString("pronunciation_bn", "").ifBlank { json.optString("pronunciation", "") }
    }

    val examples: List<ExampleItem> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        collectUniqueExamples(json)
    }
}

private enum class Page { HOME, GROUPS, WORDS, WORD, SETTINGS, BOOKMARKS, BOOKMARK_WORDS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordsmartApp(repository: WordRepository) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wordsmart_prefs", Context.MODE_PRIVATE) }
    var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
    var page by remember { mutableStateOf(runCatching { Page.valueOf(prefs.getString("last_page", "HOME") ?: "HOME") }.getOrDefault(Page.HOME)) }
    var previousPage by remember { mutableStateOf(Page.HOME) }
    var collection by remember { mutableStateOf(prefs.getString("last_collection", "word_smart_1") ?: "word_smart_1") }
    var selectedGroup by remember { mutableIntStateOf(prefs.getInt("last_group", 1)) }
    var wordIndex by remember { mutableIntStateOf(prefs.getInt("last_word_index", 0)) }
    var revision by remember { mutableIntStateOf(0) }
    var editType by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var newEnglish by remember { mutableStateOf("") }
    var newBangla by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var bookmarkedOnly by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(prefs.getStringSet("bookmarks", emptySet())?.toSet() ?: emptySet()) }
    // Navigation provenance keeps Android back gestures returning to the actual origin.
    var bookmarksReturnPage by remember { mutableStateOf(Page.HOME) }
    var bookmarkGroupOnly by remember { mutableStateOf(false) }
    var bookmarkCollection by remember { mutableStateOf("word_smart_1") }
    var bookmarkGroup by remember { mutableIntStateOf(1) }
    var wordReturnPage by remember { mutableStateOf(Page.WORDS) }
    // The active sequence defines Previous/Next/swipe order. It can be a full group,
    // a group-filtered bookmark list, or the global bookmark list.
    var activeWordSequence by remember { mutableStateOf<List<WordRecord>>(emptyList()) }
    var animationStyle by remember { mutableStateOf(prefs.getString("navigation_animation", "Slide") ?: "Slide") }
    // Keep word-list scroll state in the parent so it survives navigating into a word and back.
    val listStateCache = remember { mutableMapOf<String, LazyListState>() }
    val wordListState = remember(collection, selectedGroup) {
        listStateCache.getOrPut("words:$collection#$selectedGroup") { LazyListState() }
    }
    val bookmarkListState = remember(bookmarkCollection, bookmarkGroup, bookmarkGroupOnly) {
        listStateCache.getOrPut("bookmarks:$bookmarkCollection#$bookmarkGroup#$bookmarkGroupOnly") { LazyListState() }
    }

    fun persistLocation() {
        prefs.edit().putString("last_page", page.name).putString("last_collection", collection)
            .putInt("last_group", selectedGroup).putInt("last_word_index", wordIndex).apply()
    }
    fun go(to: Page) { if (page == Page.SETTINGS) previousPage = to; page = to; persistLocation() }
    fun goBack() {
        page = when (page) {
            Page.WORD -> wordReturnPage
            Page.WORDS -> Page.GROUPS
            Page.GROUPS -> Page.HOME
            Page.SETTINGS -> previousPage
            Page.BOOKMARKS -> bookmarksReturnPage
            Page.BOOKMARK_WORDS -> Page.BOOKMARKS
            Page.HOME -> Page.HOME
        }
        persistLocation()
    }
    BackHandler(enabled = page != Page.HOME) { goBack() }

    val groupWords = remember(collection, selectedGroup, revision) { repository.words(collection, selectedGroup) }
    val displayedWordSequence = if (page == Page.WORD && activeWordSequence.isNotEmpty()) activeWordSequence else groupWords
    if (wordIndex >= displayedWordSequence.size && displayedWordSequence.isNotEmpty()) wordIndex = displayedWordSequence.lastIndex
    val current = displayedWordSequence.getOrNull(wordIndex)
    val collectionTitle = if (collection == "word_smart_1") "Wordsmart 1" else "Wordsmart 2"
    fun bookmarkKey(record: WordRecord): String = "${record.file.relativeTo(repository.root).invariantSeparatorsPath}#${record.index}"
    fun toggleBookmark(record: WordRecord) {
        val key = bookmarkKey(record)
        bookmarks = if (key in bookmarks) bookmarks - key else bookmarks + key
        prefs.edit().putStringSet("bookmarks", bookmarks).apply()
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) Thread {
            try { repository.export(uri); (context as? ComponentActivity)?.runOnUiThread { Toast.makeText(context, "Export complete — words.zip is ready", Toast.LENGTH_LONG).show() } }
            catch (e: Exception) { (context as? ComponentActivity)?.runOnUiThread { Toast.makeText(context, "Export failed: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importBusy = true
            Thread {
                try {
                    repository.import(uri)
                    repository.warmCache()
                    (context as? ComponentActivity)?.runOnUiThread { revision++; importBusy = false; Toast.makeText(context, "Import complete — Wordsmart data updated", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    (context as? ComponentActivity)?.runOnUiThread { importBusy = false; Toast.makeText(context, "Import rejected: ${e.message ?: "invalid ZIP"}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }

    applyPalette(darkTheme)
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(primary = Blue, background = Canvas, surface = SurfaceColor, onSurface = Ink) else lightColorScheme(primary = Blue, background = Canvas, surface = SurfaceColor, onSurface = Ink)) {
        Scaffold(containerColor = Canvas, topBar = {
            if (page != Page.HOME) TopAppBar(
                title = {
                    if (page == Page.WORD) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${wordIndex + 1}/${displayedWordSequence.size}", fontSize = 14.sp, color = Muted, fontWeight = FontWeight.Medium)
                            Text("•", color = Blue, fontSize = 14.sp)
                            Text("Group $selectedGroup", fontSize = 14.sp, color = Muted, maxLines = 1)
                            Text("•", color = Blue, fontSize = 14.sp)
                            Text(collectionTitle, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Column {
                            Text(when (page) { Page.GROUPS -> collectionTitle; Page.WORDS -> "Group $selectedGroup"; Page.SETTINGS -> "Settings"; Page.BOOKMARKS -> "Bookmarks"; Page.BOOKMARK_WORDS -> "Bookmarked words"; Page.HOME, Page.WORD -> "" }, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (page != Page.SETTINGS && page != Page.BOOKMARKS && page != Page.BOOKMARK_WORDS) Text(when (page) {
                                Page.GROUPS -> "${repository.groupFiles(collection).size} groups  ·  ${repository.totalWords(collection)} words"
                                Page.WORDS -> "$collectionTitle  ·  ${repository.words(collection, selectedGroup).size} words"
                                else -> ""
                            }, fontSize = 13.sp, color = Muted)
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = { goBack() }) { Text("←", fontSize = 27.sp, color = Blue) } },
                actions = {
                    if (page == Page.WORDS) TextButton(onClick = {
                        bookmarkGroupOnly = true
                        bookmarkCollection = collection
                        bookmarkGroup = selectedGroup
                        bookmarksReturnPage = Page.WORDS
                        go(Page.BOOKMARK_WORDS)
                    }) { BookmarkGlyph(bookmarked = true, size = 22) ; Spacer(Modifier.width(5.dp)); Text("Bookmarks", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        }, bottomBar = {
            if (page == Page.HOME) HomeBottomBar(onBookmarks = { go(Page.BOOKMARKS) }, onSettings = { previousPage = Page.HOME; go(Page.SETTINGS) })
        }) { padding ->
            AnimatedContent(targetState = page, modifier = Modifier.padding(padding).fillMaxSize(), transitionSpec = {
                when (animationStyle) {
                    "None" -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                    "Fade" -> fadeIn() togetherWith fadeOut()
                    "Scale" -> (scaleIn(initialScale = 0.96f) + fadeIn()) togetherWith (scaleOut(targetScale = 1.02f) + fadeOut())
                    else -> (slideInHorizontally { it / 5 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 5 } + fadeOut())
                }
            }, label = "page") { shownPage ->
                when (shownPage) {
                    Page.HOME -> HomeScreen(onChoose = { chosen -> collection = chosen; selectedGroup = 1; wordIndex = 0; go(Page.GROUPS) }, onBookmarks = { bookmarkGroupOnly = false; bookmarksReturnPage = Page.HOME; go(Page.BOOKMARKS) }, onSettings = { previousPage = Page.HOME; go(Page.SETTINGS) }, surface = SurfaceColor)
                    Page.GROUPS -> GroupsScreen(repository, collection, onChoose = { group -> selectedGroup = group; wordIndex = 0; bookmarkedOnly = false; activeWordSequence = repository.words(collection, group); go(Page.WORDS) }, onChooseWord = { record ->
                        collection = record.file.parentFile?.name ?: "word_smart_1"
                        selectedGroup = record.file.nameWithoutExtension.takeLast(2).toIntOrNull() ?: 1
                        activeWordSequence = repository.words(collection, selectedGroup)
                        wordIndex = activeWordSequence.indexOfFirst { it.index == record.index }.coerceAtLeast(0)
                        wordReturnPage = Page.GROUPS
                        go(Page.WORD)
                    })
                    Page.WORDS -> {
                        val words = repository.words(collection, selectedGroup)
                        val filtered = if (bookmarkedOnly) words.filter { bookmarkKey(it) in bookmarks } else words
                        WordsScreen(filtered, selectedGroup, bookmarkedOnly, onToggleBookmarks = { bookmarkedOnly = !bookmarkedOnly }, onChoose = { filteredIndex ->
                            val selected = filtered.getOrNull(filteredIndex)
                            if (selected != null) { activeWordSequence = filtered; wordIndex = filtered.indexOfFirst { it.index == selected.index }.coerceAtLeast(0); wordReturnPage = Page.WORDS; go(Page.WORD) }
                        }, isBookmarked = { bookmarkKey(it) in bookmarks }, listState = wordListState)
                    }
                    Page.WORD -> if (current == null) EmptyWordsScreen(onBack = { go(Page.WORDS) }) else WordScreen(
                        record = current, position = wordIndex, total = displayedWordSequence.size,
                        group = selectedGroup, collectionTitle = collectionTitle,
                        isBookmarked = bookmarkKey(current) in bookmarks,
                        onToggleBookmark = { toggleBookmark(current) },
                        onPrevious = { if (wordIndex > 0) { wordIndex--; persistLocation() } },
                        onNext = { if (wordIndex < displayedWordSequence.lastIndex) { wordIndex++; persistLocation() } },
                        onAdd = { type -> editType = type; newEnglish = ""; newBangla = ""; showAddDialog = true },
                        onEdit = { type -> editType = type; showEditDialog = true },
                        onDelete = { type, index -> pendingDelete = type to index })
                    Page.SETTINGS -> SettingsScreen(darkTheme, onThemeChange = { darkTheme = it; prefs.edit().putBoolean("dark_theme", it).apply() }, animationStyle = animationStyle, onAnimationChange = { animationStyle = it; prefs.edit().putString("navigation_animation", it).apply() }, onExport = { exportLauncher.launch("words.zip") }, onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, importBusy = importBusy, surface = SurfaceColor)
                    Page.BOOKMARKS -> {
                        val all = remember(revision, bookmarks.toSet()) { repository.allWords().filter { bookmarkKey(it) in bookmarks } }
                        BookmarksScreen(
                            records = all,
                            listState = bookmarkListState,
                            onChoose = { record ->
                                collection = record.file.parentFile?.name ?: "word_smart_1"
                                selectedGroup = record.file.nameWithoutExtension.takeLast(2).toIntOrNull() ?: 1
                                activeWordSequence = all
                                wordIndex = all.indexOfFirst { it.file == record.file && it.index == record.index }.coerceAtLeast(0)
                                wordReturnPage = Page.BOOKMARKS
                                go(Page.WORD)
                            }
                        )
                    }
                    Page.BOOKMARK_WORDS -> {
                        val all = remember(revision, bookmarks.toSet(), bookmarkCollection, bookmarkGroup) { repository.words(bookmarkCollection, bookmarkGroup).filter { bookmarkKey(it) in bookmarks } }
                        BookmarkWordsScreen(all, bookmarkGroup, onChoose = { record ->
                            collection = bookmarkCollection; selectedGroup = bookmarkGroup
                            activeWordSequence = all
                            wordIndex = all.indexOfFirst { it.file == record.file && it.index == record.index }.coerceAtLeast(0)
                            wordReturnPage = Page.BOOKMARK_WORDS
                            go(Page.WORD)
                        }, isBookmarked = { bookmarkKey(it) in bookmarks }, listState = bookmarkListState)
                    }
                }
            }
        }
    }

    if (showEditDialog && current != null) {
        val title = if (editType == "synonyms") "Edit synonyms" else "Edit antonyms"
        val items = current.json.optJSONArray(editType)
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = Color(0xFF101114),
            titleContentColor = Color(0xFFF5F6FA),
            textContentColor = Color(0xFFD6DAE3),
            shape = RoundedCornerShape(26.dp),
            title = { Text(title, fontWeight = FontWeight.Bold, color = Color(0xFFF5F6FA)) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showEditDialog = false; newEnglish = ""; newBangla = ""; showAddDialog = true }) { Text("+ Add word", color = Blue, fontWeight = FontWeight.Bold) }
                    if (items == null || items.length() == 0) Text("No words added yet.", color = Color(0xFFA5ACBB))
                    else for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: JSONObject()
                        Row(Modifier.fillMaxWidth().background(SurfaceColor, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.optString("en", ""), color = Ink, fontWeight = FontWeight.SemiBold)
                                if (item.optString("bn").isNotBlank()) Text(item.optString("bn"), color = Blue, fontSize = 13.sp)
                            }
                            TextButton(onClick = { pendingDelete = editType to i }) { Text("✕", color = Color(0xFFFF8B8B), fontSize = 18.sp) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEditDialog = false }) { Text("Done") } }
        )
    }

    if (showAddDialog) AlertDialog(
        onDismissRequest = { showAddDialog = false },
        containerColor = Color(0xFF101114),
        titleContentColor = Color(0xFFF5F6FA),
        textContentColor = Color(0xFFD6DAE3),
        shape = RoundedCornerShape(26.dp),
        title = { Text(if (editType == "synonyms") "Add synonym" else "Add antonym", fontWeight = FontWeight.Bold, color = Color(0xFFF5F6FA)) },
        text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = newEnglish, onValueChange = { newEnglish = it }, label = { Text("English word") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFF5F6FA), unfocusedTextColor = Color(0xFFF5F6FA),
                    focusedContainerColor = Color(0xFF08090A), unfocusedContainerColor = Color(0xFF08090A),
                    focusedBorderColor = Color(0xFF79BFFF), unfocusedBorderColor = Color(0xFF34363B),
                    focusedLabelColor = Color(0xFF79BFFF), unfocusedLabelColor = Color(0xFFA5ACBB),
                    cursorColor = Color(0xFF79BFFF)
                )
            )
            OutlinedTextField(
                value = newBangla, onValueChange = { newBangla = it }, label = { Text("Bangla meaning (optional)") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFF5F6FA), unfocusedTextColor = Color(0xFFF5F6FA),
                    focusedContainerColor = Color(0xFF08090A), unfocusedContainerColor = Color(0xFF08090A),
                    focusedBorderColor = Color(0xFF79BFFF), unfocusedBorderColor = Color(0xFF34363B),
                    focusedLabelColor = Color(0xFF79BFFF), unfocusedLabelColor = Color(0xFFA5ACBB),
                    cursorColor = Color(0xFF79BFFF)
                )
            )
        }
    }, confirmButton = { TextButton(onClick = {
        val english = newEnglish.trim()
        if (english.isNotEmpty() && current != null) {
            val array = current.json.optJSONArray(editType) ?: JSONArray().also { current.json.put(editType, it) }
            val duplicate = (0 until array.length()).any { array.optJSONObject(it)?.optString("en", "")?.equals(english, true) == true }
            if (!duplicate) { array.put(JSONObject().put("en", english).put("bn", newBangla.trim())); repository.save(current); revision++ }
            else Toast.makeText(context, "That word is already in this list", Toast.LENGTH_SHORT).show()
            showAddDialog = false
        }
    }) { Text("Add") } }, dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } })

    pendingDelete?.let { (type, index) -> AlertDialog(
        onDismissRequest = { pendingDelete = null },
        containerColor = Color(0xFF101114),
        titleContentColor = Color(0xFFF5F6FA),
        textContentColor = Color(0xFFD6DAE3),
        shape = RoundedCornerShape(26.dp),
        title = { Text("Remove this word?", color = Color(0xFFF5F6FA)) },
        text = { Text("This removes only the selected ${if (type == "synonyms") "synonym" else "antonym"} from your local copy.", color = Color(0xFFD6DAE3)) },
        confirmButton = { TextButton(onClick = {
        current?.let { record -> val old = record.json.optJSONArray(type) ?: JSONArray(); val replacement = JSONArray(); for (i in 0 until old.length()) if (i != index) replacement.put(old.get(i)); record.json.put(type, replacement); repository.save(record); revision++ }
        pendingDelete = null
    }) { Text("Remove", color = Color(0xFFB42318)) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }) }
}

@Composable
private fun HomeScreen(onChoose: (String) -> Unit, onBookmarks: () -> Unit, onSettings: () -> Unit, surface: Color) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text("Word", color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp)
            Text("Smart", color = Color(0xFF55C7F2), fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)
        }
        Text("Most words you need", color = Muted, fontSize = 17.sp, letterSpacing = .2.sp)
        Spacer(Modifier.height(54.dp))
        CollectionCard("Word Smart 1", "Open collection", "01", onClick = { onChoose("word_smart_1") })
        Spacer(Modifier.height(22.dp))
        CollectionCard("Word Smart 2", "Open collection", "02", onClick = { onChoose("word_smart_2") })
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun HomeBottomBar(onBookmarks: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF080A0E)).navigationBarsPadding().padding(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onBookmarks).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BookmarkGlyph(true, 28)
            Text("Bookmarks", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Column(Modifier.weight(1f).clickable(onClick = onSettings).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚙", color = Ink, fontSize = 27.sp, lineHeight = 30.sp)
            Text("Settings", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CollectionCard(title: String, action: String, number: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(34.dp), color = Color(0xFF202328), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF464B54)), shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 156.dp).background(Brush.verticalGradient(listOf(Color(0xFF34383F), Color(0xFF1D2025), Color(0xFF292D33))), RoundedCornerShape(34.dp)).padding(horizontal = 22.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(94.dp), contentAlignment = Alignment.Center) {
                Text(number, fontSize = 76.sp, lineHeight = 80.sp, color = Color(0xFF9BBDF7), fontWeight = FontWeight.Bold)
            }
            Box(Modifier.width(1.dp).height(92.dp).background(Color(0xFF4B505A)))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = Ink, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(action, color = Muted, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("→", color = Color(0xFF9BBDF7), fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
private fun GroupsScreen(repository: WordRepository, collection: String, onChoose: (Int) -> Unit, onChooseWord: (WordRecord) -> Unit) {
    var query by remember(collection) { mutableStateOf("") }
    val groups = remember(collection) { repository.groupSummaries(collection) }
    val matchingWords = remember(query, collection) {
        if (query.isBlank()) emptyList() else repository.allWords().filter { record ->
            record.file.parentFile?.name == collection && record.word.contains(query.trim(), ignoreCase = true)
        }.take(200)
    }
    val filtered = groups.filter { summary -> query.isBlank() && summary.group > 0 }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search words...", color = Muted) },
            leadingIcon = { Text("⌕", color = Blue, fontSize = 27.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Color(0xFF5B5E67), focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Blue)
        )
        if (query.isNotBlank()) {
            if (matchingWords.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("No matching words", color = Muted) }
            else LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                itemsIndexed(matchingWords, key = { _, record -> "${record.file.name}:${record.index}" }) { _, record ->
                    Surface(onClick = { onChooseWord(record) }, shape = RoundedCornerShape(20.dp), color = SurfaceColor, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(record.word, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Group ${record.file.nameWithoutExtension.takeLast(2).toIntOrNull() ?: "?"} · ${if (collection == "word_smart_1") "Wordsmart 1" else "Wordsmart 2"}", color = Muted, fontSize = 13.sp)
                                Text(record.meaningsBangla, color = Green, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("›", color = Blue, fontSize = 28.sp)
                        }
                    }
                }
            }
        } else LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            itemsIndexed(filtered, key = { _, item -> item.group }) { _, item ->
                val group = item.group
                val count = item.count
                val first = item.firstWord
                val last = item.lastWord
                Surface(onClick = { onChoose(group) }, shape = RoundedCornerShape(22.dp), color = Color(0xFF202328), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF414650)), shadowElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF30343A), Color(0xFF1B1E23))), RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(54.dp), contentAlignment = Alignment.Center) { Text("$group", color = Color(0xFF9BBDF7), fontSize = 29.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Group $group", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(9.dp))
                                Text("($count words)", color = Muted, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text("$first  →  $last", color = Color(0xFFB5BDE0), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("›", color = Ink, fontSize = 30.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WordsScreen(words: List<WordRecord>, group: Int, bookmarkedOnly: Boolean, onToggleBookmarks: () -> Unit, onChoose: (Int) -> Unit, isBookmarked: (WordRecord) -> Boolean, listState: LazyListState) {
    if (words.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (bookmarkedOnly) "No bookmarks yet." else "No words found in this group.", color = Muted)
        }
    } else LazyColumn(state = listState, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(words, key = { _, record -> "${record.file.name}:${record.index}" }, contentType = { _, _ -> "word" }) { index, record ->
            Surface(onClick = { onChoose(index) }, shape = RoundedCornerShape(23.dp), color = Color(0xFF08090B), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24262A)), shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Color(0xFF11192B), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("${record.number}", color = if (isBookmarked(record)) Color(0xFFFF8A24) else Color(0xFF79BFFF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.word, color = Color(0xFFF7F7FA), fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val pronunciation = record.pronunciationDisplay
                            if (pronunciation.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text("($pronunciation)", color = Color(0xFFE4E5EA), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (record.meaningsBangla.isNotBlank()) {
                            Text(record.meaningsBangla, color = Color(0xFF8EE7B0), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (record.synonymPreview.isNotBlank()) {
                            Text("Syn: ${record.synonymPreview}", color = Color(0xFFB9C3D7), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private data class ExampleItem(val english: String, val bangla: String)

private fun normalizeExample(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()

private fun splitExample(value: String): ExampleItem {
    val separators = listOf(" — ", " – ", " - ", "\n")
    val separator = separators.firstOrNull { value.contains(it) } ?: return ExampleItem(value.trim(), "")
    val parts = value.split(separator, limit = 2)
    return ExampleItem(parts[0].trim(), parts.getOrElse(1) { "" }.trim())
}

private fun collectUniqueExamples(json: JSONObject): List<ExampleItem> {
    val result = mutableListOf<ExampleItem>()
    fun addUnique(item: ExampleItem) {
        val english = item.english.trim()
        val bangla = item.bangla.trim()
        if (english.isBlank() && bangla.isBlank()) return
        val duplicateIndex = result.indexOfFirst { existing ->
            english.isNotBlank() && existing.english.isNotBlank() && normalizeExample(english) == normalizeExample(existing.english)
        }
        if (duplicateIndex < 0) result.add(ExampleItem(english, bangla))
        else if (result[duplicateIndex].bangla.isBlank() && bangla.isNotBlank()) result[duplicateIndex] = ExampleItem(english, bangla)
    }
    addUnique(ExampleItem(json.optString("example", ""), json.optString("example_bn", "")))
    val examples = json.optJSONArray("examples")
    if (examples != null) for (i in 0 until examples.length()) {
        val entry = examples.opt(i)
        val item = when (entry) {
            is JSONObject -> ExampleItem(entry.optString("en", entry.optString("example", "")), entry.optString("bn", entry.optString("meaning_bn", "")))
            is String -> splitExample(entry.trim())
            else -> ExampleItem("", "")
        }
        addUnique(item)
    }
    return result
}

@Composable
private fun WordScreen(
    record: WordRecord,
    position: Int,
    total: Int,
    group: Int,
    collectionTitle: String,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAdd: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String, Int) -> Unit
) {
    val context = LocalContext.current
    var dragAmount by remember(record.file.absolutePath, record.index) { mutableFloatStateOf(0f) }
    var ttsReady by remember { mutableStateOf(false) }
    val tts = remember(context) {
        TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }
    DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }
    val wordScrollState = rememberScrollState()
    LaunchedEffect(record.file.absolutePath, record.index) { wordScrollState.scrollTo(0) }
    Column(Modifier.fillMaxSize().pointerInput(record.file.absolutePath, record.index) {
        detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> dragAmount += amount }, onDragEnd = {
            if (dragAmount < -65f) onNext() else if (dragAmount > 65f) onPrevious()
            dragAmount = 0f
        }, onDragCancel = { dragAmount = 0f })
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(wordScrollState).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF08090A), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242528))) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    val pos = record.posText
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Keep the vocabulary headword on one line. The old row let the
                        // POS label and bookmark squeeze the title into multiple lines.
                        val titleSize = when {
                            record.word.length <= 9 -> 34.sp
                            record.word.length <= 13 -> 30.sp
                            record.word.length <= 17 -> 26.sp
                            else -> 22.sp
                        }
                        Text(
                            text = record.word,
                            modifier = Modifier.weight(1f),
                            fontSize = titleSize,
                            lineHeight = titleSize,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Surface(
                            onClick = onToggleBookmark,
                            shape = CircleShape,
                            color = Color(0xFF171719),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF303136))
                        ) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                BookmarkGlyph(isBookmarked, 26)
                            }
                        }
                    }
                    if (pos.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text("($pos)", color = Blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(onClick = {
                            if (ttsReady) {
                                tts.language = Locale.US
                                tts.speak(record.word, TextToSpeech.QUEUE_FLUSH, null, "word-${record.index}")
                            } else Toast.makeText(context, "Pronunciation is loading", Toast.LENGTH_SHORT).show()
                        }, shape = CircleShape, color = Color(0xFF182431), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38536A))) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("◖))", color = Blue, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                        }
                        Text(record.pronunciationText, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = Color(0xFF292A2D))
                    Spacer(Modifier.height(22.dp))
                    Text("MEANING:", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .4.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(record.meaningEnglish, color = Color(0xFFE2E3E7), fontSize = 17.sp, lineHeight = 25.sp)
                    if (record.meaningsBangla.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text("বাংলা অর্থ:", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(6.dp))
                        Text(record.meaningsBangla, color = Green, fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            WordListCard("Synonyms", "synonyms", record.json.optJSONArray("synonyms"), Color(0xFFC084FC), onEdit, onDelete)
            WordListCard("Antonyms", "antonyms", record.json.optJSONArray("antonyms"), Color(0xFFC084FC), onEdit, onDelete)

            val exampleItems = record.examples
            if (exampleItems.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF08090A), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242528))) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text("EXAMPLES", color = Color(0xFF9BA8BD), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Spacer(Modifier.height(16.dp))
                        exampleItems.forEachIndexed { index, item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (item.english.isNotBlank()) Text(item.english, color = Color(0xFFF4F4F5), fontSize = 18.sp, lineHeight = 28.sp)
                                if (item.bangla.isNotBlank()) Text(item.bangla, color = Color(0xFF9CC8FF), fontSize = 17.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
                            }
                            if (index < exampleItems.lastIndex) Spacer(Modifier.height(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPrevious, enabled = position > 0, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCED5E1))) { Text("‹  Previous", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Button(onClick = onNext, enabled = position < total - 1, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF182431), contentColor = Blue)) { Text("Next  ›", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun WordListCard(title: String, type: String, array: JSONArray?, accent: Color, onEdit: (String) -> Unit, onDelete: (String, Int) -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF08090A), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242528))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(Color(0xFF182431), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text(if (type == "synonyms") "⛓" else "↝", color = Blue, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onEdit(type) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD4D7DE))) {
                    Text("✎ Edit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (array == null || array.length() == 0) Text("No ${title.lowercase()} yet.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            else {
                val count = array.length()
                val rows = (count + 1) / 2
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (rowIndex in 0 until rows) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            for (columnIndex in 0..1) {
                                val i = rowIndex * 2 + columnIndex
                                if (i < count) {
                                    val item = array.optJSONObject(i) ?: JSONObject()
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                                        Box(Modifier.padding(top = 8.dp).size(8.dp).background(accent, CircleShape))
                                        Spacer(Modifier.width(9.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.optString("en", ""), color = Color(0xFFF4F4F5), fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
                                            val bangla = item.optString("bn", "")
                                            if (bangla.isNotBlank()) Text("($bangla)", color = Color(0xFF9CC8FF), fontSize = 14.sp, lineHeight = 20.sp)
                                        }
                                    }
                                } else Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWordsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("No words found in this group", color = Ink, fontWeight = FontWeight.Bold)
        TextButton(onClick = onBack) { Text("Back to groups") }
    }
}

@Composable
private fun SettingsScreen(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit, animationStyle: String, onAnimationChange: (String) -> Unit, onExport: () -> Unit, onImport: () -> Unit, importBusy: Boolean, surface: Color) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("APPEARANCE", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Surface(shape = RoundedCornerShape(18.dp), color = surface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Dark theme", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("Dark theme is enabled by default", color = Muted, fontSize = 12.sp) }
                Switch(checked = darkTheme, onCheckedChange = onThemeChange)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("NAVIGATION ANIMATION", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Surface(shape = RoundedCornerShape(18.dp), color = surface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose how screens transition", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                listOf("None", "Fade", "Slide", "Scale").forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { onAnimationChange(option) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = animationStyle == option, onClick = { onAnimationChange(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(option, color = Ink, fontSize = 15.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("DATA BACKUP", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Surface(shape = RoundedCornerShape(18.dp), color = surface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Export or restore your complete Wordsmart data. Import accepts only a ZIP containing all 68 JSON files in the expected Wordsmart folder structure.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text("Export words.zip") }
                OutlinedButton(onClick = onImport, enabled = !importBusy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) { Text(if (importBusy) "Importing…" else "Import Wordsmart ZIP") }
            }
        }
    }
}

@Composable
private fun BookmarksScreen(
    records: List<WordRecord>,
    listState: LazyListState,
    onChoose: (WordRecord) -> Unit
) {
    data class GroupKey(val collection: String, val group: Int)
    val grouped = remember(records) {
        records.groupBy { record -> GroupKey(record.file.parentFile?.name ?: "word_smart_1", record.file.nameWithoutExtension.takeLast(2).toIntOrNull() ?: 1) }
            .entries.sortedWith(compareBy({ it.key.collection }, { it.key.group }))
    }
    if (grouped.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BookmarkGlyph(true, 48)
            Spacer(Modifier.height(12.dp))
            Text("No bookmarks yet", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Open a word and tap its bookmark icon to save it here.", color = Muted)
        }
    } else LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        grouped.forEach { entry ->
            val collectionTitle = if (entry.key.collection == "word_smart_1") "Wordsmart 1" else "Wordsmart 2"
            item(key = "header:${entry.key.collection}#${entry.key.group}", contentType = "bookmark-header") {
                Surface(color = Color(0xFF153B2B), shape = RoundedCornerShape(10.dp)) {
                    Text(
                        "Group ${entry.key.group}, $collectionTitle",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                        color = Color(0xFF8EE7B0), fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            itemsIndexed(entry.value.sortedBy { it.index }, key = { _, record -> "${entry.key.collection}#${entry.key.group}:${record.index}" }, contentType = { _, _ -> "bookmark-word" }) { _, record ->
                Surface(
                    onClick = { onChoose(record) },
                    shape = RoundedCornerShape(23.dp),
                    color = Color(0xFF08090B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24262A)),
                    shadowElevation = 2.dp
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Color(0xFF11192B), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text("${record.number}", color = Color(0xFF79BFFF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(record.word, color = Color(0xFFF7F7FA), fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val pronunciation = record.pronunciationDisplay
                                if (pronunciation.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("($pronunciation)", color = Color(0xFFE4E5EA), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (record.meaningsBangla.isNotBlank()) {
                                Text(record.meaningsBangla, color = Color(0xFF8EE7B0), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (record.synonymPreview.isNotBlank()) {
                                Text("Syn: ${record.synonymPreview}", color = Color(0xFFB9C3D7), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(7.dp))
                        BookmarkGlyph(true, 23)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkWordsScreen(records: List<WordRecord>, group: Int, onChoose: (WordRecord) -> Unit, isBookmarked: (WordRecord) -> Boolean, listState: LazyListState) {
    WordsScreen(
        words = records,
        group = group,
        bookmarkedOnly = true,
        onToggleBookmarks = {},
        onChoose = { index -> records.getOrNull(index)?.let(onChoose) },
        isBookmarked = isBookmarked,
        listState = listState
    )
}

@Composable
private fun BookmarkGlyph(bookmarked: Boolean, size: Int = 24) {
    val gradient = Brush.horizontalGradient(listOf(Color(0xFFFFA018), Color(0xFFFF3D25)))
    androidx.compose.foundation.Canvas(Modifier.size((size * 1.0f).dp)) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.22f, h * 0.08f)
            quadraticTo(w * 0.12f, h * 0.08f, w * 0.12f, h * 0.20f)
            lineTo(w * 0.12f, h * 0.90f)
            lineTo(w * 0.50f, h * 0.66f)
            lineTo(w * 0.88f, h * 0.90f)
            lineTo(w * 0.88f, h * 0.20f)
            quadraticTo(w * 0.88f, h * 0.08f, w * 0.78f, h * 0.08f)
            close()
        }
        if (bookmarked) {
            drawPath(path, brush = gradient)
            drawPath(path, color = Color(0xFF08090B), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.10f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else {
            drawPath(path, color = Color.Transparent)
            drawPath(path, brush = gradient, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

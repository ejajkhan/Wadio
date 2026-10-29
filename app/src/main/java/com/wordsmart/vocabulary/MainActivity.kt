package com.wordsmart.vocabulary

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
        repository.prepare()
        setContent { WordsmartApp(repository) }
    }
}

private class WordRepository(private val context: Context) {
    val root: File = File(context.filesDir, "words")
    private val collections = listOf("word_smart_1", "word_smart_2")

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

    fun words(collection: String, group: Int): List<WordRecord> {
        val file = groupFiles(collection).firstOrNull { it.nameWithoutExtension.endsWith("%02d".format(group)) } ?: return emptyList()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).map { WordRecord(file, it, array.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun allWords(): List<WordRecord> = listOf("word_smart_1", "word_smart_2").flatMap { collection ->
        groupFiles(collection).flatMap { file -> try { val array = JSONArray(file.readText(Charsets.UTF_8)); (0 until array.length()).map { WordRecord(file, it, array.getJSONObject(it)) } } catch (_: Exception) { emptyList() } }
    }

    fun count(collection: String, group: Int): Int = words(collection, group).size

    fun save(record: WordRecord) {
        val array = JSONArray(record.file.readText(Charsets.UTF_8))
        array.put(record.index, record.json)
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

private data class WordRecord(val file: File, val index: Int, val json: JSONObject) {
    val word: String get() = json.optString("word", "Untitled")
    val number: Int get() = json.optInt("no", index + 1)
}

private enum class Page { HOME, GROUPS, WORDS, WORD, SETTINGS, BOOKMARKS }

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

    fun persistLocation() {
        prefs.edit().putString("last_page", page.name).putString("last_collection", collection)
            .putInt("last_group", selectedGroup).putInt("last_word_index", wordIndex).apply()
    }
    fun go(to: Page) { if (page == Page.SETTINGS) previousPage = to; page = to; persistLocation() }
    fun goBack() {
        page = when (page) {
            Page.WORD -> Page.WORDS
            Page.WORDS -> Page.GROUPS
            Page.GROUPS -> Page.HOME
            Page.SETTINGS -> previousPage
            Page.BOOKMARKS -> Page.HOME
            Page.HOME -> Page.HOME
        }
        persistLocation()
    }
    BackHandler(enabled = page != Page.HOME) { goBack() }

    val groupWords = remember(collection, selectedGroup, revision) { repository.words(collection, selectedGroup) }
    if (wordIndex >= groupWords.size && groupWords.isNotEmpty()) wordIndex = groupWords.lastIndex
    val current = groupWords.getOrNull(wordIndex)
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
                            Text("${wordIndex + 1}/${groupWords.size}", fontSize = 14.sp, color = Muted, fontWeight = FontWeight.Medium)
                            Text("•", color = Blue, fontSize = 14.sp)
                            Text("Group $selectedGroup", fontSize = 14.sp, color = Muted, maxLines = 1)
                            Text("•", color = Blue, fontSize = 14.sp)
                            Text(collectionTitle, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Column {
                            Text(when (page) { Page.GROUPS -> collectionTitle; Page.WORDS -> "Group $selectedGroup"; Page.SETTINGS -> "Settings"; Page.BOOKMARKS -> "Bookmarks"; Page.HOME, Page.WORD -> "" }, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (page != Page.SETTINGS && page != Page.BOOKMARKS) Text(when (page) {
                                Page.GROUPS -> "${repository.groupFiles(collection).size} groups  ·  ${repository.groupFiles(collection).sumOf { file -> runCatching { JSONArray(file.readText(Charsets.UTF_8)).length() }.getOrDefault(0) }} words"
                                Page.WORDS -> "$collectionTitle  ·  ${repository.words(collection, selectedGroup).size} words"
                                else -> ""
                            }, fontSize = 13.sp, color = Muted)
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = { goBack() }) { Text("←", fontSize = 27.sp, color = Blue) } },
                actions = {
                    if (page == Page.WORDS) TextButton(onClick = { go(Page.BOOKMARKS) }) { Text("▱ Bookmarks", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        }, bottomBar = {
            if (page == Page.HOME) HomeBottomBar(onBookmarks = { go(Page.BOOKMARKS) }, onSettings = { previousPage = Page.HOME; go(Page.SETTINGS) })
        }) { padding ->
            AnimatedContent(targetState = page, modifier = Modifier.padding(padding).fillMaxSize(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "page") { shownPage ->
                when (shownPage) {
                    Page.HOME -> HomeScreen(onChoose = { chosen -> collection = chosen; selectedGroup = 1; wordIndex = 0; go(Page.GROUPS) }, onBookmarks = { go(Page.BOOKMARKS) }, onSettings = { previousPage = Page.HOME; go(Page.SETTINGS) }, surface = SurfaceColor)
                    Page.GROUPS -> GroupsScreen(repository, collection, onChoose = { group -> selectedGroup = group; wordIndex = 0; bookmarkedOnly = false; go(Page.WORDS) })
                    Page.WORDS -> {
                        val words = repository.words(collection, selectedGroup)
                        val filtered = if (bookmarkedOnly) words.filter { bookmarkKey(it) in bookmarks } else words
                        WordsScreen(filtered, selectedGroup, bookmarkedOnly, onToggleBookmarks = { bookmarkedOnly = !bookmarkedOnly }, onChoose = { filteredIndex ->
                            val selected = filtered.getOrNull(filteredIndex)
                            if (selected != null) { wordIndex = repository.words(collection, selectedGroup).indexOfFirst { it.index == selected.index }; go(Page.WORD) }
                        }, isBookmarked = { bookmarkKey(it) in bookmarks })
                    }
                    Page.WORD -> if (current == null) EmptyWordsScreen(onBack = { go(Page.WORDS) }) else WordScreen(
                        record = current, position = wordIndex, total = groupWords.size,
                        group = selectedGroup, collectionTitle = collectionTitle,
                        isBookmarked = bookmarkKey(current) in bookmarks,
                        onToggleBookmark = { toggleBookmark(current) },
                        onPrevious = { if (wordIndex > 0) { wordIndex--; persistLocation() } },
                        onNext = { if (wordIndex < groupWords.lastIndex) { wordIndex++; persistLocation() } },
                        onAdd = { type -> editType = type; newEnglish = ""; newBangla = ""; showAddDialog = true },
                        onEdit = { type -> editType = type; showEditDialog = true },
                        onDelete = { type, index -> pendingDelete = type to index })
                    Page.SETTINGS -> SettingsScreen(darkTheme, onThemeChange = { darkTheme = it; prefs.edit().putBoolean("dark_theme", it).apply() }, onExport = { exportLauncher.launch("words.zip") }, onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, importBusy = importBusy, surface = SurfaceColor)
                    Page.BOOKMARKS -> {
                        val all = remember(revision, bookmarks.toSet()) { repository.allWords().filter { bookmarkKey(it) in bookmarks } }
                        BookmarksScreen(all, onChoose = { record -> collection = record.file.parentFile?.name ?: "word_smart_1"; selectedGroup = record.file.nameWithoutExtension.takeLast(2).toIntOrNull() ?: 1; wordIndex = record.index; go(Page.WORD) }, keyOf = { bookmarkKey(it) })
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
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showEditDialog = false; newEnglish = ""; newBangla = ""; showAddDialog = true }) { Text("+ Add word", color = Blue, fontWeight = FontWeight.Bold) }
                    if (items == null || items.length() == 0) Text("No words added yet.", color = Muted)
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

    if (showAddDialog) AlertDialog(onDismissRequest = { showAddDialog = false }, title = { Text(if (editType == "synonyms") "Add synonym" else "Add antonym", fontWeight = FontWeight.Bold) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = newEnglish, onValueChange = { newEnglish = it }, label = { Text("English word") }, singleLine = true)
            OutlinedTextField(value = newBangla, onValueChange = { newBangla = it }, label = { Text("Bangla meaning (optional)") }, singleLine = true)
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

    pendingDelete?.let { (type, index) -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Remove this word?") }, text = { Text("This removes only the selected ${if (type == "synonyms") "synonym" else "antonym"} from your local copy.") }, confirmButton = { TextButton(onClick = {
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
            Text("♧", color = Blue, fontSize = 28.sp, lineHeight = 30.sp)
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
private fun GroupsScreen(repository: WordRepository, collection: String, onChoose: (Int) -> Unit) {
    var query by remember(collection) { mutableStateOf("") }
    val groups = remember(collection) {
        (1..34).map { group ->
            val words = repository.words(collection, group)
            Triple(group, words.size, words)
        }
    }
    val filtered = groups.filter { (group, _, words) ->
        query.isBlank() || "Group $group".contains(query, true) || words.any { it.word.contains(query, true) }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search words...", color = Muted) },
            leadingIcon = { Text("⌕", color = Blue, fontSize = 27.sp) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Color(0xFF5B5E67), focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Blue)
        )
        LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            itemsIndexed(filtered, key = { _, item -> item.first }) { _, item ->
                val (group, count, words) = item
                val first = words.firstOrNull()?.word ?: "No words"
                val last = words.lastOrNull()?.word ?: ""
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
private fun WordsScreen(words: List<WordRecord>, group: Int, bookmarkedOnly: Boolean, onToggleBookmarks: () -> Unit, onChoose: (Int) -> Unit, isBookmarked: (WordRecord) -> Boolean) {
    if (words.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (bookmarkedOnly) "No bookmarks yet." else "No words found in this group.", color = Muted)
        }
    } else LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(words, key = { _, record -> "${record.file.name}:${record.index}" }) { index, record ->
            Surface(onClick = { onChoose(index) }, shape = RoundedCornerShape(23.dp), color = Color(0xFF08090B), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF24262A)), shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Color(0xFF11192B), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("${record.number}", color = Color(0xFF79BFFF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(record.word, color = Color(0xFFF7F7FA), fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val pronunciation = record.json.optString("pronunciation_bn", "")
                            if (pronunciation.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                Text("($pronunciation)", color = Color(0xFFE4E5EA), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val meanings = record.json.optJSONArray("meaning_bn")
                        if (meanings != null && meanings.length() > 0) {
                            Text((0 until meanings.length()).joinToString("; ") { meanings.optString(it) }, color = Color(0xFF8EE7B0), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val synonyms = record.json.optJSONArray("synonyms")
                        if (synonyms != null && synonyms.length() > 0) {
                            val preview = (0 until synonyms.length()).mapNotNull { i ->
                                val obj = synonyms.optJSONObject(i) ?: return@mapNotNull null
                                val en = obj.optString("en", "").trim()
                                if (en.isBlank()) null else en + obj.optString("bn", "").takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
                            }.joinToString(", ")
                            if (preview.isNotBlank()) Text("Syn: $preview", color = Color(0xFFB9C3D7), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(if (isBookmarked(record)) "▮" else "♧", color = if (isBookmarked(record)) Color(0xFF79BFFF) else Color(0xFF9AA5B8), fontSize = 23.sp)
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
    Column(Modifier.fillMaxSize().pointerInput(record.file.absolutePath, record.index) {
        detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> dragAmount += amount }, onDragEnd = {
            if (dragAmount < -65f) onNext() else if (dragAmount > 65f) onPrevious()
            dragAmount = 0f
        }, onDragCancel = { dragAmount = 0f })
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF08090A), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF242528))) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pos = record.json.optJSONArray("pos")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
                        Text(record.word, modifier = Modifier.weight(1f, fill = false), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        if (pos.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text("(${pos})", color = Blue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(onClick = onToggleBookmark, shape = CircleShape, color = Color(0xFF171719), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF303136))) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text(if (isBookmarked) "▮" else "♧", color = Blue, fontSize = 26.sp) }
                        }
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
                        val bnPronunciation = record.json.optString("pronunciation_bn", "")
                        Text(if (bnPronunciation.isNotBlank()) bnPronunciation else record.json.optString("pronunciation", ""), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = Color(0xFF292A2D))
                    Spacer(Modifier.height(22.dp))
                    Text("MEANING:", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .4.sp)
                    Spacer(Modifier.height(7.dp))
                    Text(record.json.optString("meaning_en", "No English meaning"), color = Color(0xFFE2E3E7), fontSize = 17.sp, lineHeight = 25.sp)
                    val meanings = record.json.optJSONArray("meaning_bn")
                    if (meanings != null && meanings.length() > 0) {
                        Spacer(Modifier.height(16.dp))
                        Text("বাংলা অর্থ:", color = Blue, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(6.dp))
                        Text((0 until meanings.length()).joinToString("; ") { meanings.optString(it) }, color = Green, fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            WordListCard("Synonyms", "synonyms", record.json.optJSONArray("synonyms"), Color(0xFFC084FC), onEdit, onDelete)
            WordListCard("Antonyms", "antonyms", record.json.optJSONArray("antonyms"), Color(0xFFC084FC), onEdit, onDelete)

            val exampleItems = collectUniqueExamples(record.json)
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
private fun SettingsScreen(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit, onExport: () -> Unit, onImport: () -> Unit, importBusy: Boolean, surface: Color) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("APPEARANCE", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Surface(shape = RoundedCornerShape(18.dp), color = surface, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Dark theme", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("Dark theme is enabled by default", color = Muted, fontSize = 12.sp) }
                Switch(checked = darkTheme, onCheckedChange = onThemeChange)
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
private fun BookmarksScreen(records: List<WordRecord>, onChoose: (WordRecord) -> Unit, keyOf: (WordRecord) -> String) {
    if (records.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("No bookmarks yet", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text("Open a word and tap ☆ Bookmark to save it here.", color = Muted) }
    } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        itemsIndexed(records, key = { _, record -> keyOf(record) }) { _, record ->
            Surface(onClick = { onChoose(record) }, shape = RoundedCornerShape(17.dp), color = SurfaceColor, border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(record.word, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("${if (record.file.parentFile?.name == "word_smart_1") "Wordsmart 1" else "Wordsmart 2"} · Group ${record.file.nameWithoutExtension.takeLast(2).toIntOrNull() ?: "?"}", color = Muted, fontSize = 12.sp) }
                    Text("★", color = Blue, fontSize = 22.sp)
                }
            }
        }
    }
}

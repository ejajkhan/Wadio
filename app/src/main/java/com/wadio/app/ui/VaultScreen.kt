package com.wadio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wadio.app.ActiveTab
import com.wadio.app.MediaSortMode
import com.wadio.app.WadioController
import com.wadio.app.data.MediaRecord
import com.wadio.app.data.AlbumRecord
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(controller: WadioController, onPickFiles: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val baseDir = File(context.filesDir, "vault")
    var albumToDelete by remember { mutableStateOf<AlbumRecord?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (state.showAlbumList) "Albums" else if (state.tab == ActiveTab.Pictures) "Photos" else "Videos") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (state.showAlbumList) controller.showMediaChooser() else controller.showAlbumList()
                        }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = if (state.showAlbumList) "Back to chooser" else "Back to albums")
                        }
                    },
                    actions = {
                        if (state.showAlbumList) {
                            IconButton(onClick = { controller.openNewAlbumModal() }) {
                                Icon(Icons.Rounded.CreateNewFolder, "New album")
                            }
                        } else {
                            IconButton(onClick = onPickFiles) { Icon(Icons.Rounded.Add, "Add photos or videos") }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.showAlbumList) {
                Text("Open an album, create a new one, or delete an album.",
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.cachedAlbums, key = { it.id }) { album ->
                        val cover = state.albumCovers[album.id]
                        val model = cover?.let { File(baseDir, it.relativePath) } ?: Color.Gray
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                AsyncImage(
                                    model = model,
                                    contentDescription = album.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(112.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable { controller.selectAlbum(album.id) },
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(album.name, modifier = Modifier.weight(1f).clickable { controller.selectAlbum(album.id) },
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall)
                                    if (album.id != "default" && album.id != "default-videos") {
                                        IconButton(onClick = { albumToDelete = album }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Delete ${album.name}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${state.cachedMedia.size} items", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    SortPopup(
                        mode = state.sortMode,
                        ascending = state.sortAscending,
                        onMode = controller::setSortMode,
                        onDirection = controller::toggleSortDirection,
                    )
                }

                if (state.importNotice != null) {
                    Text(state.importNotice!!, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall)
                }

                if (state.selectionMode) {
                    SelectionBar(controller, state.cachedAlbums.map { it.id to it.name })
                }

                if (state.cachedMedia.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (state.tab == ActiveTab.Pictures) "No photos in this album yet. Tap + to add." else "No videos in this album yet. Tap + to add.")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.cachedMedia, key = { it.id }) { record ->
                            MediaTile(controller, record, baseDir)
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (albumToDelete != null) {
        val album = albumToDelete!!
        AlertDialog(
            onDismissRequest = { albumToDelete = null },
            title = { Text("Delete album?") },
            text = { Text("Delete \"${album.name}\" and all media inside it? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { controller.deleteAlbum(album.id); albumToDelete = null }) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { albumToDelete = null }) { Text("Cancel") } },
        )
    }
    if (state.showNewAlbumModal) {
        AlertDialog(
            onDismissRequest = { controller.dismissNewAlbumModal() },
            title = { Text("New Album") },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.newAlbumInput,
                        onValueChange = { controller.updateNewAlbumInput(it) },
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    if (state.albumError != null) Text(state.albumError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.createNewAlbum() }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { controller.dismissNewAlbumModal() }) { Text("Cancel") }
            }
        )
    }

    if (state.confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { controller.cancelDeleteSelected() },
            title = { Text("Delete ${state.selectedMediaIds.size} selected item(s)?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { controller.performDeleteSelected() }) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { controller.cancelDeleteSelected() }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SortPopup(
    mode: MediaSortMode,
    ascending: Boolean,
    onMode: (MediaSortMode) -> Unit,
    onDirection: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.Sort, contentDescription = "Sort media")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Name${if (mode == MediaSortMode.Name) " ✓" else ""}") },
                onClick = { onMode(MediaSortMode.Name); expanded = false },
                leadingIcon = { Icon(Icons.Rounded.SortByAlpha, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Size${if (mode == MediaSortMode.Size) " ✓" else ""}") },
                onClick = { onMode(MediaSortMode.Size); expanded = false },
                leadingIcon = { Icon(Icons.Rounded.DataUsage, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Date created${if (mode == MediaSortMode.DateCreated) " ✓" else ""}") },
                onClick = { onMode(MediaSortMode.DateCreated); expanded = false },
                leadingIcon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (ascending) "Ascending" else "Descending") },
                onClick = { onDirection(); expanded = false },
                leadingIcon = { Icon(if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SelectionBar(controller: WadioController, albums: List<Pair<String, String>>) {
    val state by controller.state.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${state.selectedMediaIds.size} selected")
        DropdownMenuWrapper(
            label = albums.firstOrNull { it.first == state.selectionDestination }?.second
                ?: "Choose destination",
            options = albums.filter { it.first != state.selectedAlbum },
        ) { controller.updateSelectionDestination(it) }
        val can = state.selectionDestination.isNotEmpty() &&
            state.selectionDestination != state.selectedAlbum &&
            state.selectedMediaIds.isNotEmpty()
        TextButton(onClick = { controller.performCopyTo() }, enabled = can) { Text("Copy to") }
        TextButton(onClick = { controller.performMoveTo() }, enabled = can) { Text("Move to") }
        TextButton(onClick = { controller.requestDeleteSelected() },
            enabled = state.selectedMediaIds.isNotEmpty()) { Text("Delete") }
        TextButton(onClick = { controller.cancelSelection() }) { Text("Cancel") }
    }
}

@Composable
private fun <T> DropdownMenuWrapper(
    label: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}

@Composable
private fun MediaTile(controller: WadioController, record: MediaRecord, baseDir: File) {
    val state by controller.state.collectAsStateWithLifecycle()
    val selected = record.id in state.selectedMediaIds
    val context = LocalContext.current

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(record.id) {
                detectTapGestures(
                    onTap = { controller.onMediaTap(record) },
                    onPress = {
                        val released = withTimeoutOrNull(2000L) { tryAwaitRelease() }
                        if (released == null) {
                            controller.onLongPressSelect(record.id)
                        }
                    }
                )
            }
    ) {
        val file = File(baseDir, record.relativePath)
        AsyncImage(
            model = file,
            contentDescription = record.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (selected) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x6611AADD)))
            ))
            Icon(
                Icons.Rounded.CheckCircle, null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp),
            )
        }

        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(4.dp)
        ) {
            Text(record.fileName, color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

package com.wadio.app.ui

import androidx.compose.foundation.background
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
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(controller: WadioController, onPickFiles: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val baseDir = File(context.filesDir, "vault")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (state.tab == ActiveTab.Pictures) "Pictures" else "Videos") },
                    navigationIcon = {
                        TextButton(onClick = { controller.setTab(ActiveTab.Audio) }) { Text("Audio") }
                    },
                    actions = {
                        IconButton(onClick = onPickFiles) { Icon(Icons.Rounded.Add, "Import") }
                        IconButton(onClick = { controller.openNewAlbumModal() }) { Icon(Icons.Rounded.CreateNewFolder, "New album") }
                        TextButton(onClick = { controller.lockVault() }) { Text("Lock") }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // Albums strip
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.cachedAlbums.forEach { album ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(80.dp)
                            .pointerInput(album.id) {
                                detectTapGestures(onTap = { controller.selectAlbum(album.id) })
                            }
                    ) {
                        val cover = state.albumCovers[album.id]
                        val model = cover?.let { File(baseDir, it.relativePath) }
                            ?: Color.Gray
                        AsyncImage(
                            model = model,
                            contentDescription = album.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            album.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (album.id == state.selectedAlbum) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            // Sort
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sort by:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                DropdownMenuWrapper(
                    label = sortLabel(state.sortMode),
                    options = listOf(
                        MediaSortMode.Name to "Name",
                        MediaSortMode.Size to "Size",
                        MediaSortMode.DateCreated to "Date created",
                    ),
                ) { controller.setSortMode(it) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { controller.toggleSortDirection() }) {
                    Text(if (state.sortAscending) "Ascending ↑" else "Descending ↓")
                }
            }

            if (state.importNotice != null) {
                Text(state.importNotice!!, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }

            // Selection bar
            if (state.selectionMode) {
                SelectionBar(controller, state.cachedAlbums.map { it.id to it.name })
            } else {
                Text("Tip: hold a photo for 2 seconds to select multiple items.",
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.cachedMedia.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No media yet. Tap + to import.")
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

    // Dialogs
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
        TextButton(onClick = { controller.lockVault() }) { Text("Cancel") }
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

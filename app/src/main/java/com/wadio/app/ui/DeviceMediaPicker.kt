package com.wadio.app.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.wadio.app.ActiveTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DeviceMediaItem(
    val uri: Uri,
    val name: String,
    val folderPath: String,
    val folderName: String,
)

@Composable
fun DeviceMediaPicker(
    tab: ActiveTab,
    onDismiss: () -> Unit,
    onImport: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    var permissionAttempted by remember { mutableStateOf(false) }
    var permissionReady by remember { mutableStateOf(hasMediaPermission(context, tab)) }
    var permissionDenied by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retryQuery by remember { mutableIntStateOf(0) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var showCustomSelect by remember { mutableStateOf(false) }
    var customCount by remember { mutableStateOf("10") }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionReady = hasMediaPermission(context, tab)
        permissionDenied = !permissionReady
        permissionAttempted = true
    }

    LaunchedEffect(tab) {
        permissionReady = hasMediaPermission(context, tab)
        if (!permissionReady && !permissionAttempted) {
            val permissions = mediaPermissionsFor(tab)
            if (permissions.isNotEmpty()) permissionsLauncher.launch(permissions)
            else permissionAttempted = true
        }
    }

    LaunchedEffect(permissionReady, tab, retryQuery) {
        if (permissionReady) {
            loading = true
            loadError = null
            val result = withContext(Dispatchers.IO) { queryDeviceMedia(context, tab) }
            items = result.items
            loadError = result.error
            loading = false
        } else {
            items = emptyList()
            loadError = null
            loading = false
        }
    }

    val folders = remember(items) {
        items.groupBy { it.folderPath }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val visibleItems = remember(items, selectedFolder) {
        when (selectedFolder) {
            null -> emptyList()
            "__ALL_PHOTOS__" -> items
            else -> items.filter { it.folderPath == selectedFolder }
        }
    }
    val allVisibleSelected = visibleItems.isNotEmpty() && visibleItems.all { it.uri in selectedUris }
    val mediaNoun = if (tab == ActiveTab.Videos) "videos" else "photos"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (selectedFolder != null) {
                            selectedFolder = null
                            selectedUris = emptySet()
                        } else onDismiss()
                    }) {
                        Icon(
                            if (selectedFolder != null) Icons.Rounded.ArrowBack else Icons.Rounded.Close,
                            contentDescription = if (selectedFolder != null) "Back to folders" else "Close",
                        )
                    }
                    Text(
                        selectedFolder?.let { folders[it]?.firstOrNull()?.folderName ?: it.substringAfterLast('/') }
                            ?: "Choose folder",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selectedFolder != null) {
                        TextButton(
                            onClick = { showCustomSelect = true },
                            enabled = visibleItems.isNotEmpty(),
                        ) { Text("Custom select") }
                        TextButton(onClick = {
                            selectedUris = if (allVisibleSelected) selectedUris - visibleItems.map { it.uri }.toSet()
                                else selectedUris + visibleItems.map { it.uri }
                        }) { Text(if (allVisibleSelected) "Deselect all" else "Select all") }
                    }
                }
                HorizontalDivider()

                when {
                    !permissionReady -> {
                        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    if (permissionDenied) "Media access is needed to list device folders."
                                    else "Waiting for media access…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (permissionDenied) {
                                    Button(onClick = {
                                        permissionDenied = false
                                        permissionAttempted = false
                                        val permissions = mediaPermissionsFor(tab)
                                        if (permissions.isNotEmpty()) permissionsLauncher.launch(permissions)
                                    }) { Text("Grant access") }
                                }
                            }
                        }
                    }
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    items.isEmpty() -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                loadError ?: "No $mediaNoun found on this device.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (loadError != null) {
                                Button(onClick = {
                                    // Re-query without requiring the user to close and reopen the picker.
                                    permissionReady = hasMediaPermission(context, tab)
                                    if (permissionReady) {
                                        retryQuery++
                                    } else {
                                        permissionAttempted = false
                                        val permissions = mediaPermissionsFor(tab)
                                        if (permissions.isNotEmpty()) permissionsLauncher.launch(permissions)
                                    }
                                }) { Text("Try again") }
                            }
                        }
                    }
                    selectedFolder == null -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            item(key = "__all_photos__") {
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                        .clickable { selectedFolder = "__ALL_PHOTOS__"; selectedUris = emptySet() }
                                        .padding(horizontal = 12.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("All $mediaNoun", modifier = Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                    Text("${items.size}", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            folders.forEach { (path, folderItems) ->
                                item(key = path) {
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .clickable { selectedFolder = path; selectedUris = emptySet() }
                                            .padding(horizontal = 12.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                            Text(folderItems.first().folderName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("${folderItems.size}", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(visibleItems, key = { it.uri.toString() }) { item ->
                                val selected = item.uri in selectedUris
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)
                                        .clickable {
                                            selectedUris = if (selected) selectedUris - item.uri else selectedUris + item.uri
                                        },
                                ) {
                                    AsyncImage(
                                        model = item.uri,
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (tab == ActiveTab.Videos) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            modifier = Modifier.align(Alignment.Center)
                                                .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(50))
                                                .padding(4.dp),
                                        )
                                    }
                                    if (selected) {
                                        Box(
                                            Modifier.align(Alignment.TopEnd).padding(5.dp)
                                                .size(24.dp).clip(RoundedCornerShape(50))
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selectedUris.isEmpty()) "Select $mediaNoun to add" else "${selectedUris.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = selectedUris.isNotEmpty(),
                        onClick = {
                            val chosen = items.filter { it.uri in selectedUris }.map { it.uri }
                            onImport(chosen)
                            onDismiss()
                        },
                    ) { Text(if (tab == ActiveTab.Videos) "Add videos" else "Add photos") }
                }
            }
        }
    }
    if (showCustomSelect) {
        AlertDialog(
            onDismissRequest = { showCustomSelect = false },
            title = { Text("Select recent items") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Select the most recently added items from this folder. Newest items are listed first.")
                    OutlinedTextField(
                        value = customCount,
                        onValueChange = { customCount = it.filter(Char::isDigit).take(6) },
                        label = { Text("Number of items (X)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = customCount.toIntOrNull()?.coerceIn(1, visibleItems.size) ?: return@TextButton
                    selectedUris = selectedUris + visibleItems.take(count).map { it.uri }
                    showCustomSelect = false
                }, enabled = customCount.toIntOrNull() != null && visibleItems.isNotEmpty()) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { showCustomSelect = false }) { Text("Cancel") } },
        )
    }
}

private fun mediaPermissionsFor(tab: ActiveTab): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            if (tab == ActiveTab.Videos) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            if (tab == ActiveTab.Videos) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun hasMediaPermission(context: Context, tab: ActiveTab): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        if (tab == ActiveTab.Videos) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
    } else Manifest.permission.READ_EXTERNAL_STORAGE
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
    // Android 14's selected-photos permission grants access to selected images only.
    // It must never satisfy the video picker permission check, or the video folder
    // query is skipped and the picker appears to contain no folders.
    return tab == ActiveTab.Pictures && Build.VERSION.SDK_INT >= 34 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
}

private data class DeviceMediaQueryResult(
    val items: List<DeviceMediaItem>,
    val error: String? = null,
)

/**
 * Query MediaStore using column names rather than fixed positions and fall back to
 * bucket columns if RELATIVE_PATH is unavailable on a vendor MediaStore provider.
 * Failures are surfaced in the picker instead of silently appearing as an empty library.
 */
private fun queryDeviceMedia(context: Context, tab: ActiveTab): DeviceMediaQueryResult {
    val isVideo = tab == ActiveTab.Videos
    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val bucketNameColumn = if (isVideo) MediaStore.Video.Media.BUCKET_DISPLAY_NAME else MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    val bucketIdColumn = if (isVideo) MediaStore.Video.Media.BUCKET_ID else MediaStore.Images.Media.BUCKET_ID
    val projections = if (Build.VERSION.SDK_INT >= 29) {
        listOf(
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, bucketNameColumn, bucketIdColumn),
        )
    } else {
        listOf(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, bucketNameColumn, bucketIdColumn))
    }

    var lastError: Throwable? = null
    var queriedSuccessfully = false
    for ((projectionIndex, projection) in projections.withIndex()) {
        try {
            val cursor = context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )
            if (cursor == null) {
                lastError = IllegalStateException("MediaStore returned no cursor")
                continue
            }
            queriedSuccessfully = true

            cursor.use { c ->
                val idIndex = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathIndex = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val bucketNameIndex = c.getColumnIndex(bucketNameColumn)
                val bucketIdIndex = c.getColumnIndex(bucketIdColumn)
                if (idIndex < 0 || nameIndex < 0) {
                    lastError = IllegalStateException("MediaStore did not return required media columns")
                    return@use
                }

                val result = buildList {
                    while (c.moveToNext()) {
                        val id = c.getLong(idIndex)
                        val name = c.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: "media-$id"
                        val rawPath = if (pathIndex >= 0) c.getString(pathIndex).orEmpty() else ""
                        val bucketName = if (bucketNameIndex >= 0) c.getString(bucketNameIndex).orEmpty() else ""
                        val bucketId = if (bucketIdIndex >= 0) c.getString(bucketIdIndex).orEmpty() else ""
                        val path = when {
                            rawPath.isNotBlank() -> rawPath.trimEnd('/')
                            bucketName.isNotBlank() -> "${bucketName.trimEnd('/')}${if (bucketId.isNotBlank()) " ($bucketId)" else ""}"
                            else -> "Unknown folder"
                        }
                        val folderName = when {
                            rawPath.isNotBlank() -> path.substringAfterLast('/').ifBlank { path }
                            bucketName.isNotBlank() -> bucketName
                            else -> "Unknown folder"
                        }
                        add(DeviceMediaItem(ContentUris.withAppendedId(collection, id), name, path, folderName))
                    }
                }
                if (result.isNotEmpty()) return DeviceMediaQueryResult(result)
                // Some vendor providers accept RELATIVE_PATH but return no rows for it;
                // retry with the more widely supported bucket columns before reporting empty.
                if (projectionIndex == projections.lastIndex) return DeviceMediaQueryResult(emptyList())
            }
        } catch (t: Throwable) {
            lastError = t
        }
    }

    return if (queriedSuccessfully && lastError == null) {
        DeviceMediaQueryResult(emptyList())
    } else {
        DeviceMediaQueryResult(
            emptyList(),
            "Couldn't read device media. Check photo/video permission and try again.",
        )
    }
}

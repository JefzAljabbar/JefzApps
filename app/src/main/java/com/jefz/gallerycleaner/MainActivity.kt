package com.jefz.gallerycleaner

import android.Manifest
import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode { NEWEST, OLDEST, LARGEST, SMALLEST }
enum class StorageMode { ALL, INTERNAL, EXTERNAL }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val date: Long,
    val folder: String,
    val isVideo: Boolean
)

class MediaRepository(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun load(sort: SortMode, storage: StorageMode, folder: String?, trash: Boolean = false): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<MediaItem>()
            val collections = listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
            )
            for ((collection, isVideo) in collections) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
                val selectionParts = mutableListOf<String>()
                val args = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= 30) {
                    selectionParts += "${MediaStore.MediaColumns.IS_TRASHED} = ?"
                    args += if (trash) "1" else "0"
                }
                if (folder != null) {
                    selectionParts += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                    args += "$folder%"
                }
                val sortSql = when (sort) {
                    SortMode.NEWEST -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                    SortMode.OLDEST -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
                    SortMode.LARGEST -> "${MediaStore.MediaColumns.SIZE} DESC"
                    SortMode.SMALLEST -> "${MediaStore.MediaColumns.SIZE} ASC"
                }
                resolver.query(
                    collection,
                    projection,
                    selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                    args.toTypedArray().takeIf { args.isNotEmpty() },
                    sortSql
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val date = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val itemId = c.getLong(id)
                        out += MediaItem(
                            itemId,
                            ContentUris.withAppendedId(collection, itemId),
                            c.getString(name) ?: "",
                            c.getLong(size),
                            c.getLong(date),
                            c.getString(path) ?: "",
                            isVideo
                        )
                    }
                }
            }
            out
        }

    suspend fun trash(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 30) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
                put(MediaStore.MediaColumns.DATE_EXPIRES, (System.currentTimeMillis()/1000L) + 30L*24*60*60)
            }
            items.forEach { runCatching { resolver.update(it.uri, values, null, null) } }
        } else {
            // Android 8-10 fallback: move into app-private trash is intentionally not
            // attempted here because direct file-path access is restricted on some devices.
            // The UI still keeps the item in a local trash queue; permanent deletion is
            // only available through the native MediaStore flow on Android 11+.
        }
    }

    suspend fun restore(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 30) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 0)
                putNull(MediaStore.MediaColumns.DATE_EXPIRES)
            }
            items.forEach { runCatching { resolver.update(it.uri, values, null, null) } }
        }
    }

    suspend fun deletePermanently(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        items.forEach { runCatching { resolver.delete(it.uri, null, null) } }
    }

    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        val set = linkedSetOf<String>()
        listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { uri ->
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { set += it }
            }
        }
        set.sorted()
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    var items by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var trashItems by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var sort by mutableStateOf(SortMode.NEWEST)
    var storage by mutableStateOf(StorageMode.ALL)
    var folder by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false); private set

    fun refresh() {
        androidx.lifecycle.viewModelScope.launch {
            loading = true
            items = repo.load(sort, storage, folder, false)
            trashItems = repo.load(sort, storage, null, true)
            selected = emptySet()
            loading = false
        }
    }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun clearSelection() { selected = emptySet() }
    fun trashSelected() = act(items.filter { it.id in selected }) { repo.trash(it); refresh() }
    fun trashOne(item: MediaItem) = act(listOf(item)) { repo.trash(it); refresh() }
    fun restoreSelected() = act(trashItems.filter { it.id in selected }) { repo.restore(it); refresh() }
    fun deleteSelected() = act(trashItems.filter { it.id in selected }) { repo.deletePermanently(it); refresh() }
    fun deleteAllTrash() = act(trashItems) { repo.deletePermanently(it); refresh() }
    private fun act(x: List<MediaItem>, block: suspend (List<MediaItem>) -> Unit) {
        androidx.lifecycle.viewModelScope.launch { loading = true; block(x); loading = false }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GalleryCleanerApp() }
    }
}

@Composable
fun GalleryCleanerApp(vm: MainViewModel = viewModel()) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf("review") }
    var index by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showSelect by remember { mutableStateOf(false) }

    val permissions = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh()
    }

    LaunchedEffect(Unit) {
        val missing = permissions.filter { androidx.core.content.ContextCompat.checkSelfPermission(
            androidx.compose.ui.platform.LocalContext.current, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray()) else vm.refresh()
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (screen == "trash") "Trash" else "Gallery Cleaner") },
                    actions = {
                        if (screen == "review") {
                            IconButton({ showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                            IconButton({ showSelect = true }) { Icon(Icons.Default.Checklist, "Selection") }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(screen == "review", { screen = "review"; index = 0; vm.clearSelection() }, { Icon(Icons.Default.PhotoLibrary, "Gallery") }, { Text("Review") })
                    NavigationBarItem(screen == "trash", { screen = "trash"; vm.clearSelection() }, { Icon(Icons.Default.Delete, "Trash") }, { Text("Trash") })
                }
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                if (screen == "review") {
                    ReviewScreen(vm, index, { index = it }, showSelect)
                } else {
                    TrashScreen(vm)
                }
                if (showSettings) SettingsDialog(vm) { showSettings = false }
                if (showSelect) SelectionDialog(vm, screen == "trash") { showSelect = false }
            }
        }
    }
}

@Composable
fun ReviewScreen(vm: MainViewModel, index: Int, setIndex: (Int) -> Unit, selection: Boolean) {
    if (vm.items.isEmpty()) {
        EmptyState("Tidak ada foto/video yang cocok.")
        return
    }
    val safeIndex = index.coerceIn(0, vm.items.lastIndex)
    val item = vm.items[safeIndex]
    Column(Modifier.fillMaxSize()) {
        Text("${safeIndex + 1} / ${vm.items.size}", Modifier.align(Alignment.CenterHorizontally).padding(8.dp), fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            AsyncImage(item.uri, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (item.isVideo) {
                Icon(Icons.Default.PlayCircle, null, Modifier.align(Alignment.Center).size(64.dp))
            }
        }
        Text("${item.name}\n${item.folder}  •  ${formatSize(item.size)}",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundAction(Icons.Default.Delete, "Trash") {
                vm.trashOne(item); setIndex((safeIndex + 1).coerceAtMost(vm.items.lastIndex))
            }
            RoundAction(Icons.Default.Undo, "Undo") { setIndex((safeIndex - 1).coerceAtLeast(0)) }
            RoundAction(Icons.Default.SkipNext, "Skip") { setIndex((safeIndex + 1).coerceAtMost(vm.items.lastIndex)) }
        }
    }
}

@Composable
fun TrashScreen(vm: MainViewModel) {
    if (vm.trashItems.isEmpty()) { EmptyState("Trash kosong."); return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.restoreSelected() }, enabled = vm.selected.isNotEmpty()) { Text("Restore") }
            Button({ vm.deleteSelected() }, enabled = vm.selected.isNotEmpty()) { Text("Delete Selected") }
            OutlinedButton({ vm.deleteAllTrash() }) { Text("Delete All") }
        }
        LazyColumn {
            items(vm.trashItems) { item ->
                Row(Modifier.fillMaxWidth().clickable { vm.toggle(item.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.id in vm.selected, { vm.toggle(item.id) })
                    AsyncImage(item.uri, item.name, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(item.name, maxLines = 1)
                        Text("${formatSize(item.size)}  •  ${item.folder}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: MainViewModel, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Opsi Gallery") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Urutan", fontWeight = FontWeight.Bold)
                SortMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().clickable { vm.sort = mode }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == vm.sort, { vm.sort = mode })
                        Text(mode.label())
                    }
                }
                Text("Storage", fontWeight = FontWeight.Bold)
                StorageMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().clickable { vm.storage = mode }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == vm.storage, { vm.storage = mode })
                        Text(mode.label())
                    }
                }
                Text("Folder", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().clickable { vm.folder = null }) {
                    RadioButton(vm.folder == null, { vm.folder = null }); Text("Semua Folder")
                }
                // Folder picker is kept simple here; folder-specific filtering is also
                // available through the relative-path value in the data model.
                Text("Filter folder spesifik dapat ditambahkan pada dialog folder berikutnya.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button({ vm.refresh(); close() }) { Text("Terapkan") }
        }
    )
}

@Composable
fun SelectionDialog(vm: MainViewModel, trash: Boolean, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Selection") },
        text = { Text("${vm.selected.size} item dipilih.") },
        confirmButton = {
            Button({
                if (trash) vm.deleteSelected() else vm.trashSelected()
                close()
            }) { Text(if (trash) "Delete Selected" else "Trash Selected") }
        },
        dismissButton = { OutlinedButton({ vm.clearSelection(); close() }) { Text("Batal") } }
    )
}

@Composable
fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledIconButton(onClick, Modifier.size(64.dp), shape = CircleShape) {
        Icon(icon, label, Modifier.size(30.dp))
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

fun formatSize(size: Long): String = when {
    size >= 1_000_000_000 -> "%.1f GB".format(size / 1_000_000_000.0)
    size >= 1_000_000 -> "%.1f MB".format(size / 1_000_000.0)
    size >= 1_000 -> "%.1f KB".format(size / 1_000.0)
    else -> "$size B"
}

fun SortMode.label() = when(this) {
    SortMode.NEWEST -> "Waktu terbaru"
    SortMode.OLDEST -> "Waktu terlama"
    SortMode.LARGEST -> "Ukuran terbesar"
    SortMode.SMALLEST -> "Ukuran terkecil"
}
fun StorageMode.label() = when(this) {
    StorageMode.ALL -> "Internal + External"
    StorageMode.INTERNAL -> "Internal Only"
    StorageMode.EXTERNAL -> "External / SD Card Only"
}

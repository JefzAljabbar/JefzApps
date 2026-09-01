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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val resolver: ContentResolver
        get() = context.contentResolver

    suspend fun load(
        sort: SortMode,
        storage: StorageMode,
        folder: String?,
        trash: Boolean
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<MediaItem>()

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

            val selection = mutableListOf<String>()
            val args = mutableListOf<String>()

            if (Build.VERSION.SDK_INT >= 30) {
                selection += "${MediaStore.MediaColumns.IS_TRASHED} = ?"
                args += if (trash) "1" else "0"
            }

            if (folder != null) {
                selection += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                args += "$folder%"
            }

            val order = when (sort) {
                SortMode.NEWEST -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                SortMode.OLDEST -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
                SortMode.LARGEST -> "${MediaStore.MediaColumns.SIZE} DESC"
                SortMode.SMALLEST -> "${MediaStore.MediaColumns.SIZE} ASC"
            }

            resolver.query(
                collection,
                projection,
                selection.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                args.toTypedArray().takeIf { it.isNotEmpty() },
                order
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    result += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = cursor.getString(nameIndex) ?: "",
                        size = cursor.getLong(sizeIndex),
                        date = cursor.getLong(dateIndex),
                        folder = cursor.getString(pathIndex) ?: "",
                        isVideo = isVideo
                    )
                }
            }
        }

        result
    }

    suspend fun trash(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 30) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.IS_TRASHED, 1)
                put(
                    MediaStore.MediaColumns.DATE_EXPIRES,
                    (System.currentTimeMillis() / 1000L) + 30L * 24L * 60L * 60L
                )
            }
            items.forEach { runCatching { resolver.update(it.uri, values, null, null) } }
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
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = MediaRepository(app)

    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    var trashItems by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    var selected by mutableStateOf<Set<Long>>(emptySet())
        private set

    var sort by mutableStateOf(SortMode.NEWEST)
    var storage by mutableStateOf(StorageMode.ALL)
    var folder by mutableStateOf<String?>(null)

    var loading by mutableStateOf(false)
        private set

    fun refresh() {
        viewModelScope.launch {
            loading = true
            items = repository.load(sort, storage, folder, false)
            trashItems = repository.load(sort, storage, null, true)
            selected = emptySet()
            loading = false
        }
    }

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun trashOne(item: MediaItem, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            loading = true
            repository.trash(listOf(item))
            refresh()
            onDone()
        }
    }

    fun trashSelected(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            loading = true
            repository.trash(items.filter { it.id in selected })
            refresh()
            onDone()
        }
    }

    fun restoreSelected(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            loading = true
            repository.restore(trashItems.filter { it.id in selected })
            refresh()
            onDone()
        }
    }

    fun deleteSelected(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            loading = true
            repository.deletePermanently(trashItems.filter { it.id in selected })
            refresh()
            onDone()
        }
    }

    fun deleteAllTrash(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            loading = true
            repository.deletePermanently(trashItems)
            refresh()
            onDone()
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GalleryCleanerApp()
        }
    }
}

@Composable
fun GalleryCleanerApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf("review") }
    var index by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showSelection by remember { mutableStateOf(false) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refresh()
    }

    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        } else {
            vm.refresh()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            if (screen == "trash") "Trash"
                            else "Gallery Cleaner"
                        )
                    },
                    actions = {
                        if (screen == "review") {
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, "Settings")
                            }
                            IconButton(onClick = { showSelection = true }) {
                                Icon(Icons.Default.Checklist, "Selection")
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == "review",
                        onClick = {
                            screen = "review"
                            index = 0
                            vm.clearSelection()
                        },
                        icon = {
                            Icon(Icons.Default.PhotoLibrary, "Gallery")
                        },
                        label = { Text("Review") }
                    )

                    NavigationBarItem(
                        selected = screen == "trash",
                        onClick = {
                            screen = "trash"
                            vm.clearSelection()
                        },
                        icon = {
                            Icon(Icons.Default.Delete, "Trash")
                        },
                        label = { Text("Trash") }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (screen == "review") {
                    ReviewScreen(
                        vm = vm,
                        index = index,
                        setIndex = { index = it }
                    )
                } else {
                    TrashScreen(vm)
                }

                if (showSettings) {
                    SettingsDialog(
                        vm = vm,
                        close = { showSettings = false }
                    )
                }

                if (showSelection) {
                    SelectionDialog(
                        vm = vm,
                        trash = screen == "trash",
                        close = { showSelection = false }
                    )
                }

                if (vm.loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@Composable
fun ReviewScreen(
    vm: MainViewModel,
    index: Int,
    setIndex: (Int) -> Unit
) {
    if (vm.items.isEmpty()) {
        EmptyState("Tidak ada foto/video yang cocok.")
        return
    }

    val safeIndex = index.coerceIn(0, vm.items.lastIndex)
    val item = vm.items[safeIndex]

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${safeIndex + 1} / ${vm.items.size}",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(8.dp),
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            if (item.isVideo) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = "Video",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                )
            }
        }

        Text(
            text = "${item.name}\n${item.folder} • ${formatSize(item.size)}",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundAction(Icons.Default.Delete, "Trash") {
                vm.trashOne(item) {
                    setIndex((safeIndex + 1).coerceAtMost(vm.items.lastIndex))
                }
            }

            RoundAction(Icons.Default.Undo, "Undo") {
                setIndex((safeIndex - 1).coerceAtLeast(0))
            }

            RoundAction(Icons.Default.SkipNext, "Skip") {
                setIndex((safeIndex + 1).coerceAtMost(vm.items.lastIndex))
            }
        }
    }
}

@Composable
fun TrashScreen(vm: MainViewModel) {
    if (vm.trashItems.isEmpty()) {
        EmptyState("Trash kosong.")
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.restoreSelected() },
                enabled = vm.selected.isNotEmpty()
            ) {
                Text("Restore")
            }

            Button(
                onClick = { vm.deleteSelected() },
                enabled = vm.selected.isNotEmpty()
            ) {
                Text("Delete Selected")
            }

            OutlinedButton(
                onClick = { vm.deleteAllTrash() }
            ) {
                Text("Delete All")
            }
        }

        LazyColumn {
            items(
                items = vm.trashItems,
                key = { it.id }
            ) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggle(item.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.id in vm.selected,
                        onCheckedChange = { vm.toggle(item.id) }
                    )

                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )

                    Column(
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Text(
                            text = item.name,
                            maxLines = 1
                        )
                        Text(
                            text = "${formatSize(item.size)} • ${item.folder}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    vm: MainViewModel,
    close: () -> Unit
) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Opsi Gallery") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Urutan", fontWeight = FontWeight.Bold)

                SortMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.sort = mode },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == vm.sort,
                            onClick = { vm.sort = mode }
                        )
                        Text(mode.label())
                    }
                }

                Text("Storage", fontWeight = FontWeight.Bold)

                StorageMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.storage = mode },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == vm.storage,
                            onClick = { vm.storage = mode }
                        )
                        Text(mode.label())
                    }
                }

                Text("Folder", fontWeight = FontWeight.Bold)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.folder = null },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = vm.folder == null,
                        onClick = { vm.folder = null }
                    )
                    Text("Semua Folder")
                }

                Text(
                    text = "Filter folder spesifik akan disempurnakan pada versi berikutnya.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    vm.refresh()
                    close()
                }
            ) {
                Text("Terapkan")
            }
        }
    )
}

@Composable
fun SelectionDialog(
    vm: MainViewModel,
    trash: Boolean,
    close: () -> Unit
) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Selection") },
        text = {
            Text("${vm.selected.size} item dipilih.")
        },
        confirmButton = {
            Button(
                onClick = {
                    if (trash) {
                        vm.deleteSelected()
                    } else {
                        vm.trashSelected()
                    }
                    close()
                }
            ) {
                Text(
                    if (trash) "Delete Selected"
                    else "Trash Selected"
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    vm.clearSelection()
                    close()
                }
            ) {
                Text("Batal")
            }
        }
    )
}

@Composable
fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

fun formatSize(size: Long): String {
    return when {
        size >= 1_000_000_000 ->
            "%.1f GB".format(size / 1_000_000_000.0)

        size >= 1_000_000 ->
            "%.1f MB".format(size / 1_000_000.0)

        size >= 1_000 ->
            "%.1f KB".format(size / 1_000.0)

        else -> "$size B"
    }
}

fun SortMode.label(): String {
    return when (this) {
        SortMode.NEWEST -> "Waktu terbaru"
        SortMode.OLDEST -> "Waktu terlama"
        SortMode.LARGEST -> "Ukuran terbesar"
        SortMode.SMALLEST -> "Ukuran terkecil"
    }
}

fun StorageMode.label(): String {
    return when (this) {
        StorageMode.ALL -> "Internal + External"
        StorageMode.INTERNAL -> "Internal Only"
        StorageMode.EXTERNAL -> "External / SD Card Only"
    }
}

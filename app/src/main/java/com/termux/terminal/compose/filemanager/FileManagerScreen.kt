package com.termux.terminal.compose.filemanager

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.filemanager.FileOperationsHelper
import com.termux.app.filemanager.FileSortOption
import kotlinx.coroutines.launch
import java.io.File

private enum class DialogKind { NONE, NEW_FOLDER, NEW_FILE, RENAME, DELETE, DETAILS, SORT, BOOKMARKS }

/**
 * Expressive file manager screen. Ports all `FileManagerActivity`
 * operations: browse, search, sort, hidden, bookmarks, create, rename,
 * copy/cut/paste, trash + undo, share, details, multi-select.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    viewModel: FileManagerViewModel,
    onNavigateUp: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFiles: (List<File>) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(DialogKind.NONE) }
    var dialogFile by remember { mutableStateOf<File?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.selectionMode) "${state.selectedPaths.size} selected" else state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!state.selectionMode) {
                            Text(
                                text = state.currentPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sort: ${state.sortOption.getDisplayName()} ${if (state.sortAscending) "↑" else "↓"}") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                            onClick = { menuExpanded = false; dialog = DialogKind.SORT }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.showHidden) "Hide hidden files" else "Show hidden files") },
                            leadingIcon = {
                                Icon(
                                    if (state.showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null
                                )
                            },
                            onClick = { menuExpanded = false; viewModel.toggleHidden() }
                        )
                        DropdownMenuItem(
                            text = { Text("Bookmarks") },
                            leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                            onClick = { menuExpanded = false; dialog = DialogKind.BOOKMARKS }
                        )
                        if (state.hasClipboard) {
                            DropdownMenuItem(
                                text = { Text("Paste") },
                                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.paste { count ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Pasted $count item(s)")
                                        }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Select all") },
                            leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                            onClick = { menuExpanded = false; viewModel.selectAll() }
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (state.selectionMode) {
                BottomAppBar {
                    IconButton(onClick = { viewModel.copySelection() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = { viewModel.cutSelection() }) {
                        Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                    }
                    IconButton(onClick = { onShareFiles(viewModel.selectedFiles()) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        dialogFile = null
                        nameInput = ""
                        dialog = DialogKind.DELETE
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text("Clear")
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = { fabMenuExpanded = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New")
                }
                DropdownMenu(
                    expanded = fabMenuExpanded,
                    onDismissRequest = { fabMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                        onClick = {
                            fabMenuExpanded = false
                            nameInput = ""
                            dialog = DialogKind.NEW_FOLDER
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New file") },
                        leadingIcon = { Icon(Icons.Default.Description, null) },
                        onClick = {
                            fabMenuExpanded = false
                            nameInput = ""
                            dialog = DialogKind.NEW_FILE
                        }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.goBack() },
                    enabled = state.canGoBack
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "History back")
                }
                IconButton(
                    onClick = { viewModel.goForward() },
                    enabled = state.canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "History forward")
                }
                IconButton(onClick = { viewModel.goUp() }) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                }
                if (searchActive) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.files, key = { it.absolutePath }) { file ->
                    val selected = state.selectedPaths.contains(file.absolutePath)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (state.selectionMode) viewModel.toggleSelection(file.absolutePath)
                                    else if (file.isDirectory) viewModel.navigateTo(file)
                                    else onOpenFile(file)
                                },
                                onLongClick = { viewModel.toggleSelection(file.absolutePath) }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.selectionMode) {
                            Icon(
                                if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Icon(
                            if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (file.isDirectory) "Folder"
                                else FileOperationsHelper.formatSize(file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            dialogFile = file
                            nameInput = file.name
                            dialog = DialogKind.DETAILS
                        }) {
                            Icon(Icons.Default.Info, contentDescription = "Details")
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        DialogKind.NEW_FOLDER -> NameDialog(
            title = "New folder",
            initial = "",
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name ->
                if (!viewModel.createFolder(name)) {
                    scope.launch { snackbarHostState.showSnackbar("Failed to create folder") }
                }
                dialog = DialogKind.NONE
            }
        )
        DialogKind.NEW_FILE -> NameDialog(
            title = "New file",
            initial = "",
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name ->
                if (!viewModel.createFile(name)) {
                    scope.launch { snackbarHostState.showSnackbar("Failed to create file") }
                }
                dialog = DialogKind.NONE
            }
        )
        DialogKind.RENAME -> NameDialog(
            title = "Rename",
            initial = nameInput,
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name ->
                val f = dialogFile
                if (f != null && !viewModel.renameFile(f, name)) {
                    scope.launch { snackbarHostState.showSnackbar("Rename failed") }
                }
                dialog = DialogKind.NONE
            }
        )
        DialogKind.DELETE -> {
            val count = dialogFile?.let { 1 } ?: state.selectedPaths.size
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text("Delete?") },
                text = { Text("Move $count item(s) to trash?") },
                confirmButton = {
                    TextButton(onClick = {
                        val files = dialogFile?.let { listOf(it) } ?: viewModel.selectedFiles()
                        dialog = DialogKind.NONE
                        viewModel.deleteFiles(files) { ok ->
                            scope.launch {
                                if (ok) {
                                    val result = snackbarHostState.showSnackbar(
                                        "Moved to trash", actionLabel = "Undo"
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete()
                                    }
                                } else {
                                    snackbarHostState.showSnackbar("Delete failed")
                                }
                            }
                        }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = DialogKind.NONE }) { Text("Cancel") }
                }
            )
        }
        DialogKind.DETAILS -> {
            val f = dialogFile
            if (f != null) {
                AlertDialog(
                    onDismissRequest = { dialog = DialogKind.NONE },
                    title = { Text(f.name) },
                    text = {
                        Text(
                            (if (f.isDirectory) "Folder\n" else "") +
                                "Path: ${f.absolutePath}\n" +
                                "Size: ${FileOperationsHelper.formatSize(if (f.isDirectory) 0 else f.length())}\n" +
                                "Type: ${FileOperationsHelper.getMimeType(f.name)}"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            dialog = DialogKind.NONE
                            if (f.isDirectory) viewModel.navigateTo(f) else onOpenFile(f)
                        }) { Text("Open") }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                nameInput = f.name
                                dialog = DialogKind.RENAME
                            }) { Text("Rename") }
                            TextButton(onClick = { dialog = DialogKind.NONE }) { Text("Close") }
                        }
                    }
                )
            } else {
                dialog = DialogKind.NONE
            }
        }
        DialogKind.SORT -> {
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text("Sort by") },
                text = {
                    Column {
                        FileSortOption.entries.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        viewModel.toggleSort(option)
                                        dialog = DialogKind.NONE
                                    })
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Sort, null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    option.getDisplayName() +
                                        if (state.sortOption == option) {
                                            if (state.sortAscending) " ↑" else " ↓"
                                        } else ""
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            viewModel.toggleHidden()
                            dialog = DialogKind.NONE
                        }) {
                            Text(if (state.showHidden) "Hide hidden files" else "Show hidden files")
                        }
                    }
                },
                confirmButton = {}
            )
        }
        DialogKind.BOOKMARKS -> {
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text("Bookmarks") },
                text = {
                    Column {
                        viewModel.bookmarkDirs().forEach { (label, dir) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        viewModel.navigateTo(dir)
                                        dialog = DialogKind.NONE
                                    })
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Bookmark, null)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(label)
                                    Text(
                                        dir.absolutePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
        DialogKind.NONE -> Unit
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotEmpty()
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

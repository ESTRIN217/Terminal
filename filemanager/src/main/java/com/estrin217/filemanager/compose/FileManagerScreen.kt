package com.estrin217.filemanager.compose

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estrin217.filemanager.FileOperationsHelper
import com.estrin217.filemanager.FileSortOption
import com.estrin217.filemanager.R
import kotlinx.coroutines.launch
import java.io.File

private enum class DialogKind { NONE, NEW_FOLDER, NEW_FILE, NEW_SYMLINK, RENAME, DELETE, DETAILS, SORT, BOOKMARKS }

/**
 * Expressive file manager screen. Ports all `FileManagerActivity`
 * operations: browse, search, sort, hidden, bookmarks, create, rename,
 * copy/cut/paste, trash + undo, share, details, multi-select.
 *
 * Symlinks show a link badge with their raw target, resolve to the canonical
 * destination on open, and dangling links report a broken-link message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    viewModel: FileManagerViewModel,
    onNavigateUp: () -> Unit,
    onOpenFile: (File) -> Unit,
    onShareFiles: (List<File>) -> Unit,
    onEnsureStorageAccess: (File, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onOpenInTerminal: ((File) -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatusMessage()
        }
    }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(DialogKind.NONE) }
    var dialogFile by remember { mutableStateOf<File?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    // Keyboard / extra keys navigation target for the visible file list.
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(state.focusedIndex) {
        if (state.focusedIndex >= 0) listState.animateScrollToItem(state.focusedIndex)
    }

    fun executeKeyAction(action: FileManagerKeyAction): Boolean =
        FileManagerActions.execute(action, viewModel, onEnsureStorageAccess, onOpenFile)

    fun hardwareKeyAction(key: Key): FileManagerKeyAction = when (key) {
        Key.DirectionUp -> FileManagerKeyAction.FOCUS_UP
        Key.DirectionDown -> FileManagerKeyAction.FOCUS_DOWN
        Key.MoveHome -> FileManagerKeyAction.FOCUS_HOME
        Key.MoveEnd -> FileManagerKeyAction.FOCUS_END
        Key.PageUp -> FileManagerKeyAction.PAGE_UP
        Key.PageDown -> FileManagerKeyAction.PAGE_DOWN
        Key.Enter, Key.NumPadEnter, Key.DirectionRight -> FileManagerKeyAction.OPEN
        Key.Escape, Key.Backspace, Key.DirectionLeft -> FileManagerKeyAction.BACK
        Key.Tab -> FileManagerKeyAction.TOGGLE_SELECTION
        else -> FileManagerKeyAction.NONE
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Never steal keys while the search field is on screen.
                if (searchActive) return@onPreviewKeyEvent false
                val action = hardwareKeyAction(event.key)
                if (action == FileManagerKeyAction.NONE) return@onPreviewKeyEvent false
                executeKeyAction(action)
            },
        contentWindowInsets = contentWindowInsets,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.selectionMode) stringResource(R.string.selection_mode_title, state.selectedPaths.size) else state.title,
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_go_back))
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.filemanager_more))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_sort) + ": ${state.sortOption.getDisplayName()} ${if (state.sortAscending) "↑" else "↓"}") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                            onClick = { menuExpanded = false; dialog = DialogKind.SORT }
                        )
                        DropdownMenuItem(
                            text = { Text(if (state.showHidden) stringResource(R.string.action_hide_hidden) else stringResource(R.string.action_show_hidden)) },
                            leadingIcon = {
                                Icon(
                                    if (state.showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    null
                                )
                            },
                            onClick = { menuExpanded = false; viewModel.toggleHidden() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_bookmarks)) },
                            leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                            onClick = { menuExpanded = false; dialog = DialogKind.BOOKMARKS }
                        )
                        if (state.hasClipboard) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_paste)) },
                                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                enabled = !state.busy,
                                onClick = {
                                    menuExpanded = false
                                    viewModel.paste { count ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.msg_paste_success, count))
                                        }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_select_all)) },
                            leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                            onClick = { menuExpanded = false; viewModel.selectAll() }
                        )
                        if (onOpenInTerminal != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_open_in_terminal)) },
                                leadingIcon = { Icon(Icons.Default.Terminal, null) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenInTerminal(File(state.currentPath))
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.selectionMode) {
                BottomAppBar {
                    IconButton(onClick = { viewModel.copySelection() }, enabled = !state.busy) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                    }
                    IconButton(onClick = { viewModel.cutSelection() }, enabled = !state.busy) {
                        Icon(Icons.Default.ContentCut, contentDescription = stringResource(R.string.action_cut))
                    }
                    IconButton(onClick = { onShareFiles(viewModel.selectedFiles()) }, enabled = !state.busy) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = {
                        dialogFile = null
                        nameInput = ""
                        dialog = DialogKind.DELETE
                    }, enabled = !state.busy) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearSelection() }) {
                        Text(stringResource(R.string.filemanager_clear))
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButtonMenu(
                    expanded = fabMenuExpanded,
                    button = {
                        ToggleFloatingActionButton(
                            checked = fabMenuExpanded,
                            onCheckedChange = { fabMenuExpanded = !fabMenuExpanded }
                        ) {
                            Icon(
                                if (fabMenuExpanded) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = stringResource(R.string.filemanager_new)
                            )
                        }
                    }
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            nameInput = ""
                            dialog = DialogKind.NEW_FOLDER
                        },
                        icon = { Icon(Icons.Default.Folder, null) },
                        text = { Text(stringResource(R.string.action_new_folder)) }
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            nameInput = ""
                            dialog = DialogKind.NEW_FILE
                        },
                        icon = { Icon(Icons.Default.Description, null) },
                        text = { Text(stringResource(R.string.action_new_file)) }
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            nameInput = ""
                            dialog = DialogKind.NEW_SYMLINK
                        },
                        icon = { Icon(Icons.Default.Link, null) },
                        text = { Text(stringResource(R.string.action_new_symlink)) }
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_go_back))
                }
                IconButton(
                    onClick = { viewModel.goForward() },
                    enabled = state.canGoForward
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.action_go_forward))
                }
                IconButton(onClick = { viewModel.goUp() }) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.filemanager_up))
                }
                if (onOpenInTerminal != null) {
                    IconButton(onClick = { onOpenInTerminal(File(state.currentPath)) }) {
                        Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.action_open_in_terminal))
                    }
                }
                if (searchActive) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        label = { Text(stringResource(R.string.action_search)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.files, key = { _, file -> file.absolutePath }) { index, file ->
                    val selected = state.selectedPaths.contains(file.absolutePath)
                    val focused = state.focusedIndex == index
                    val linkTarget = state.symlinkTargets[file.absolutePath]
                    val isLink = linkTarget != null
                    val isBroken = state.brokenLinks.contains(file.absolutePath)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (focused) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            )
                            .combinedClickable(
                                onClick = {
                                    viewModel.setFocusedIndex(index)
                                    if (state.selectionMode) viewModel.toggleSelection(file.absolutePath)
                                    else FileManagerActions.openFileOrDir(
                                        file, viewModel, onEnsureStorageAccess, onOpenFile
                                    )
                                },
                                onLongClick = {
                                    viewModel.setFocusedIndex(index)
                                    viewModel.toggleSelection(file.absolutePath)
                                }
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
                            if (isLink) Icons.Default.Link
                            else if (file.isDirectory) Icons.Default.Folder
                            else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (isBroken) MaterialTheme.colorScheme.error
                            else if (isLink) MaterialTheme.colorScheme.tertiary
                            else if (file.isDirectory) MaterialTheme.colorScheme.primary
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
                                text = if (isBroken) stringResource(R.string.filemanager_broken_link, linkTarget!!)
                                else if (isLink) stringResource(R.string.filemanager_link_target, linkTarget!!)
                                else if (file.isDirectory) stringResource(R.string.filemanager_folder)
                                else FileOperationsHelper.formatSize(file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBroken) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            dialogFile = file
                            nameInput = file.name
                            dialog = DialogKind.DETAILS
                        }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.action_details))
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        DialogKind.NEW_FOLDER -> NameDialog(
            title = stringResource(R.string.action_new_folder),
            initial = "",
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name ->
                dialog = DialogKind.NONE
                val target = File(state.currentPath)
                onEnsureStorageAccess(target) {
                    viewModel.createFolder(name) { ok ->
                        if (!ok) scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.filemanager_error_create_folder)) }
                    }
                }
            }
        )
        DialogKind.NEW_FILE -> NameDialog(
            title = stringResource(R.string.action_new_file),
            initial = "",
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name ->
                dialog = DialogKind.NONE
                val target = File(state.currentPath)
                onEnsureStorageAccess(target) {
                    viewModel.createFile(name) { ok ->
                        if (!ok) scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.filemanager_error_create_file)) }
                    }
                }
            }
        )
        DialogKind.NEW_SYMLINK -> SymlinkDialog(
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name, target ->
                dialog = DialogKind.NONE
                val dir = File(state.currentPath)
                onEnsureStorageAccess(dir) {
                    viewModel.createSymlink(name, target) { ok ->
                        if (!ok) scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.filemanager_error_create_symlink)) }
                    }
                }
            }
        )
        DialogKind.RENAME -> NameDialog(
            title = stringResource(R.string.title_rename),
            initial = nameInput,
            onDismiss = { dialog = DialogKind.NONE },
            onConfirm = { name ->
                val f = dialogFile
                dialog = DialogKind.NONE
                if (f != null) {
                    viewModel.renameFile(f, name) { ok ->
                        if (!ok) scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.msg_error_rename)) }
                    }
                }
            }
        )
        DialogKind.DELETE -> {
            val count = dialogFile?.let { 1 } ?: state.selectedPaths.size
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text(stringResource(R.string.filemanager_confirm_delete)) },
                text = { Text(stringResource(R.string.filemanager_move_to_trash, count)) },
                confirmButton = {
                    TextButton(onClick = {
                        val files = dialogFile?.let { listOf(it) } ?: viewModel.selectedFiles()
                        dialog = DialogKind.NONE
                        viewModel.deleteFiles(files) { ok ->
                            scope.launch {
                                if (ok) {
                                    val result = snackbarHostState.showSnackbar(
                                        context.getString(R.string.filemanager_moved_to_trash),
                                        actionLabel = context.getString(R.string.action_undo)
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.undoDelete { ok ->
                                            if (!ok) scope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.filemanager_delete_failed))
                                            }
                                        }
                                    }
                                } else {
                                    snackbarHostState.showSnackbar(context.getString(R.string.filemanager_delete_failed))
                                }
                            }
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = DialogKind.NONE }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
        DialogKind.DETAILS -> {
            val f = dialogFile
            if (f != null) {
                val linkTarget = state.symlinkTargets[f.absolutePath]
                val isBroken = state.brokenLinks.contains(f.absolutePath)
                AlertDialog(
                    onDismissRequest = { dialog = DialogKind.NONE },
                    title = { Text(f.name) },
                    text = {
                        Text(
                            (if (f.isDirectory) stringResource(R.string.filemanager_folder) + "\n" else "") +
                                (if (linkTarget != null) stringResource(R.string.filemanager_link_target, linkTarget) + "\n" else "") +
                                (if (isBroken) stringResource(R.string.filemanager_broken_status) + "\n" else "") +
                                stringResource(R.string.filemanager_detail_path, f.absolutePath) + "\n" +
                                stringResource(R.string.filemanager_detail_size, FileOperationsHelper.formatSize(if (f.isDirectory) 0 else f.length())) + "\n" +
                                stringResource(R.string.filemanager_detail_type, FileOperationsHelper.getMimeType(f.name))
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            dialog = DialogKind.NONE
                            if (isBroken) {
                                viewModel.notifyBrokenSymlink(f)
                            } else {
                                val resolved = viewModel.resolveForOpen(f)
                                if (resolved.isDirectory) {
                                    if (FileOperationsHelper.isSharedStoragePath(resolved)) {
                                        onEnsureStorageAccess(resolved) {
                                            viewModel.navigateTo(resolved)
                                        }
                                    } else {
                                        viewModel.navigateTo(resolved)
                                    }
                                } else {
                                    onOpenFile(resolved)
                                }
                            }
                        }) { Text(stringResource(R.string.filemanager_open)) }
                    },
                    dismissButton = {
                        Row {
                            TextButton(onClick = {
                                nameInput = f.name
                                dialog = DialogKind.RENAME
                            }) { Text(stringResource(R.string.action_rename)) }
                            TextButton(onClick = { dialog = DialogKind.NONE }) { Text(stringResource(R.string.filemanager_close)) }
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
                title = { Text(stringResource(R.string.filemanager_sort_by)) },
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
                            Text(if (state.showHidden) stringResource(R.string.action_hide_hidden) else stringResource(R.string.action_show_hidden))
                        }
                    }
                },
                confirmButton = {}
            )
        }
        DialogKind.BOOKMARKS -> {
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text(stringResource(R.string.action_bookmarks)) },
                text = {
                    Column {
                        viewModel.bookmarkDirs().forEach { (label, dir) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = {
                                        dialog = DialogKind.NONE
                                        onEnsureStorageAccess(dir) { viewModel.navigateTo(dir) }
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
            ) { Text(stringResource(R.string.filemanager_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Two-field dialog to create a symlink: link name plus its target.
 *
 * @param onDismiss Called when the dialog is cancelled.
 * @param onConfirm Called with the trimmed link name and target.
 */
@Composable
private fun SymlinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_new_symlink)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.filemanager_link_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(stringResource(R.string.filemanager_target_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), target.trim()) },
                enabled = name.trim().isNotEmpty() && target.trim().isNotEmpty()
            ) { Text(stringResource(R.string.filemanager_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

package com.estrin217.filemanager.compose

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.estrin217.filemanager.FileTypeDetector
import com.estrin217.filemanager.FileOperationsHelper
import com.estrin217.filemanager.FileSortOption
import com.estrin217.filemanager.ExtractionOutcome
import com.estrin217.filemanager.R
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

private enum class DialogKind { NONE, NEW_FOLDER, NEW_FILE, NEW_SYMLINK, RENAME, DELETE, PROPERTIES, GO_TO, COMPRESS, EXTRACT_PASSWORD }

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
    onOpenFile: (File) -> Unit,
    onShareFiles: (List<File>) -> Unit,
    onEnsureStorageAccess: (File, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    onOpenInTerminal: ((File) -> Unit)? = null,
    onEditFile: ((File) -> Unit)? = null,
    /** App-side "New tab" action; hidden when the host does not support it (RF-10). */
    onNewFileManagerTab: (() -> Unit)? = null,
    /** App-side launcher shortcut pinning for the open folder (RF-16). */
    onCreateShortcut: ((File) -> Unit)? = null,
    /**
     * App-side launcher shortcut pinning that opens one *file* with its
     * Android app (spec 009, RF-11); hidden while the host does not wire it.
     */
    onCreateFileShortcut: ((File) -> Unit)? = null,
    /**
     * App-side "Run in terminal" for one file (spec 023, RF-1..RF-4); the
     * element hides while the host does not wire it (008 precedent).
     */
    onRunInTerminal: ((File) -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
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
    var organizeMenuExpanded by remember { mutableStateOf(false) }
    // Which entry's item menu is open (spec 009, RF-1); null when closed.
    var itemMenuFile by remember { mutableStateOf<File?>(null) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    // Keyboard / extra keys navigation target for the visible file list.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Directory changes reset the scroll to the first row: the lazy list keeps
    // its previous offset across listings, which made a new folder open
    // mid-page with the last-position row on top (spec 007). Only a cursor
    // move inside the same directory scrolls, and then minimally. The grid
    // view keeps the same policy over its own lazy state (spec 008, RF-17);
    // switching view modes resets the scroll too.
    var listedDir by remember { mutableStateOf<String?>(null) }
    var savedViewMode by remember { mutableStateOf(FileViewMode.LIST) }
    LaunchedEffect(state.currentPath, state.files.size, state.focusedIndex, state.viewMode) {
        if (state.currentPath != listedDir || state.viewMode != savedViewMode) {
            listedDir = state.currentPath
            savedViewMode = state.viewMode
            if (state.files.isNotEmpty()) {
                listState.scrollToItem(0)
                gridState.scrollToItem(0)
            }
        } else if (state.focusedIndex >= 0) {
            if (state.viewMode == FileViewMode.LIST) {
                listState.ensureItemVisible(state.focusedIndex)
            } else {
                gridState.ensureItemVisible(state.focusedIndex)
            }
        }
    }

    fun executeKeyAction(action: FileManagerKeyAction): Boolean =
        FileManagerActions.execute(action, viewModel, onEnsureStorageAccess, onOpenFile)

    fun copyPathsToClipboard(paths: List<File>) {
        if (paths.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("paths", paths.joinToString("\n") { it.absolutePath })
        )
        scope.launch {
            snackbarHostState.showSnackbar(
                if (paths.size == 1) context.getString(R.string.msg_path_copied)
                else context.getString(R.string.msg_paths_copied, paths.size)
            )
        }
    }

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.action_bookmarks),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                val bookmarks = remember { viewModel.bookmarkDirs() }
                bookmarks.forEach { (label, dir) ->
                    DrawerBookmarkRow(
                        label = label,
                        path = dir.absolutePath,
                        favorite = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onEnsureStorageAccess(dir) { viewModel.navigateTo(dir) }
                        },
                        onRemove = null
                    )
                }
                if (state.favorites.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.filemanager_favorites),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                state.favorites.forEach { favorite ->
                    DrawerBookmarkRow(
                        label = favorite.label,
                        path = favorite.path,
                        favorite = true,
                        onClick = {
                            scope.launch { drawerState.close() }
                            val dir = File(favorite.path)
                            onEnsureStorageAccess(dir) { viewModel.navigateTo(dir) }
                        },
                        onRemove = { viewModel.removeFavorite(favorite.path) }
                    )
                }
            }
        }
    ) {
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
            if (state.selectionMode) {
                // Selection mode is a second state of the top bar: count as
                // title, close icon clears the selection, action icons match
                // the selection semantics (no bottom bar).
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.selection_mode_title, state.selectedPaths.size),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.filemanager_clear)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.copySelection() }, enabled = !state.busy) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                        }
                        IconButton(onClick = { viewModel.cutSelection() }, enabled = !state.busy) {
                            Icon(Icons.Default.ContentCut, contentDescription = stringResource(R.string.action_cut))
                        }
                        IconButton(
                            onClick = { copyPathsToClipboard(viewModel.selectedFiles()) },
                            enabled = !state.busy
                        ) {
                            Icon(Icons.Default.FileCopy, contentDescription = stringResource(R.string.action_copy_path))
                        }
                        IconButton(
                            onClick = { onShareFiles(viewModel.selectedFiles()) },
                            enabled = !state.busy
                        ) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        // Edit: single selected file only (one editor tab per invocation).
                        if (onEditFile != null) {
                            val editSelection = viewModel.selectedFiles()
                            if (editSelection.size == 1 && !editSelection[0].isDirectory) {
                                IconButton(onClick = {
                                    onEditFile(viewModel.resolveForOpen(editSelection[0]))
                                }, enabled = !state.busy) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                                }
                            }
                        }
                        IconButton(onClick = {
                            dialogFile = null
                            nameInput = ""
                            dialog = DialogKind.DELETE
                        }, enabled = !state.busy) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                )
            } else {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (state.selectionMode)
                                stringResource(R.string.selection_mode_title, state.selectedPaths.size)
                            else state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!state.selectionMode) {
                            Text(
                                // "N folders · M files" of the visible listing; the
                                // search filter never changes it (spec 008, RF-1).
                                text = stringResource(
                                    R.string.filemanager_counts, state.folderCount, state.fileCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    // The bookmarks drawer replaces the top-bar back button
                    // (spec 008, RF-4); hardware back keeps the VM history.
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.action_bookmarks))
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                    }
                    IconButton(onClick = { organizeMenuExpanded = true }) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.action_organize))
                    }
                    // The Organize menu (spec 008, RF-7): presentation, sort
                    // criteria and direction, folders-first grouping, and the
                    // "this folder only" scope toggle (RF-8 persistence).
                    DropdownMenu(
                        expanded = organizeMenuExpanded,
                        onDismissRequest = { organizeMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.title_section_view),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {}
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.view_list)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                            trailingIcon = {
                                if (state.viewMode == FileViewMode.LIST) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                organizeMenuExpanded = false
                                viewModel.setViewMode(FileViewMode.LIST)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.view_grid)) },
                            leadingIcon = { Icon(Icons.Default.GridView, null) },
                            trailingIcon = {
                                if (state.viewMode == FileViewMode.GRID) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                organizeMenuExpanded = false
                                viewModel.setViewMode(FileViewMode.GRID)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.title_section_sort),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {}
                        )
                        FileSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.getDisplayName()) },
                                leadingIcon = {
                                    Icon(
                                        when (option) {
                                            FileSortOption.NAME -> Icons.Default.SortByAlpha
                                            FileSortOption.TYPE -> Icons.Default.Category
                                            FileSortOption.SIZE -> Icons.Default.DataUsage
                                            FileSortOption.DATE -> Icons.Default.CalendarMonth
                                        },
                                        null
                                    )
                                },
                                trailingIcon = {
                                    if (state.sortOption == option) Icon(Icons.Default.Check, null)
                                },
                                onClick = {
                                    organizeMenuExpanded = false
                                    viewModel.setSortOption(option)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_ascending)) },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            trailingIcon = {
                                if (state.sortAscending) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                organizeMenuExpanded = false
                                viewModel.setSortAscending(!state.sortAscending)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folders_first)) },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            trailingIcon = {
                                if (state.foldersFirst) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                organizeMenuExpanded = false
                                viewModel.setFoldersFirst(!state.foldersFirst)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.only_this_folder)) },
                            trailingIcon = {
                                if (state.folderScoped) Icon(Icons.Default.Check, null)
                            },
                            onClick = {
                                organizeMenuExpanded = false
                                viewModel.setFolderScoped(!state.folderScoped)
                            }
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.filemanager_more))
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        // Note the fixed order of spec 008, RF-9; host-dependent
                        // entries (new tab, shortcut) hide when unavailable.
                        if (onNewFileManagerTab != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_new_tab)) },
                                leadingIcon = { Icon(Icons.Default.Tab, null) },
                                onClick = {
                                    menuExpanded = false
                                    onNewFileManagerTab()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.filemanager_up)) },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            enabled = state.canGoUp,
                            onClick = { menuExpanded = false; viewModel.goUp() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_go_to)) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = {
                                menuExpanded = false
                                dialog = DialogKind.GO_TO
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_refresh)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { menuExpanded = false; viewModel.refresh() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_select_all)) },
                            leadingIcon = { Icon(Icons.Default.CheckBox, null) },
                            onClick = { menuExpanded = false; viewModel.selectAll() }
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
                            text = { Text(stringResource(R.string.action_share_folder)) },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            enabled = !state.busy,
                            onClick = {
                                menuExpanded = false
                                try {
                                    FileOperationsHelper.shareFolder(context, File(state.currentPath))
                                } catch (e: Exception) {
                                    viewModel.notifyShareFailed()
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_path)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menuExpanded = false
                                copyPathsToClipboard(listOf(File(state.currentPath)))
                            }
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
                        // Toggled: the open folder is either added to or removed
                        // from the persisted favorites (spec 008, RF-6).
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (state.isFavorite) stringResource(R.string.action_remove_favorite)
                                    else stringResource(R.string.action_add_favorite)
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                            onClick = {
                                menuExpanded = false
                                if (state.isFavorite) viewModel.removeFavorite(state.currentPath)
                                else viewModel.addFavorite()
                            }
                        )
                        if (onCreateShortcut != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_create_shortcut)) },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = {
                                    menuExpanded = false
                                    onCreateShortcut(File(state.currentPath))
                                }
                            )
                        }
                        // Kept last: cut/copy have no destination without it
                        // (decided 2026-10-21: RF-9 open question closed as "kept by design").
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
                    }
                }
            )
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
            // Breadcrumb train under the top bar (spec 008, RF-2): back/forward
            // at the left, then the clickable chain ending in the open folder.
            // The search field swaps in here while searching.
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
                    val crumbs = remember(state.currentPath) {
                        BreadcrumbPolicy.crumbs(state.currentPath, TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH)
                    }
                    val crumbScroll = rememberScrollState()
                    // The tail of the path stays pinned to the right (RF-3).
                    LaunchedEffect(state.currentPath) { crumbScroll.scrollTo(crumbScroll.maxValue) }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(crumbScroll)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        crumbs.forEachIndexed { index, crumb ->
                            if (index > 0) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            val isLast = index == crumbs.lastIndex
                            Text(
                                text = crumb.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isLast) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (isLast) Modifier
                                else Modifier.combinedClickable(onClick = {
                                    val dir = File(crumb.path)
                                    onEnsureStorageAccess(dir) { viewModel.navigateTo(dir) }
                                })
                            )
                        }
                    }
                }
            }
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (state.viewMode) {
                FileViewMode.LIST -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(state.files, key = { _, file -> file.absolutePath }) { index, file ->
                        val selected = state.selectedPaths.contains(file.absolutePath)
                        val focused = state.focusedIndex == index
                        val linkTarget = state.symlinkTargets[file.absolutePath]
                        val isLink = linkTarget != null
                        val isBroken = state.brokenLinks.contains(file.absolutePath)
                        val rowFavorite = state.favorites.any { it.path == file.absolutePath }
                        val resolved = remember(file.absolutePath) {
                            viewModel.resolveForOpen(file)
                        }
                        val (icon, tint) = entryIconAndTint(file, isLink, isBroken)
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
                                icon,
                                contentDescription = null,
                                tint = tint,
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
                                    text = entrySubtitle(file, isBroken, linkTarget),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isBroken) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box {
                                IconButton(onClick = { itemMenuFile = file }) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.filemanager_more)
                                    )
                                }
                                ItemMoreMenu(
                                    file = file,
                                    expanded = itemMenuFile == file,
                                    busy = state.busy,
                                    hasClipboard = state.hasClipboard,
                                    favorite = rowFavorite,
                                    onDismiss = { itemMenuFile = null },
                                    onOpenWithSystem = {
                                        itemMenuFile = null
                                        onOpenFile(resolved)
                                    },
                                    onOpenWithRootfs = if (onEditFile != null) {
                                        {
                                            itemMenuFile = null
                                            // RF-2 pre-check (spec 009): the pure
                                            // policy decides nano→vim availability;
                                            // the host session handles MAX_SESSIONS
                                            // and the guest path mapping.
                                            val candidates = TermuxConstants.DEBIAN_EDITOR_CANDIDATES
                                                .map { name ->
                                                    EditorCandidate(
                                                        name,
                                                        File(TermuxConstants.DEBIAN_ROOTFS_DIR_PATH + "/usr/bin/" + name)
                                                            .canExecute()
                                                    )
                                                }
                                            if (EditorPolicy.chooseEditor(candidates) == null) {
                                                viewModel.notifyNoRootfsEditor()
                                            } else {
                                                onEditFile(resolved)
                                            }
                                        }
                                    } else null,
                                    onRunInTerminal = if (onRunInTerminal != null) {
                                        {
                                            itemMenuFile = null
                                            // Pre-checks (rootfs, MAX_SESSIONS,
                                            // mappable path) are the host's, like
                                            // the Rootfs editor flow (RF-2).
                                            onRunInTerminal(file)
                                        }
                                    } else null,
                                    canExtract = !file.isDirectory &&
                                        ExtractPolicy.parse(file.name) != null,
                                    onExtract = {
                                        itemMenuFile = null
                                        // RF-6: status messages per outcome come
                                        // from the ViewModel; a password-needed
                                        // 7z opens the dialog for the one retry.
                                        viewModel.extractItem(file, null) { outcome ->
                                            if (outcome is ExtractionOutcome.PasswordRequired) {
                                                dialogFile = file
                                                dialog = DialogKind.EXTRACT_PASSWORD
                                            }
                                        }
                                    },
                                    onCut = {
                                        itemMenuFile = null
                                        viewModel.cutItem(file)
                                    },
                                    onCopy = {
                                        itemMenuFile = null
                                        viewModel.copyItem(file)
                                    },
                                    onPaste = {
                                        itemMenuFile = null
                                        viewModel.paste { count ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.msg_paste_success, count)
                                                )
                                            }
                                        }
                                    },
                                    onDelete = {
                                        itemMenuFile = null
                                        dialogFile = file
                                        dialog = DialogKind.DELETE
                                    },
                                    onRename = {
                                        itemMenuFile = null
                                        nameInput = file.name
                                        dialogFile = file
                                        dialog = DialogKind.RENAME
                                    },
                                    onCompress = {
                                        itemMenuFile = null
                                        dialogFile = file
                                        dialog = DialogKind.COMPRESS
                                    },
                                    onShare = {
                                        itemMenuFile = null
                                        onShareFiles(listOf(file))
                                    },
                                    onCopyPath = {
                                        itemMenuFile = null
                                        copyPathsToClipboard(listOf(file))
                                    },
                                    onToggleFavorite = {
                                        itemMenuFile = null
                                        if (rowFavorite) viewModel.removeFavorite(file.absolutePath)
                                        else viewModel.addFavoriteFor(file)
                                    },
                                    onCreateShortcut = if (onCreateShortcut != null) {
                                        {
                                            itemMenuFile = null
                                            onCreateShortcut(file)
                                        }
                                    } else null,
                                    onCreateFileShortcut = if (onCreateFileShortcut != null) {
                                        {
                                            itemMenuFile = null
                                            onCreateFileShortcut(file)
                                        }
                                    } else null,
                                    onProperties = {
                                        itemMenuFile = null
                                        dialogFile = file
                                        // Tabs-only properties dialog (T8, RF-12).
                                        dialog = DialogKind.PROPERTIES
                                    }
                                )
                            }
                        }
                    }
                }
                FileViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 112.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItemsIndexed(state.files, key = { _, file -> file.absolutePath }) { index, file ->
                        val selected = state.selectedPaths.contains(file.absolutePath)
                        val focused = state.focusedIndex == index
                        val linkTarget = state.symlinkTargets[file.absolutePath]
                        val isLink = linkTarget != null
                        val isBroken = state.brokenLinks.contains(file.absolutePath)
                        val (icon, tint) = entryIconAndTint(file, isLink, isBroken)
                        // Tile: same detection/selection/semantics as the row
                        // (spec 008, RF-17); adaptive 112dp columns.
                        Column(
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
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (state.selectionMode) {
                                Icon(
                                    if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                            }
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entrySubtitle(file, isBroken, linkTarget),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBroken) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = {
                                    dialogFile = file
                                    // The tile keeps its "Info" icon; it now
                                    // opens the tabs-only properties dialog
                                    // (spec 009, fuera de alcance del menú).
                                    dialog = DialogKind.PROPERTIES
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = stringResource(R.string.action_details),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
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
        DialogKind.PROPERTIES -> {
            val f = dialogFile
            if (f != null) {
                // Two-tab read-only snapshot (spec 009, RF-12); folder counts
                // and permissions load on demand, stale dialog data guards
                // against the entry path.
                LaunchedEffect(f.absolutePath) {
                    viewModel.loadProperties(f)
                    viewModel.loadFolderCounts(f)
                }
                PropertiesDialog(
                    file = f,
                    properties = state.properties?.takeIf { it.path == f.absolutePath },
                    counts = state.folderContents[f.absolutePath],
                    isFolder = f.isDirectory,
                    linkTarget = state.symlinkTargets[f.absolutePath],
                    isBroken = state.brokenLinks.contains(f.absolutePath),
                    onAccept = { dialog = DialogKind.NONE }
                )
            } else {
                dialog = DialogKind.NONE
            }
        }
        DialogKind.GO_TO -> {
            AlertDialog(
                onDismissRequest = { dialog = DialogKind.NONE },
                title = { Text(stringResource(R.string.filemanager_go_to_title)) },
                text = {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text(stringResource(R.string.filemanager_go_to_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = nameInput.trim()
                            dialog = DialogKind.NONE
                            if (target.isNotEmpty()) {
                                val dir = if (target.startsWith("/")) File(target)
                                else File(state.currentPath, target)
                                onEnsureStorageAccess(dir) { viewModel.goTo(target) }
                            }
                        },
                        enabled = nameInput.trim().isNotEmpty()
                    ) { Text(stringResource(R.string.filemanager_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = DialogKind.NONE }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }
        DialogKind.COMPRESS -> {
            val f = dialogFile
            if (f != null) {
                CompressDialog(
                    initial = f.name,
                    onDismiss = { dialog = DialogKind.NONE },
                    onConfirm = { name, format, password ->
                        val target = dialogFile
                        dialog = DialogKind.NONE
                        // Status messages per outcome (created/collision/
                        // invalid/failed) come from the ViewModel itself.
                        target?.let {
                            viewModel.compressItem(it, name, format, password) { }
                        }
                    }
                )
            } else {
                dialog = DialogKind.NONE
            }
        }
        DialogKind.EXTRACT_PASSWORD -> {
            val f = dialogFile
            if (f != null) {
                // RF-8 single retry: wrong password lands in the failed
                // status message; the dialog never opens back-to-back.
                ExtractPasswordDialog(
                    file = f,
                    onDismiss = { dialog = DialogKind.NONE },
                    onConfirm = { password ->
                        val target = dialogFile
                        dialog = DialogKind.NONE
                        target?.let { viewModel.extractItem(it, password) { } }
                    }
                )
            } else {
                dialog = DialogKind.NONE
            }
        }
        DialogKind.NONE -> Unit
    }
}

/**
 * The per-item "More" menu anchored to a list row (spec 009, RF-1), in the
 * fixed order: Open with… (files, submenu System/Rootfs), Cut, Copy, Paste
 * (conditional), Delete, Rename, Compress…, Share, Copy path, Favorites
 * (folders only), Create shortcut and Properties. Busy disables the
 * disk-touching entries; selection state is never modified.
 *
 * All actions carry the row's own [file]; "Open with" resolves symlinks on
 * its own (RF-13), everything else operates on the link.
 *
 * @param file The entry the menu acts on.
 * @param expanded Whether this row's menu is the open one.
 * @param busy Whether a file operation is in flight.
 * @param hasClipboard Whether paste has anything to paste (RF-4 conditional).
 * @param favorite Whether this folder is already a user favorite (RF-10).
 * @param onDismiss Closes the menu.
 * @param onCut Puts the item on the clipboard in cut mode.
 * @param onCopy Puts the item on the clipboard in copy mode.
 * @param onPaste Pastes the clipboard into the open folder.
 * @param onDelete Opens the trash confirmation for this item.
 * @param onRename Opens the rename dialog for this item.
 * @param onCompress Opens the compress dialog for this item (RF-7).
 * @param onShare Shares this item with the Android picker.
 * @param onCopyPath Copies the item's absolute path.
 * @param onToggleFavorite Adds/removes this folder to/from favorites.
 * @param onOpenWithSystem Opens this item's resolved destination with its
 * Android app ("Open with › System", RF-2).
 * @param onOpenWithRootfs Opens the resolved destination in the rootfs
 * editor after the nano→vim availability pre-check, or null when the host
 * does not wire an editor session (the option hides, 008 precedent).
 * @param onRunInTerminal Runs this file in a rootfs terminal session
 * (spec 023, RF-1), or null when the host does not wire a runner (hidden).
 * @param canExtract Whether the item's name matches an extractable archive
 * format (spec 023, RF-5).
 * @param onExtract Extracts this archive into its destination (RF-6).
 * @param onCreateShortcut Folder shortcut callback, or null when the host
 * does not wire it (element hidden, 008 precedent).
 * @param onCreateFileShortcut File shortcut callback (opens the file with
 * its Android app), or null when unwired (hidden, 008 precedent).
 * @param onProperties Opens the properties dialog for this item.
 */
@Composable
private fun ItemMoreMenu(
    file: File,
    expanded: Boolean,
    busy: Boolean,
    hasClipboard: Boolean,
    favorite: Boolean,
    onDismiss: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenWithSystem: () -> Unit,
    onOpenWithRootfs: (() -> Unit)?,
    onRunInTerminal: (() -> Unit)?,
    canExtract: Boolean,
    onExtract: () -> Unit,
    onCreateShortcut: (() -> Unit)?,
    onCreateFileShortcut: (() -> Unit)?,
    onProperties: () -> Unit
) {    val isFolder = remember(file.absolutePath) { file.isDirectory }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!isFolder) {
            // "Open with…" submenu (spec 009, RF-2): files only; the link is
            // resolved by the caller so even symlinks offer the target.
            var openWithExpanded by remember { mutableStateOf(false) }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_with)) },
                trailingIcon = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                onClick = { openWithExpanded = !openWithExpanded }
            )
            if (openWithExpanded) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open_with_system)) },
                    onClick = {
                        onDismiss()
                        onOpenWithSystem()
                    }
                )
                if (onOpenWithRootfs != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_with_rootfs)) },
                        onClick = {
                            onDismiss()
                            onOpenWithRootfs()
                        }
                    )
                }
            }
        }
        // "Run in terminal" for files (spec 023, RF-1): right after the
        // "Open with…" submenu, hidden while the host does not run a session.
        if (!isFolder && onRunInTerminal != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_run_in_terminal)) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                enabled = !busy,
                onClick = onRunInTerminal
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_cut)) },
            leadingIcon = { Icon(Icons.Default.ContentCut, null) },
            onClick = onCut
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_copy)) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
            onClick = onCopy
        )
        if (hasClipboard) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_paste)) },
                leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                enabled = !busy,
                onClick = onPaste
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            leadingIcon = { Icon(Icons.Default.Delete, null) },
            enabled = !busy,
            onClick = onDelete
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            leadingIcon = { Icon(Icons.Default.Edit, null) },
            enabled = !busy,
            onClick = onRename
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_compress)) },
            leadingIcon = { Icon(Icons.Default.Archive, null) },
            enabled = !busy,
            onClick = onCompress
        )
        // "Extract" for supported archive names (spec 023, RF-5/RF-6); the
        // destination and its collision refusal are the ViewModel's (RNF-5).
        if (!isFolder && canExtract) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_extract)) },
                leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                enabled = !busy,
                onClick = onExtract
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_share)) },
            leadingIcon = { Icon(Icons.Default.Share, null) },
            onClick = onShare
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_copy_path)) },
            leadingIcon = { Icon(Icons.Default.FileCopy, null) },
            onClick = onCopyPath
        )
        if (isFolder) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (favorite) stringResource(R.string.action_remove_favorite)
                        else stringResource(R.string.action_add_favorite)
                    )
                },
                leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                onClick = onToggleFavorite
            )
        }
        if (isFolder) {
            if (onCreateShortcut != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_create_shortcut)) },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = onCreateShortcut
                )
            }
        } else if (onCreateFileShortcut != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_create_shortcut)) },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = onCreateFileShortcut
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_details)) },
            leadingIcon = { Icon(Icons.Default.Info, null) },
            onClick = onProperties
        )
    }
}

/**
 * Two-tab read-only properties dialog (spec 009, RF-12): the *basic* tab
 * paints name, amble parent folder, MIME type, content (formatted size, or
 * the "N folders · M files" counts for folders), last modified, and for
 * symlinks the raw target plus broken state; the *permissions* tab paints
 * owner, group, mode (`rwxr-xr-x (644)`) and the SELinux context read via
 * lstat of the entry itself (RF-13). Any unreadable datum renders the "—"
 * dash without blocking the dialog. One accept button closes it.
 *
 * @param file The entry whose data the dialog shows.
 * @param properties Latest permissions snapshot for [file], null until (or
 * when) the read fails — dash rows meanwhile.
 * @param counts Folder content counts for [file], or null while loading.
 * @param isFolder Whether the entry is a folder (content as counts). * @param linkTarget Raw symlink target, null for non-links.
 * @param isBroken Whether the symlink target is missing.
 * @param onAccept Closes the dialog.
 */
@Composable
private fun PropertiesDialog(
    file: File,
    properties: FileProperties?,
    counts: ListingCounts.Counts?,
    isFolder: Boolean,
    linkTarget: String?,
    isBroken: Boolean,
    onAccept: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    val dash = stringResource(R.string.properties_unavailable)
    AlertDialog(
        onDismissRequest = onAccept,
        title = { Text(stringResource(R.string.properties_title, file.name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        text = { Text(stringResource(R.string.properties_tab_basic)) }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.properties_tab_permissions)) }
                    )
                }
                if (tab == 0) {
                    PropertyRow(
                        label = stringResource(R.string.properties_name),
                        value = file.name
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_parent),
                        value = BreadcrumbPolicy.crumbs(
                            file.parent ?: "/", TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH
                        ).lastOrNull()?.label ?: (file.parent ?: dash)
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_type),
                        value = FileOperationsHelper.getMimeType(file.name)
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_content),
                        value = if (isFolder) {
                            counts?.let { stringResource(R.string.filemanager_counts, it.folders, it.files) }
                                ?: stringResource(R.string.filemanager_calculating)
                        } else {
                            FileOperationsHelper.formatSize(remember(file.absolutePath) { file.length() })
                        }
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_last_modified),
                        value = remember(file.absolutePath) {
                            SimpleDateFormat
                                .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(file.lastModified()))
                        }
                    )
                    linkTarget?.let {
                        PropertyRow(
                            label = stringResource(R.string.properties_link),
                            value = it
                        )
                    }
                    if (isBroken) {
                        PropertyRow(
                            label = stringResource(R.string.properties_status),
                            value = stringResource(R.string.filemanager_broken_status),
                            isError = true
                        )
                    }
                } else {
                    PropertyRow(
                        label = stringResource(R.string.properties_owner),
                        value = properties?.owner ?: dash
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_group),
                        value = properties?.group ?: dash
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_mode),
                        value = properties?.modeText ?: dash
                    )
                    PropertyRow(
                        label = stringResource(R.string.properties_selinux),
                        value = properties?.selinuxContext ?: dash
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.filemanager_ok)) }
        }
    )
}

/**
 * One label/value line of the properties dialog; the value wraps so long
 * SELinux contexts stay readable.
 *
 * @param label Left column key.
 * @param value Right column value (never null: the caller pre-renders the
 * dash for unreadable data).
 * @param isError Whether the value paints in the error color (broken links).
 */
@Composable
private fun PropertyRow(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Dialog to compress an entry (spec 009, RF-7): archive name (blank keeps
 * the confirm disabled like the other name dialogs), three format radios and
 * an optional password field that only appears for the 7z format (the only
 * one that encrypts in this stack). Trimming and the collision check live in
 * {@link buildCompressionPlan}; the dialog hands over the raw inputs.
 *
 * @param initial Name the archive should get by default (the item's own).
 * @param onDismiss Called when the dialog is cancelled.
 * @param onConfirm Called with the trimmed name, the chosen format and the
 * optional password (null for the un-encrypted formats or a blank field).
 */
@Composable
private fun CompressDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String, ArchiveFormat, CharArray?) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_compress)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.compress_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                ArchiveFormat.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = format == option,
                            onClick = { format = option }
                        )
                        Text(
                            text = stringResource(
                                when (option) {
                                    ArchiveFormat.ZIP -> R.string.compress_format_zip
                                    ArchiveFormat.TAR_XZ -> R.string.compress_format_tarxz
                                    ArchiveFormat.SEVEN_Z -> R.string.compress_format_7z
                                }
                            )
                        )
                    }
                }
                if (format == ArchiveFormat.SEVEN_Z) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.compress_password_label)) },
                        singleLine = true,
                        // Masked input; the optionality is the label's wording.
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars =
                        if (format == ArchiveFormat.SEVEN_Z && password.isNotEmpty()) password.toCharArray()
                        else null
                    onConfirm(name.trim(), format, chars)
                },
                enabled = name.trim().isNotEmpty()
            ) { Text(stringResource(R.string.filemanager_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * Password prompt for an encrypted `.7z` the first extract attempt demanded
 * (spec 023, RF-8): one accept runs the single retry with the entered
 * password; a wrong one posts the failed status message and closes the
 * dialog (no back-to-back prompt).
 *
 * @param file The archive being extracted.
 * @param onDismiss Called when the dialog is cancelled.
 * @param onConfirm Called with the password from the (possibly empty) field
 * — an empty field keeps the earlier null-password shape.
 */
@Composable
private fun ExtractPasswordDialog(
    file: File,
    onDismiss: () -> Unit,
    onConfirm: (CharArray?) -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.extract_password_title, file.name)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.compress_password_label)) },
                singleLine = true,
                // Masked input; the retry is what makes it meaningful.
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Empty stays null so the retry keeps the null-password
                    // shape (a re-prompt would not be honored anyway, RF-8).
                    onConfirm(if (password.isNotEmpty()) password.toCharArray() else null)
                }
            ) { Text(stringResource(R.string.filemanager_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * One bookmarks-drawer entry (spec 008, RF-5): fixed bookmarks and user
 * favorites share the row shape; only favorites can be removed.
 *
 * @param label Friendly label (Home / SDCard / breadcrumb tail).
 * @param path Absolute path the entry navigates to.
 * @param favorite Whether the row is a user favorite (removable).
 * @param onClick Navigates to the entry folder.
 * @param onRemove Removes the favorite, or null for fixed bookmarks.
 */
@Composable
private fun DrawerBookmarkRow(
    label: String,
    path: String,
    favorite: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (favorite && onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_remove_favorite))
            }
        }
    }
}

/**
 * Scrolls [index] into view only when needed and never more than necessary
 * (spec 007): a fully visible row must not yank the page, a row cut by the
 * top edge aligns with it, a row cut by the bottom edge gets a minimal nudge
 * by its overflow, and an out-of-view row is docked against the nearest edge
 * (top when above; bottom when below, estimating the height from the last
 * visible row since rows are uniform in this list).
 *
 * @param index The row carrying the cursor highlight.
 */
private suspend fun LazyListState.ensureItemVisible(index: Int) {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        when {
            item.offset < info.viewportStartOffset -> animateScrollToItem(index)
            item.offset + item.size > info.viewportEndOffset ->
                animateScrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
            else -> Unit // Already fully visible: no movement.
        }
        return
    }
    if (index < info.visibleItemsInfo.first().index) {
        animateScrollToItem(index)
    } else {
        val last = info.visibleItemsInfo.last()
        val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
        scrollToItem(index, last.size - viewportHeight)
    }
}

/**
 * Grid twin of the list [ensureItemVisible] (spec 007 policy over grid
 * geometry, spec 008 RF-17): a fully visible cell never moves the page, a
 * cell cut by an edge gets the minimal nudge for its overflow, and an
 * out-of-view cell scrolls to dock against the nearest edge (top when
 * above; otherwise docks at the top of its row, the grid approximation —
 * cells below the viewport cannot be measured reliably with multi-row
 * spans, so the policy keeps the simpler top-dock instead of an estimate).
 *
 * @param index The cell index carrying the cursor highlight.
 */
private suspend fun LazyGridState.ensureItemVisible(index: Int) {
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (item != null) {
        when {
            item.offset.y < info.viewportStartOffset -> animateScrollToItem(index)
            item.offset.y + item.size.height > info.viewportEndOffset ->
                animateScrollBy(
                    (item.offset.y + item.size.height - info.viewportEndOffset).toFloat()
                )
            else -> Unit // Already fully visible: no movement.
        }
        return
    }
    if (index < info.visibleItemsInfo.first().index) {
        animateScrollToItem(index)
    } else {
        scrollToItem(index)
    }
}

/**
 * Shared entry icon + tint for the list row and the grid tile (spec 008,
 * RF-17): same link badge, folder, family/variant detection and broken-link
 * error color as the pre-grid rows.
 *
 * @param file The listed entry.
 * @param isLink Whether the entry is a symlink (raw target present).
 * @param isBroken Whether the symlink target is missing.
 * @return The icon vector and its tint for the entry.
 */
@Composable
private fun entryIconAndTint(
    file: File,
    isLink: Boolean,
    isBroken: Boolean
): Pair<ImageVector, Color> {
    if (isLink) {
        return Icons.Default.Link to if (isBroken) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.tertiary
    }
    val isDir = remember(file.absolutePath) { file.isDirectory }
    if (isDir) return Icons.Default.Folder to MaterialTheme.colorScheme.primary
    val family = remember(file.absolutePath) {
        FileTypeDetector.familyFor(file.name)
    }
    val variant = remember(file.absolutePath) {
        FileTypeDetector.iconVariantFor(file.name)
    }
    val icon = variant?.icon ?: family?.icon ?: Icons.Default.Description
    val tint = variant?.tint ?: family?.tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    return icon to tint
}

/**
 * Shared secondary line of an entry (folder / link target / broken / size)
 * for both list rows and grid tiles (spec 008, RF-17).
 *
 * @param file The listed entry.
 * @param isBroken Whether a dangling-link message wins the line.
 * @param linkTarget The raw symlink target, or null for non-links.
 * @return The subtitle text of the entry.
 */
@Composable
private fun entrySubtitle(
    file: File,
    isBroken: Boolean,
    linkTarget: String?
): String {
    val fileSize = remember(file.absolutePath) { file.length() }
    return when {
        isBroken -> stringResource(
            R.string.filemanager_broken_link, linkTarget ?: file.absolutePath
        )
        linkTarget != null -> stringResource(R.string.filemanager_link_target, linkTarget)
        file.isDirectory -> stringResource(R.string.filemanager_folder)
        else -> FileOperationsHelper.formatSize(fileSize)
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

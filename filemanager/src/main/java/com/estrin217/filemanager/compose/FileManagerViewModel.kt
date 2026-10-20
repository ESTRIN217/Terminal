package com.estrin217.filemanager.compose

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.estrin217.filemanager.FileOperationsHelper
import com.estrin217.filemanager.FileSortOption
import com.estrin217.filemanager.R
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
/**
 * ViewModel for the Compose file manager.
 *
 * Reuses [FileOperationsHelper] and [FileSortOption] for all IO so
 * behaviour matches the old `FileManagerActivity`. All filesystem work
 * (listing, create/rename/delete/copy/paste, undo) runs on Dispatchers.IO
 * via [viewModelScope]; callbacks fire on the main thread.
 *
 * @param application The host application for resources and prefs.
 * @param initialPath Optional folder this session opens at (launcher
 *                    shortcut, spec 008); ignored when not an existing
 *                    directory — the session then opens at the default.
 */
class FileManagerViewModel(
    application: Application,
    initialPath: String? = null
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()
    private var currentDir: File? = null

    /** Cancels the previous listing when a new one is requested. */
    private var refreshJob: Job? = null

    /**
     * Raw result of the last successful `listFiles` for [lastListedDir].
     *
     * Lets search/sort/hidden toggles re-filter in memory without re-enumerating
     * the directory (the expensive part of a keystroke-driven refresh).
     */
    private var lastListed: Array<File>? = null
    private var lastListedDir: String? = null

    /** Last trashed file + original path, for Snackbar undo. */
    var lastTrash: Pair<File, File>? = null
        private set

    /** Number of rows jumped on page up/down. */
    private val pageSize = 10

    private val prefsName = "file_manager"

    companion object {
        /** Preferences keys for the per-folder listing-preferences override (RF-8). */
        private const val KEY_SORT = "sort"
        private const val KEY_ASC = "asc"
        private const val KEY_FOLDERS_FIRST = "ff"
        private const val KEY_VIEW = "view"
        private const val KEY_GLOBAL_VIEW = "view_mode"
        private const val KEY_GLOBAL_FOLDERS_FIRST = "folders_first"
        private const val KEY_FAVORITES = "favorites"
    }

    /** Debian home path, the "Home" crumb rank of the bookmarks (RF-5). */
    private val homeDirectoryPath = TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH

    init {
        val prefs = sharedPrefs()
        val sort = try {
            FileSortOption.valueOf(
                prefs.getString("sort_option", FileSortOption.NAME.name)
                    ?: FileSortOption.NAME.name
            )
        } catch (e: Exception) {
            FileSortOption.NAME
        }
        _uiState.update {
            it.copy(
                sortOption = sort,
                sortAscending = prefs.getBoolean("sort_ascending", true),
                showHidden = prefs.getBoolean("show_hidden", false),
                foldersFirst = prefs.getBoolean(KEY_GLOBAL_FOLDERS_FIRST, true),
                viewMode = runCatching {
                    FileViewMode.valueOf(prefs.getString(KEY_GLOBAL_VIEW, FileViewMode.LIST.name)!!)
                }.getOrDefault(FileViewMode.LIST),
                favorites = loadFavorites()
            )
        }
        // Launcher shortcut cold start (spec 008, RF-16): open at the pinned
        // folder when it is a real directory, the default otherwise.
        val start = initialPath?.let { path -> File(path).takeIf { it.isDirectory } }
        navigateTo(start ?: defaultDir())
    }

    private fun defaultDir(): File {
        val debianHome = File(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH)
        if (debianHome.isDirectory) return debianHome
        return Environment.getExternalStorageDirectory()
    }

    private fun persistPrefs() {
        val editor = sharedPrefs().edit()
        storeGlobalPreferences(editor)
        editor.apply()
    }

    /** The shared preferences file backing all listing preferences. */
    private fun sharedPrefs() =
        getApplication<Application>().getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Writes the global listing-preferences keys from the current state. */
    private fun storeGlobalPreferences(editor: android.content.SharedPreferences.Editor) {
        val s = _uiState.value
        editor.putString("sort_option", s.sortOption.name)
        editor.putBoolean("sort_ascending", s.sortAscending)
        editor.putBoolean("show_hidden", s.showHidden)
        editor.putBoolean(KEY_GLOBAL_FOLDERS_FIRST, s.foldersFirst)
        editor.putString(KEY_GLOBAL_VIEW, s.viewMode.name)
    }

    /** Per-folder override key prefix for [dir], like `folder:/sdcard:`. */
    private fun folderPrefix(dir: File) = "folder:" + dir.absolutePath + ":"

    /** Whether [dir] carries its own Organize preferences override (RF-8). */
    private fun hasFolderOverride(dir: File) =
        sharedPrefs().contains(folderPrefix(dir) + KEY_SORT)

    /** Stores the current listing preferences into [dir]'s override keys. */
    private fun storeFolderPreferences(editor: android.content.SharedPreferences.Editor, dir: File) {
        val prefix = folderPrefix(dir)
        val s = _uiState.value
        editor.putString(prefix + KEY_SORT, s.sortOption.name)
        editor.putBoolean(prefix + KEY_ASC, s.sortAscending)
        editor.putBoolean(prefix + KEY_FOLDERS_FIRST, s.foldersFirst)
        editor.putString(prefix + KEY_VIEW, s.viewMode.name)
    }

    /** Removes every listing-preferences key of [dir] (RF-8 scope off). */
    private fun clearFolderPreferences(editor: android.content.SharedPreferences.Editor, dir: File) {
        val prefix = folderPrefix(dir)
        editor.remove(prefix + KEY_SORT)
        editor.remove(prefix + KEY_ASC)
        editor.remove(prefix + KEY_FOLDERS_FIRST)
        editor.remove(prefix + KEY_VIEW)
    }

    /**
     * Applies the listing preferences of [dir] to the state: the folder
     * override when it exists, the global values otherwise (RF-8); also
     * refreshes the folder-scoped and favorite flags.
     */
    private fun applyFolderPreferences(dir: File) {
        val prefs = sharedPrefs()
        val prefix = folderPrefix(dir)
        val hasOverride = prefs.contains(prefix + KEY_SORT)
        _uiState.update { state ->
            if (hasOverride) {
                state.copy(
                    sortOption = runCatching {
                        FileSortOption.valueOf(prefs.getString(prefix + KEY_SORT, state.sortOption.name)!!)
                    }.getOrDefault(state.sortOption),
                    sortAscending = prefs.getBoolean(prefix + KEY_ASC, state.sortAscending),
                    foldersFirst = prefs.getBoolean(prefix + KEY_FOLDERS_FIRST, state.foldersFirst),
                    viewMode = runCatching {
                        FileViewMode.valueOf(prefs.getString(prefix + KEY_VIEW, state.viewMode.name)!!)
                    }.getOrDefault(state.viewMode),
                    folderScoped = true,
                    isFavorite = state.favorites.any { it.path == dir.absolutePath }
                )
            } else {
                state.copy(
                    sortOption = runCatching {
                        FileSortOption.valueOf(prefs.getString("sort_option", state.sortOption.name)!!)
                    }.getOrDefault(state.sortOption),
                    sortAscending = prefs.getBoolean("sort_ascending", state.sortAscending),
                    foldersFirst = prefs.getBoolean(KEY_GLOBAL_FOLDERS_FIRST, state.foldersFirst),
                    viewMode = runCatching {
                        FileViewMode.valueOf(prefs.getString(KEY_GLOBAL_VIEW, state.viewMode.name)!!)
                    }.getOrDefault(state.viewMode),
                    folderScoped = false,
                    isFavorite = state.favorites.any { it.path == dir.absolutePath }
                )
            }
        }
    }

    /**
     * Applies a listing-preferences change from the Organize menu: without
     * a folder override it persists the global keys (RF-8), with one it
     * persists the current folder's override; then re-sorts in memory.
     */
    private fun updateListingPrefs() {
        val dir = currentDir ?: return
        val editor = sharedPrefs().edit()
        if (hasFolderOverride(dir)) storeFolderPreferences(editor, dir)
        else storeGlobalPreferences(editor)
        editor.apply()
        reapplyCachedListing()
    }

    /** Selects a sort criteria from the Organize menu (RF-7), keeping the direction. */
    fun setSortOption(option: FileSortOption) {
        _uiState.update { it.copy(sortOption = option) }
        updateListingPrefs()
    }

    /** Sets the sort direction from the Organize menu (RF-7). */
    fun setSortAscending(ascending: Boolean) {
        _uiState.update { it.copy(sortAscending = ascending) }
        updateListingPrefs()
    }

    /** Toggles the folders-first grouping from the Organize menu (RF-7). */
    fun setFoldersFirst(foldersFirst: Boolean) {
        _uiState.update { it.copy(foldersFirst = foldersFirst) }
        updateListingPrefs()
    }

    /** Selects the listing presentation from the Organize menu (RF-7). */
    fun setViewMode(viewMode: FileViewMode) {
        _uiState.update { it.copy(viewMode = viewMode) }
        updateListingPrefs()
    }

    /**
     * Toggles "this folder only": on activation the folder snapshots the
     * values in effect as its override, on deactivation the override is
     * cleared and the folder falls back to the global values (RF-8).
     */
    fun setFolderScoped(scoped: Boolean) {
        val dir = currentDir ?: return
        val editor = sharedPrefs().edit()
        if (scoped) storeFolderPreferences(editor, dir)
        else clearFolderPreferences(editor, dir)
        editor.apply()
        _uiState.update { it.copy(folderScoped = scoped) }
        if (!scoped) applyFolderPreferences(dir)
    }

    fun bookmarkDirs(): List<Pair<String, File>> {
        val list = mutableListOf<Pair<String, File>>()
        val debianHome = File(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH)
        if (debianHome.isDirectory) list.add("Home" to debianHome)
        list.add("SDCard" to Environment.getExternalStorageDirectory())
        list.add("Root" to File("/"))
        return list
    }

    /** Loads the user favorites into drawer crumbs (label + path, RF-5). */
    private fun loadFavorites(): List<BreadcrumbPolicy.Crumb> =
        favoritePaths().sorted().map { path ->
            BreadcrumbPolicy.Crumb(friendlyLabel(path), path)
        }

    /** The persisted favorite paths (RF-6). */
    private fun favoritePaths(): Set<String> =
        sharedPrefs().getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    /** Friendly label of a favorite: its breadcrumb tail (Home/Root/name). */
    private fun friendlyLabel(path: String): String =
        BreadcrumbPolicy.crumbs(path, homeDirectoryPath).lastOrNull()?.label ?: path

    /** Adds the open folder to the persisted user favorites (RF-6). */
    fun addFavorite() {
        val dir = currentDir ?: return
        val editor = sharedPrefs().edit()
        editor.putStringSet(KEY_FAVORITES, favoritePaths() + dir.absolutePath)
        editor.apply()
        _uiState.update {
            it.copy(
                favorites = loadFavorites(),
                isFavorite = true
            )
        }
    }

    /** Removes a favorite path from the persisted user favorites (RF-5/RF-6). */
    fun removeFavorite(path: String) {
        if (path !in favoritePaths()) return
        val editor = sharedPrefs().edit()
        editor.putStringSet(KEY_FAVORITES, favoritePaths() - path)
        editor.apply()
        _uiState.update { state ->
            state.copy(
                favorites = loadFavorites(),
                isFavorite = if (state.currentPath == path) false else state.isFavorite
            )
        }
    }

    fun navigateTo(dir: File) {
        if (!FileOperationsHelper.isSharedStoragePath(dir) &&
            FileOperationsHelper.isBrokenSymlink(dir)
        ) {
            notifyBrokenSymlink(dir)
            return
        }
        // Shared storage must keep its FUSE-accessible path (/sdcard,
        // /storage/emulated/0): resolving the symlink chain would land on
        // /storage/self/primary or /mnt/user/0/... which java.io cannot list.
        // Other symlinks resolve to their canonical target so the breadcrumb
        // shows the real location and symlink loops cannot nest.
        val resolved = if (FileOperationsHelper.isSharedStoragePath(dir)) dir
        else FileOperationsHelper.resolveFileForOpen(dir)
        currentDir?.let {
            backStack.addLast(it.absolutePath)
            forwardStack.clear()
        }
        navigateToInternal(resolved, pushHistory = false, clearSearch = true)
    }

    fun goBack() {
        if (backStack.isEmpty()) return
        currentDir?.let { forwardStack.addLast(it.absolutePath) }
        val path = backStack.removeLast()
        navigateToInternal(File(path), pushHistory = false, clearSearch = true)
    }

    fun goForward() {
        if (forwardStack.isEmpty()) return
        currentDir?.let { backStack.addLast(it.absolutePath) }
        val path = forwardStack.removeLast()
        navigateToInternal(File(path), pushHistory = false, clearSearch = true)
    }

    fun goUp(): Boolean {
        val parent = currentDir?.parentFile ?: return false
        if (!parent.exists() && !FileOperationsHelper.isSharedStoragePath(parent)) return false
        currentDir?.let {
            backStack.addLast(it.absolutePath)
            forwardStack.clear()
        }
        navigateToInternal(parent, pushHistory = false, clearSearch = true)
        return true
    }

    /**
     * Navigates to the user-typed "Go to…" target (RF-12): absolute paths
     * are used as-is, relative ones against the open folder. A target that
     * is not an existing directory publishes the cannot-read status and
     * keeps the current folder.
     *
     * @param text The path text typed in the Go to dialog.
     */
    fun goTo(text: String) {
        val trimmed = text.trim()
        val base = currentDir ?: return
        if (trimmed.isEmpty()) return
        val target = if (trimmed.startsWith("/")) File(trimmed) else File(base, trimmed)
        if (!target.isDirectory) {
            _uiState.update {
                it.copy(statusMessage = getApplication<Application>().getString(
                    R.string.filemanager_error_cannot_read, target.absolutePath))
            }
            return
        }
        navigateTo(target)
    }

    /** Back press handling: exit selection first, then history/up. */
    fun onBackPressed(): Boolean {
        val s = _uiState.value
        if (s.selectionMode) {
            clearSelection()
            return true
        }
        if (backStack.isNotEmpty()) {
            goBack()
            return true
        }
        return goUp()
    }

    private fun navigateToInternal(dir: File, pushHistory: Boolean, clearSearch: Boolean) {
        if (!dir.exists() && !FileOperationsHelper.isSharedStoragePath(dir)) {
            if (FileOperationsHelper.isBrokenSymlink(dir)) notifyBrokenSymlink(dir)
            return
        }
        // Shared storage keeps its own path; everything else is canonicalized so
        // the breadcrumb shows the real location and symlink loops cannot nest.
        val isShared = FileOperationsHelper.isSharedStoragePath(dir)
        val canonical = if (isShared) dir else try {
            dir.canonicalFile
        } catch (e: Exception) {
            dir
        }
        val target = if (isShared) dir
        else if (canonical.isDirectory) canonical else canonical.parentFile ?: return
        currentDir = target
        applyFolderPreferences(target)
        if (clearSearch) _uiState.update { it.copy(searchQuery = "") }
        refresh()
    }

    /**
     * Relists the current directory off the main thread and publishes the
     * result to the [uiState] flow.
     *
     * <p>Enumeration, filter, sort and the symlink scan all run on
     * {@code Dispatchers.IO}; only the final [MutableStateFlow.update] hops to
     * the main thread. A stale result from an outdated navigation is discarded
     * when [currentDir] no longer matches the listed directory.
     * [FileManagerUiState.busy] stays set until the listing is applied.</p>
     */
    fun refresh() {
        val dir = currentDir ?: return
        _uiState.update { it.copy(busy = true) }
        refreshJob?.cancel()
        // Drop the cache so a concurrent search/sort reapply cannot cancel this
        // full relist and serve a listing that predates a file mutation.
        lastListed = null
        lastListedDir = null
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val listed = FileOperationsHelper.listFiles(dir)
            if (!isActive) return@launch
            if (listed != null) {
                lastListed = listed
                lastListedDir = dir.absolutePath
            }
            applyListing(dir, listed)
        }
    }

    /**
     * Re-applies filter/sort/symlink scan from [lastListed] when it still matches
     * the current directory; otherwise falls back to a full [refresh].
     */
    private fun reapplyCachedListing() {
        val dir = currentDir ?: return
        val cached = lastListed
        if (cached == null || lastListedDir != dir.absolutePath) {
            refresh()
            return
        }
        _uiState.update { it.copy(busy = true) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            applyListing(dir, cached)
        }
    }

    /**
     * Filters, sorts and scans [listed] on the calling (IO) dispatcher, then
     * publishes the result on the main thread. [listed] of `null` publishes the
     * unreadable-directory error state.
     */
    private suspend fun applyListing(dir: File, listed: Array<File>?) {
        if (listed == null) {
            withContext(Dispatchers.Main) {
                if (currentDir != dir) return@withContext
                _uiState.update {
                    it.copy(
                        busy = false,
                        currentPath = dir.absolutePath,
                        title = titleFor(dir),
                        files = emptyList(),
                        focusedIndex = -1,
                        canGoBack = backStack.isNotEmpty(),
                        canGoForward = forwardStack.isNotEmpty(),
                        canGoUp = canGoUpFrom(dir),
                        folderCount = 0,
                        fileCount = 0,
                        hasClipboard = FileOperationsHelper.hasClipboard(),
                        statusMessage = if (FileOperationsHelper.isSharedStoragePath(dir))
                            getApplication<Application>().getString(R.string.filemanager_error_cannot_read_access, dir.absolutePath)
                        else
                            getApplication<Application>().getString(R.string.filemanager_error_cannot_read, dir.absolutePath)
                    )
                }
            }
            return
        }
        val s = _uiState.value
        val visible = listed.filter { s.showHidden || !it.name.startsWith(".") }
        val counts = ListingCounts.counts(listed.toList(), s.showHidden)
        val filtered = if (s.searchQuery.isEmpty()) visible
        else visible.filter { it.name.contains(s.searchQuery, ignoreCase = true) }
        val sorted = filtered.sortedWith(
            s.sortOption.getComparator(s.sortAscending, s.foldersFirst)
        )
        val symlinkTargets = HashMap<String, String?>()
        val brokenLinks = HashSet<String>()
        for (f in listed) {
            val raw = FileOperationsHelper.readSymlinkTargetRaw(f)
            if (raw != null) {
                symlinkTargets[f.absolutePath] = raw
                if (FileOperationsHelper.isBrokenSymlink(f)) brokenLinks.add(f.absolutePath)
            }
        }
        withContext(Dispatchers.Main) {
            if (currentDir != dir) return@withContext
            _uiState.update {
                // The cursor policy unsets it on a directory change (a
                // phantom carry-over made new folders auto-scroll) and keeps
                // it only for same-directory re-listings (spec 007).
                val normalized = FocusCursorPolicy.normalizedIndex(
                    previous = it.focusedIndex,
                    previousPath = it.currentPath,
                    listedPath = dir.absolutePath,
                    size = sorted.size
                )
                it.copy(
                    busy = false,
                    currentPath = dir.absolutePath,
                    title = titleFor(dir),
                    files = sorted,
                    focusedIndex = normalized,
                    canGoBack = backStack.isNotEmpty(),
                    canGoForward = forwardStack.isNotEmpty(),
                    canGoUp = canGoUpFrom(dir),
                    folderCount = counts.folders,
                    fileCount = counts.files,
                    hasClipboard = FileOperationsHelper.hasClipboard(),
                    symlinkTargets = symlinkTargets,
                    brokenLinks = brokenLinks
                )
            }
        }
    }

    private fun titleFor(dir: File): String {
        if (dir.absolutePath == "/") return "Root"
        if (dir.absolutePath == TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH) return "Home"
        if (dir.name == "home" && (dir.parent ?: "").endsWith("/files")) return "Home"
        return dir.name.ifEmpty { dir.absolutePath }
    }

    /**
     * Whether [dir] has a navigable parent (RF-11 "succumb" enablement):
     * the root folder has none, and a shared-storage folder can always go
     * up because its parent chain stays FUSE-accessible.
     *
     * @param dir The open folder.
     * @return Whether the Subir menu entry should be enabled.
     */
    private fun canGoUpFrom(dir: File): Boolean {
        val parent = dir.parentFile ?: return false
        return parent.exists() || FileOperationsHelper.isSharedStoragePath(dir)
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        reapplyCachedListing()
    }

    fun toggleSort(option: FileSortOption) {
        _uiState.update {
            if (it.sortOption == option) it.copy(sortAscending = !it.sortAscending)
            else it.copy(sortOption = option, sortAscending = true)
        }
        persistPrefs()
        reapplyCachedListing()
    }

    fun toggleHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
        persistPrefs()
        reapplyCachedListing()
    }

    /**
     * Move the focused list entry by [delta] rows (clamped to the visible list).
     * Delegates the index math to [FocusCursorPolicy.movedIndex]: an unset
     * cursor counts as one step before the first row (spec 007).
     *
     * @param delta How many rows to move (negative moves up).
     */
    fun moveFocus(delta: Int) {
        val next = FocusCursorPolicy.movedIndex(
            current = _uiState.value.focusedIndex,
            delta = delta,
            size = _uiState.value.files.size
        ) ?: return
        setFocusedIndex(next)
    }

    /**
     * Focus the first visible entry.
     */
    fun focusHome() {
        setFocusedIndex(0)
    }

    /**
     * Focus the last visible entry.
     */
    fun focusEnd() {
        setFocusedIndex(_uiState.value.files.size - 1)
    }

    /**
     * Page up/down: jump [pageSize] rows at a time.
     *
     * @param sign {@code -1} for page up, {@code +1} for page down.
     */
    fun pageJump(sign: Int) {
        moveFocus(pageSize * sign)
    }

    /**
     * Set the focused list row, clamped to the visible list.
     *
     * @param index The desired row index.
     */
    fun setFocusedIndex(index: Int) {
        val size = _uiState.value.files.size
        if (size == 0) return
        _uiState.update { it.copy(focusedIndex = index.coerceIn(0, size - 1)) }
    }

    /**
     * The currently focused entry, or {@code null} when the list is empty.
     */
    val focusedFile: File?
        get() = _uiState.value.files.getOrNull(_uiState.value.focusedIndex)

    /**
     * Toggle the selection state of the focused entry (used by Tab / extra keys).
     */
    fun toggleFocusedSelection() {
        val file = focusedFile ?: return
        toggleSelection(file.absolutePath)
    }

    fun toggleSelection(path: String) {
        _uiState.update { state ->
            val selected = state.selectedPaths.toMutableSet()
            if (!selected.add(path)) selected.remove(path)
            state.copy(selectedPaths = selected, selectionMode = selected.isNotEmpty())
        }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(
                selectedPaths = it.files.map { f -> f.absolutePath }.toSet(),
                selectionMode = it.files.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedPaths = emptySet(), selectionMode = false) }
    }

    fun selectedFiles(): List<File> =
        _uiState.value.selectedPaths.map(::File)

    /**
     * Creates a folder in the current directory off the main thread.
     *
     * @param name Name of the new folder.
     * @param onDone Invoked on the main thread with {@code true} on success.
     */
    fun createFolder(name: String, onDone: (Boolean) -> Unit) {
        val dir = currentDir ?: run { onDone(false); return }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FileOperationsHelper.createDirectory(dir, name.trim())
            withContext(Dispatchers.Main) {
                refresh()
                onDone(ok)
            }
        }
    }

    /**
     * Creates an empty file in the current directory off the main thread.
     *
     * @param name Name of the new file.
     * @param onDone Invoked on the main thread with {@code true} on success.
     */
    fun createFile(name: String, onDone: (Boolean) -> Unit) {
        val dir = currentDir ?: run { onDone(false); return }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FileOperationsHelper.createFile(dir, name.trim())
            withContext(Dispatchers.Main) {
                refresh()
                onDone(ok)
            }
        }
    }

    /**
     * Creates a symlink in the current directory off the main thread.
     *
     * @param name Name of the new link.
     * @param target Link target (absolute or relative to the current dir).
     * @param onDone Invoked on the main thread with {@code true} on success.
     */
    fun createSymlink(name: String, target: String, onDone: (Boolean) -> Unit) {
        val dir = currentDir ?: run { onDone(false); return }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FileOperationsHelper.createSymlink(dir, name.trim(), target.trim())
            withContext(Dispatchers.Main) {
                refresh()
                onDone(ok)
            }
        }
    }

    /**
     * Resolves a tapped file to what should actually be opened: the canonical
     * target for healthy symlinks, the file itself otherwise.
     *
     * @param file The tapped file.
     * @return The file to open or navigate to.
     */
    fun resolveForOpen(file: File): File =
        FileOperationsHelper.resolveFileForOpen(file)

    /**
     * Computes the size of a folder off the main thread and caches it in
     * [FileManagerUiState.folderSizes] keyed by absolute path. Files (and
     * already-cached folders) are a no-op.
     *
     * @param file The folder to measure.
     */
    fun loadFolderSize(file: File) {
        if (!file.isDirectory) return
        if (_uiState.value.folderSizes.containsKey(file.absolutePath)) return
        viewModelScope.launch(Dispatchers.IO) {
            val size = FileOperationsHelper.getDirectorySize(file)
            _uiState.update {
                it.copy(folderSizes = it.folderSizes + (file.absolutePath to size))
            }
        }
    }

    /**
     * Posts a "broken symlink" status message for [file].
     *
     * @param file The dangling symlink the user tried to open.
     */
    fun notifyBrokenSymlink(file: File) {
        val raw = FileOperationsHelper.readSymlinkTargetRaw(file)
        _uiState.update {
            it.copy(statusMessage = getApplication<Application>().getString(R.string.filemanager_error_broken_symlink, file.name, raw ?: "?"))
        }
    }

    /** Posts a "share failed" status message (RF-14 fallback). */
    fun notifyShareFailed() {
        _uiState.update {
            it.copy(statusMessage = getApplication<Application>().getString(R.string.msg_share_failed))
        }
    }

    /**
     * Renames [file] off the main thread.
     *
     * @param file The file to rename.
     * @param newName The new name (trimmed before use).
     * @param onDone Invoked on the main thread with {@code true} on success.
     */
    fun renameFile(file: File, newName: String, onDone: (Boolean) -> Unit) {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FileOperationsHelper.renameFile(file, newName.trim())
            withContext(Dispatchers.Main) {
                refresh()
                onDone(ok)
            }
        }
    }

    fun copySelection() {
        FileOperationsHelper.setClipboard(
            selectedFiles(), FileOperationsHelper.ClipboardOperation.COPY
        )
        clearSelection()
        refresh()
    }

    fun cutSelection() {
        FileOperationsHelper.setClipboard(
            selectedFiles(), FileOperationsHelper.ClipboardOperation.CUT
        )
        clearSelection()
        refresh()
    }

    fun deleteFiles(files: List<File>, onDone: (ok: Boolean) -> Unit) {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var ok = true
            var trashed: Pair<File, File>? = null
            for (f in files) {
                val trash = moveToTrash(f)
                if (trash == null) {
                    ok = false
                } else {
                    trashed = trash to f
                }
            }
            withContext(Dispatchers.Main) {
                if (trashed != null) lastTrash = trashed
                clearSelection()
                refresh()
                onDone(ok)
            }
        }
    }

    fun undoDelete(onDone: (Boolean) -> Unit) {
        val entry = lastTrash ?: run { onDone(false); return }
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = FileOperationsHelper.moveFile(entry.first, entry.second)
            withContext(Dispatchers.Main) {
                if (ok) lastTrash = null
                refresh()
                onDone(ok)
            }
        }
    }

    private fun moveToTrash(file: File): File? {
        val trashDir = File(getApplication<Application>().cacheDir, "fm_trash")
        if (!trashDir.exists()) trashDir.mkdirs()
        var trashFile = File(trashDir, file.name)
        var i = 1
        while (trashFile.exists()) {
            trashFile = File(trashDir, file.name + "." + i)
            i++
        }
        return if (FileOperationsHelper.moveFile(file, trashFile)) trashFile else null
    }

    fun paste(onDone: (count: Int) -> Unit) {
        if (!FileOperationsHelper.hasClipboard()) return
        val dest = currentDir ?: return
        val sources = FileOperationsHelper.getClipboardFiles().toList()
        val isCut = FileOperationsHelper.getClipboardOperation() ==
            FileOperationsHelper.ClipboardOperation.CUT
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            for (src in sources) {
                val target = File(dest, src.name)
                val ok = if (isCut) FileOperationsHelper.moveFile(src, target)
                else FileOperationsHelper.copyFile(src, target)
                if (ok) count++
            }
            if (isCut) FileOperationsHelper.clearClipboard()
            withContext(Dispatchers.Main) {
                refresh()
                onDone(count)
            }
        }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}

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
 */
class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()
    private var currentDir: File? = null

    /** Cancels the previous listing when a new one is requested. */
    private var refreshJob: Job? = null

    /** Last trashed file + original path, for Snackbar undo. */
    var lastTrash: Pair<File, File>? = null
        private set

    /** Number of rows jumped on page up/down. */
    private val pageSize = 10

    private val prefsName = "file_manager"

    init {
        val prefs = application.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
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
                showHidden = prefs.getBoolean("show_hidden", false)
            )
        }
        navigateToInternal(defaultDir(), pushHistory = false, clearSearch = true)
    }

    private fun defaultDir(): File {
        val debianHome = File(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH)
        if (debianHome.isDirectory) return debianHome
        return Environment.getExternalStorageDirectory()
    }

    private fun persistPrefs() {
        val s = _uiState.value
        getApplication<Application>().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("sort_option", s.sortOption.name)
            .putBoolean("sort_ascending", s.sortAscending)
            .putBoolean("show_hidden", s.showHidden)
            .apply()
    }

    fun bookmarkDirs(): List<Pair<String, File>> {
        val list = mutableListOf<Pair<String, File>>()
        val debianHome = File(TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH)
        if (debianHome.isDirectory) list.add("Home" to debianHome)
        list.add("SDCard" to Environment.getExternalStorageDirectory())
        list.add("Root" to File("/"))
        return list
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
        if (clearSearch) _uiState.update { it.copy(searchQuery = "") }
        refresh()
    }

    /**
     * Relists the current directory off the main thread and publishes the
     * result to the [uiState] flow.
     *
     * <p>The listing (enumerate, filter, sort, symlink scan) runs on
     * {@code Dispatchers.IO}; a stale result from an outdated navigation is
     * discarded when [currentDir] no longer matches the listed directory.
     * [FileManagerUiState.busy] stays set until the listing is applied.</p>
     */
    fun refresh() {
        val dir = currentDir ?: return
        _uiState.update { it.copy(busy = true) }
        val s = _uiState.value
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val listed = FileOperationsHelper.listFiles(dir)
            withContext(Dispatchers.Main) {
                if (currentDir != dir) return@withContext
                if (listed == null) {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            currentPath = dir.absolutePath,
                            title = titleFor(dir),
                            files = emptyList(),
                            focusedIndex = -1,
                            canGoBack = backStack.isNotEmpty(),
                            canGoForward = forwardStack.isNotEmpty(),
                            hasClipboard = FileOperationsHelper.hasClipboard(),
                            statusMessage = if (FileOperationsHelper.isSharedStoragePath(dir))
                                getApplication<Application>().getString(R.string.filemanager_error_cannot_read_access, dir.absolutePath)
                            else
                                getApplication<Application>().getString(R.string.filemanager_error_cannot_read, dir.absolutePath)
                        )
                    }
                    return@withContext
                }
                val visible = listed.filter { s.showHidden || !it.name.startsWith(".") }
                val filtered = if (s.searchQuery.isEmpty()) visible
                else visible.filter { it.name.contains(s.searchQuery, ignoreCase = true) }
                val sorted = filtered.sortedWith(s.sortOption.getComparator(s.sortAscending))
                val symlinkTargets = HashMap<String, String?>()
                val brokenLinks = HashSet<String>()
                for (f in listed) {
                    val raw = FileOperationsHelper.readSymlinkTargetRaw(f)
                    if (raw != null) {
                        symlinkTargets[f.absolutePath] = raw
                        if (FileOperationsHelper.isBrokenSymlink(f)) brokenLinks.add(f.absolutePath)
                    }
                }
                _uiState.update {
                    // Normalize the focused cursor to the new listing: keep the current
                    // position when it still fits, else fall back to the first entry.
                    val normalized = if (sorted.isEmpty()) -1
                    else it.focusedIndex.coerceIn(0, sorted.lastIndex)
                    it.copy(
                        busy = false,
                        currentPath = dir.absolutePath,
                        title = titleFor(dir),
                        files = sorted,
                        focusedIndex = normalized,
                        canGoBack = backStack.isNotEmpty(),
                        canGoForward = forwardStack.isNotEmpty(),
                        hasClipboard = FileOperationsHelper.hasClipboard(),
                        symlinkTargets = symlinkTargets,
                        brokenLinks = brokenLinks
                    )
                }
            }
        }
    }

    private fun titleFor(dir: File): String {
        if (dir.absolutePath == "/") return "Root"
        if (dir.absolutePath == TermuxConstants.DEBIAN_GUEST_HOME_DIR_PATH) return "Home"
        if (dir.name == "home" && (dir.parent ?: "").endsWith("/files")) return "Home"
        return dir.name.ifEmpty { dir.absolutePath }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refresh()
    }

    fun toggleSort(option: FileSortOption) {
        _uiState.update {
            if (it.sortOption == option) it.copy(sortAscending = !it.sortAscending)
            else it.copy(sortOption = option, sortAscending = true)
        }
        persistPrefs()
        refresh()
    }

    fun toggleHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
        persistPrefs()
        refresh()
    }

    /**
     * Move the focused list entry by [delta] rows (clamped to the visible list).
     *
     * @param delta How many rows to move (negative moves up).
     */
    fun moveFocus(delta: Int) {
        val size = _uiState.value.files.size
        if (size == 0) return
        val current = _uiState.value.focusedIndex.coerceAtLeast(0)
        setFocusedIndex((current + delta).coerceIn(0, size - 1))
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

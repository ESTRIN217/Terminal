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
 * behaviour matches the old `FileManagerActivity`. Paste runs on
 * Dispatchers.IO via [viewModelScope] instead of raw threads.
 */
class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val backStack = ArrayDeque<String>()
    private val forwardStack = ArrayDeque<String>()
    private var currentDir: File? = null

    /** Last trashed file + original path, for Snackbar undo. */
    var lastTrash: Pair<File, File>? = null
        private set

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

    fun refresh() {
        val dir = currentDir ?: return
        val s = _uiState.value
        val listed = FileOperationsHelper.listFiles(dir)
        if (listed == null) {
            _uiState.update {
                it.copy(
                    currentPath = dir.absolutePath,
                    title = titleFor(dir),
                    files = emptyList(),
                    canGoBack = backStack.isNotEmpty(),
                    canGoForward = forwardStack.isNotEmpty(),
                    hasClipboard = FileOperationsHelper.hasClipboard(),
                    statusMessage = if (FileOperationsHelper.isSharedStoragePath(dir))
                        getApplication<Application>().getString(R.string.filemanager_error_cannot_read_access, dir.absolutePath)
                    else
                        getApplication<Application>().getString(R.string.filemanager_error_cannot_read, dir.absolutePath)
                )
            }
            return
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
            it.copy(
                currentPath = dir.absolutePath,
                title = titleFor(dir),
                files = sorted,
                canGoBack = backStack.isNotEmpty(),
                canGoForward = forwardStack.isNotEmpty(),
                hasClipboard = FileOperationsHelper.hasClipboard(),
                symlinkTargets = symlinkTargets,
                brokenLinks = brokenLinks
            )
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

    fun createFolder(name: String): Boolean {
        val dir = currentDir ?: return false
        val ok = FileOperationsHelper.createDirectory(dir, name.trim())
        if (ok) refresh()
        return ok
    }

    fun createFile(name: String): Boolean {
        val dir = currentDir ?: return false
        val ok = FileOperationsHelper.createFile(dir, name.trim())
        if (ok) refresh()
        return ok
    }

    /**
     * Creates a symlink in the current directory.
     *
     * @param name Name of the new link.
     * @param target Link target (absolute or relative to the current dir).
     * @return true on success.
     */
    fun createSymlink(name: String, target: String): Boolean {
        val dir = currentDir ?: return false
        val ok = FileOperationsHelper.createSymlink(dir, name.trim(), target.trim())
        if (ok) refresh()
        return ok
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

    fun renameFile(file: File, newName: String): Boolean {
        val ok = FileOperationsHelper.renameFile(file, newName.trim())
        if (ok) refresh()
        return ok
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
        viewModelScope.launch(Dispatchers.IO) {
            var ok = true
            for (f in files) {
                val trash = moveToTrash(f)
                if (trash == null) {
                    ok = false
                } else {
                    lastTrash = trash to f
                }
            }
            withContext(Dispatchers.Main) {
                clearSelection()
                refresh()
                onDone(ok)
            }
        }
    }

    fun undoDelete(): Boolean {
        val (trash, original) = lastTrash ?: return false
        val ok = FileOperationsHelper.moveFile(trash, original)
        if (ok) lastTrash = null
        refresh()
        return ok
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

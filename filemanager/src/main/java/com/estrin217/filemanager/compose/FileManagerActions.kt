package com.estrin217.filemanager.compose

import com.estrin217.filemanager.FileOperationsHelper
import java.io.File

/**
 * Stateless actions shared between touch taps, hardware keyboard navigation and
 * the extra keys bar, so the "open" behaviour never drifts across input paths.
 */
object FileManagerActions {

    /**
     * Open [file] exactly like a row tap: report broken symlinks, resolve
     * symlinks to their canonical target, navigate into directories (asking for
     * shared-storage access first) and hand files to [onOpenFile].
     *
     * @param file The file to open (normally the focused entry).
     * @param viewModel The owning [FileManagerViewModel].
     * @param onEnsureStorageAccess Storage permission gate for shared-storage dirs.
     * @param onOpenFile Callback to open a regular file (e.g. via FileProvider).
     */
    fun openFileOrDir(
        file: File,
        viewModel: FileManagerViewModel,
        onEnsureStorageAccess: (File, () -> Unit) -> Unit,
        onOpenFile: (File) -> Unit
    ) {
        if (FileOperationsHelper.isBrokenSymlink(file)) {
            viewModel.notifyBrokenSymlink(file)
            return
        }
        val resolved = viewModel.resolveForOpen(file)
        if (resolved.isDirectory) {
            if (FileOperationsHelper.isSharedStoragePath(resolved)) {
                onEnsureStorageAccess(resolved) { viewModel.navigateTo(resolved) }
            } else {
                viewModel.navigateTo(resolved)
            }
        } else {
            onOpenFile(resolved)
        }
    }

    /**
     * Apply a mapped [FileManagerKeyAction] to [viewModel], routing "open" back
     * through [openFileOrDir]. Shared by the hardware keyboard handler (screen)
     * and the extra keys bar handler (host), so both behave identically.
     *
     * @param action The action to perform.
     * @param viewModel The owning [FileManagerViewModel].
     * @param onEnsureStorageAccess Storage permission gate for shared-storage dirs.
     * @param onOpenFile Callback to open a regular file (e.g. via FileProvider).
     * @return {@code true} if the action was handled.
     */
    fun execute(
        action: FileManagerKeyAction,
        viewModel: FileManagerViewModel,
        onEnsureStorageAccess: (File, () -> Unit) -> Unit,
        onOpenFile: (File) -> Unit
    ): Boolean = when (action) {
        FileManagerKeyAction.FOCUS_UP -> { viewModel.moveFocus(-1); true }
        FileManagerKeyAction.FOCUS_DOWN -> { viewModel.moveFocus(1); true }
        FileManagerKeyAction.FOCUS_HOME -> { viewModel.focusHome(); true }
        FileManagerKeyAction.FOCUS_END -> { viewModel.focusEnd(); true }
        FileManagerKeyAction.PAGE_UP -> { viewModel.pageJump(-1); true }
        FileManagerKeyAction.PAGE_DOWN -> { viewModel.pageJump(1); true }
        FileManagerKeyAction.OPEN -> {
            val focused = viewModel.focusedFile
            if (focused == null) false
            else {
                openFileOrDir(focused, viewModel, onEnsureStorageAccess, onOpenFile)
                true
            }
        }
        FileManagerKeyAction.BACK -> { viewModel.onBackPressed(); true }
        FileManagerKeyAction.TOGGLE_SELECTION -> { viewModel.toggleFocusedSelection(); true }
        FileManagerKeyAction.NONE -> false
    }
}
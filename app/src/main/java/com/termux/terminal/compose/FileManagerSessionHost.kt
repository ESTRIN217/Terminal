package com.termux.terminal.compose

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.estrin217.filemanager.FileOperationsHelper
import com.estrin217.filemanager.compose.FileManagerActions
import com.estrin217.filemanager.compose.FileManagerKeyAction
import com.estrin217.filemanager.compose.FileManagerScreen
import com.estrin217.filemanager.compose.FileManagerViewModel
import com.termux.shared.android.PermissionUtils
import com.termux.shared.logger.Logger
import java.io.File

/**
 * Hosts a [FileManagerScreen] as a session tab inside [com.termux.app.TermuxComposeActivity].
 *
 * Each tab receives its own per-session [FileManagerViewModel], keyed by [sessionId],
 * which survives rotation because the ViewModel is stored in the activity's ViewModelStore.
 *
 * @param sessionId Stable id for the file manager session
 * @param isActivePane Whether this pane is the focused (active) pane of a split view; only the
 * focused pane registers the extra-keys handler and the back handler
 * @param onActivatePane Callback when the pane requests focus while it is not the active pane
 * @param onCloseSession Callback when the tab should be closed (back at root / X button)
 * @param modifier Modifier to apply
 */
@Composable
fun FileManagerSessionHost(
    sessionId: String,
    onCloseSession: () -> Unit,
    onOpenInTerminal: (String) -> Unit,
    isActivePane: Boolean = true,
    onActivatePane: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = context.applicationContext as Application

    val viewModel: FileManagerViewModel = viewModel(
        key = "filemanager_$sessionId",
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return FileManagerViewModel(appContext) as T
            }
        }
    )

    val pendingStorageAction = remember { mutableStateOf<(() -> Unit)?>(null) }

    fun onStoragePermissionResult() {
        val action = pendingStorageAction.value
        pendingStorageAction.value = null
        if (hasStoragePermission(context)) {
            action?.invoke()
            viewModel.refresh()
        } else {
            Toast.makeText(context, "Storage permission not granted", Toast.LENGTH_LONG).show()
        }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onStoragePermissionResult() }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStoragePermissionResult() }

    fun ensureStorageAccess(dir: File, onGranted: () -> Unit) {
        if (!FileOperationsHelper.isSharedStoragePath(dir) || hasStoragePermission(context)) {
            onGranted()
            return
        }
        pendingStorageAction.value = onGranted
        if (PermissionUtils.isLegacyExternalStoragePossible(context) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            legacyStorageLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            manageStorageLauncher.launch(intent)
        }
    }

    fun openFile(file: File) {
        if (FileOperationsHelper.isBrokenSymlink(file)) {
            Toast.makeText(context, "Broken symlink: target not found", Toast.LENGTH_SHORT).show()
            return
        }
        val target = FileOperationsHelper.resolveFileForOpen(file)
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", target
            )
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, FileOperationsHelper.getMimeType(target.name))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Open file"))
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(
                "FileManagerSessionHost",
                "Failed to open file: " + file.absolutePath,
                e
            )
            Toast.makeText(context, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }

    // Refresh on becoming active, mirroring FileManagerComposeActivity.onResume().
    LaunchedEffect(sessionId) { viewModel.refresh() }

    // Route extra keys bar presses into this session while its tab is composed and focused.
    // Only the focused pane owns the handler: with two file manager panes composed at once,
    // the last registered handler must not steal input from the inactive pane.
    DisposableEffect(sessionId, isActivePane) {
        if (isActivePane) {
            FileManagerKeyHandlerHolder.active = { key, _ ->
                FileManagerActions.execute(
                    FileManagerKeyAction.map(key),
                    viewModel,
                    { dir, onGranted -> ensureStorageAccess(dir, onGranted) },
                    { openFile(it) }
                )
            }
        }
        onDispose { FileManagerKeyHandlerHolder.active = null }
    }

    // Back: navigate the file manager history; close the tab when at its root. Only the
    // focused pane handles back so two composed panes do not fight for it.
    val onBackRequested: () -> Unit = {
        if (!viewModel.onBackPressed()) onCloseSession()
    }
    BackHandler(enabled = isActivePane, onBack = onBackRequested)

    FileManagerScreen(
        viewModel = viewModel,
        onNavigateUp = onBackRequested,
        onOpenFile = { openFile(it) },
        onEnsureStorageAccess = { dir, onGranted -> ensureStorageAccess(dir, onGranted) },
        onShareFiles = {
            try {
                FileOperationsHelper.shareFiles(context, it)
            } catch (e: Exception) {
                Logger.logStackTraceWithMessage("FileManagerSessionHost", "Share failed", e)
            }
            viewModel.clearSelection()
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        onOpenInTerminal = { dir -> onOpenInTerminal(dir.absolutePath) },
        // A secondary file manager pane promotes itself on focus, mirroring the terminal
        // panes: focusable() catches taps on empty areas, and onFocusChanged also fires when
        // a descendant (a list item row) takes focus. Only wired while the pane is secondary.
        modifier = if (isActivePane) {
            modifier
        } else {
            modifier
                .focusable()
                .onFocusChanged { focusState -> if (focusState.isFocused) onActivatePane() }
        }
    )
}

private fun hasStoragePermission(context: Context): Boolean =
    PermissionUtils.checkStoragePermission(
        context, PermissionUtils.isLegacyExternalStoragePossible(context)
    )

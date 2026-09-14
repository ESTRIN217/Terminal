package com.termux.app.activities

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.termux.app.filemanager.FileOperationsHelper
import com.termux.shared.android.PermissionUtils
import com.termux.shared.logger.Logger
import com.termux.terminal.compose.TermuxExpressiveTheme
import com.termux.terminal.compose.filemanager.FileManagerScreen
import com.termux.terminal.compose.filemanager.FileManagerViewModel
import java.io.File

/**
 * Compose replacement for {@link FileManagerActivity}.
 *
 * Same operations (browse, search, sort, hidden, bookmarks, create,
 * rename, copy/cut/paste, trash + undo, share, details, symlinks) backed by
 * [FileOperationsHelper]; IO for paste/delete runs on Dispatchers.IO
 * inside the ViewModel.
 */
class FileManagerComposeActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "FileManagerComposeActivity"
    }

    private lateinit var mViewModel: FileManagerViewModel

    /** Action deferred until the user grants shared-storage access. */
    private var mPendingStorageAction: (() -> Unit)? = null

    private val mLegacyStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onStoragePermissionResult() }

    private val mManageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStoragePermissionResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mViewModel = ViewModelProvider(this)[FileManagerViewModel::class.java]

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mViewModel.onBackPressed()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        setContent {
            TermuxExpressiveTheme {
                FileManagerScreen(
                    viewModel = mViewModel,
                    onNavigateUp = { finish() },
                    onOpenFile = { openFile(it) },
                    onEnsureStorageAccess = { dir, onGranted -> ensureStorageAccess(dir, onGranted) },
                    onShareFiles = {
                        try {
                            FileOperationsHelper.shareFiles(this, it)
                        } catch (e: Exception) {
                            Logger.logStackTraceWithMessage(LOG_TAG, "Share failed", e)
                        }
                        mViewModel.clearSelection()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mViewModel.refresh()
    }

    /**
     * Runs {@code onGranted} immediately when {@code dir} is not on shared
     * storage or the app already has storage access; otherwise requests the
     * permission first and runs it once granted.
     *
     * @param dir The directory the pending action targets.
     * @param onGranted Action to run once access is confirmed.
     */
    private fun ensureStorageAccess(dir: File, onGranted: () -> Unit) {
        if (!isSharedStoragePath(dir) || hasStoragePermission()) {
            onGranted()
            return
        }
        mPendingStorageAction = onGranted
        if (PermissionUtils.isLegacyExternalStoragePossible(this) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            mLegacyStoragePermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            mManageStorageLauncher.launch(intent)
        }
    }

    private fun onStoragePermissionResult() {
        val action = mPendingStorageAction
        mPendingStorageAction = null
        if (hasStoragePermission()) {
            action?.invoke()
            mViewModel.refresh()
        } else {
            Toast.makeText(this, "Storage permission not granted", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasStoragePermission(): Boolean =
        PermissionUtils.checkStoragePermission(
            this, PermissionUtils.isLegacyExternalStoragePossible(this)
        )

    /** Whether {@code dir} lives on shared storage (phone/tablet storage). */
    private fun isSharedStoragePath(dir: File): Boolean =
        FileOperationsHelper.isSharedStoragePath(dir)

    private fun openFile(file: File) {
        if (FileOperationsHelper.isBrokenSymlink(file)) {
            Toast.makeText(this, "Broken symlink: target not found", Toast.LENGTH_SHORT).show()
            return
        }
        // Open the canonical target so links share the destination's MIME type.
        val target = FileOperationsHelper.resolveFileForOpen(file)
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", target
            )
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, FileOperationsHelper.getMimeType(target.name))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open file"))
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(
                LOG_TAG, "Failed to open file: " + file.absolutePath, e
            )
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }
}

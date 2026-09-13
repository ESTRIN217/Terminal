package com.termux.app.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.termux.app.filemanager.FileOperationsHelper
import com.termux.shared.logger.Logger
import com.termux.terminal.compose.TermuxExpressiveTheme
import com.termux.terminal.compose.filemanager.FileManagerScreen
import com.termux.terminal.compose.filemanager.FileManagerViewModel
import java.io.File

/**
 * Compose replacement for {@link FileManagerActivity}.
 *
 * Same operations (browse, search, sort, hidden, bookmarks, create,
 * rename, copy/cut/paste, trash + undo, share, details) backed by
 * [FileOperationsHelper]; IO for paste/delete runs on Dispatchers.IO
 * inside the ViewModel.
 */
class FileManagerComposeActivity : ComponentActivity() {

    companion object {
        private const val LOG_TAG = "FileManagerComposeActivity"
    }

    private lateinit var mViewModel: FileManagerViewModel

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

    private fun openFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, FileOperationsHelper.getMimeType(file.name))
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

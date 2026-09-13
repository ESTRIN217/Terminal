package com.termux.terminal.compose.filemanager

import com.termux.app.filemanager.FileSortOption
import java.io.File

/**
 * UI state for the Compose file manager.
 *
 * Ports the state previously held by `FileManagerActivity` fields
 * (current dir, back/forward stacks, sort, hidden, search, selection).
 */
data class FileManagerUiState(
    val currentPath: String = "",
    val title: String = "Files",
    val files: List<File> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val searchQuery: String = "",
    val sortOption: FileSortOption = FileSortOption.NAME,
    val sortAscending: Boolean = true,
    val showHidden: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasClipboard: Boolean = false,
    val statusMessage: String? = null
)

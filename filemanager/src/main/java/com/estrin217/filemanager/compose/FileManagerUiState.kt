package com.estrin217.filemanager.compose

import com.estrin217.filemanager.FileSortOption
import java.io.File

/**
 * UI state for the Compose file manager.
 *
 * Ports the state previously held by `FileManagerActivity` fields
 * (current dir, back/forward stacks, sort, hidden, search, selection).
 *
 * Symlink support: [symlinkTargets] maps absolute path to the raw link
 * target for every symlink in the current listing; [brokenLinks] holds the
 * absolute paths of dangling links whose target does not exist.
 *
 * [focusedIndex] is the keyboard/extra-keys cursor into [files] (a negative
 * value means the list is empty).
 */
data class FileManagerUiState(
    val currentPath: String = "",
    val title: String = "Files",
    val files: List<File> = emptyList(),
    val focusedIndex: Int = -1,
    val selectedPaths: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val searchQuery: String = "",
    val sortOption: FileSortOption = FileSortOption.NAME,
    val sortAscending: Boolean = true,
    val showHidden: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasClipboard: Boolean = false,
    /** Whether a listing or file operation is in flight (blocking interactions). */
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val symlinkTargets: Map<String, String?> = emptyMap(),
    val brokenLinks: Set<String> = emptySet()
)

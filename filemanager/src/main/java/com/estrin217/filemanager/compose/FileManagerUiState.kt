package com.estrin217.filemanager.compose

import androidx.compose.runtime.Immutable
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
 * [focusedIndex] is the keyboard/extra-keys cursor into [files]: a negative
 * value means there is no cursor yet (no highlight shown) — either because
 * the listing is empty or because the directory just changed (spec 007).
 */
@Immutable
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
    /**
     * Whether directories sort first (grouped, historical behaviour) or
     * merge into the plain criteria order (spec 008, RF-7).
     */
    val foldersFirst: Boolean = true,
    /** Presentation mode of the listing (list rows or tile grid, RF-7). */
    val viewMode: FileViewMode = FileViewMode.LIST,
    /**
     * Whether the open folder has its own listing-preferences override
     * ("Organize > this folder only", RF-8). When set, organize changes
     * write to the folder override instead of the global prefs.
     */
    val folderScoped: Boolean = false,
    /** Visible directory tally of the current listing (RF-1/RF-18). */
    val folderCount: Int = 0,
    /** Visible non-directory tally of the current listing (RF-1/RF-18). */
    val fileCount: Int = 0,
    /** User favorites (label + path) for the bookmarks drawer (RF-5). */
    val favorites: List<BreadcrumbPolicy.Crumb> = emptyList(),
    /** Whether the open folder is in [favorites] (RF-6). */
    val isFavorite: Boolean = false,
    /** Whether the open folder has a navigable parent (RF-11). */
    val canGoUp: Boolean = false,
    val showHidden: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasClipboard: Boolean = false,
    /** Whether a listing or file operation is in flight (blocking interactions). */
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val symlinkTargets: Map<String, String?> = emptyMap(),
    val brokenLinks: Set<String> = emptySet(),
    /**
     * Cache of computed directory sizes (absolute path -> size in bytes),
     * filled on demand when the details dialog is shown for a folder.
     */
    val folderSizes: Map<String, Long> = emptyMap()
)

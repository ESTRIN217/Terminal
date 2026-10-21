package com.estrin217.filemanager.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.estrin217.filemanager.FileTypeDetector.FileType
import com.estrin217.filemanager.FileTypeDetector.IconVariant

/** Verbose tag for the file (Termux repo convention). */
private const val LOG_TAG = "FileTypeIcons"

// Fixed family colors (plan 006): nine hues chosen to stay distinguishable from
// each other and from the generic onSurfaceVariant tint in both the light and
// dark Compose themes of the host app.

/** Source code tint: green. */
private val CodeTint = Color(0xFF2E7D32)

/** Binaries/apps tint: teal. */
private val BinaryTint = Color(0xFF00897B)

/** Compressed archives tint: orange. */
private val ArchiveTint = Color(0xFFEF6C00)

/** Images tint: blue. */
private val ImageTint = Color(0xFF1565C0)

/** Audio tint: violet. */
private val AudioTint = Color(0xFF8E24AA)

/** Video tint: crimson. */
private val VideoTint = Color(0xFFE53935)

/** Plain text/data tint: indigo. */
private val TextTint = Color(0xFF3F51B5)

/** PDF tint: dark red. */
private val PdfTint = Color(0xFFB71C1C)

/** Rich documents tint: brown. */
private val DocumentTint = Color(0xFF8D6E63)

/**
 * UI-only mapping from a [FileType] family (spec 006) to the Material icon
 * painted on the file list rows.
 *
 * <b>No logic lives here beyond the one-entry-per-family table</b>: recognizing
 * a family is {@link com.estrin217.filemanager.FileTypeDetector}'s job (pure,
 * unit-tested); this file only paints the result.
 *
 * @return the icon drawn for every file of the family
 */
internal val FileType.icon: ImageVector
    get() = when (this) {
        FileType.CODE -> Icons.Default.Code
        FileType.BINARY -> Icons.Default.Android
        FileType.ARCHIVE -> Icons.Default.Archive
        FileType.IMAGE -> Icons.Default.Image
        FileType.AUDIO -> Icons.Default.MusicNote
        FileType.VIDEO -> Icons.Default.Movie
        FileType.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
        FileType.PDF -> Icons.Default.PictureAsPdf
        FileType.DOCUMENT -> Icons.AutoMirrored.Filled.Article
    }

/**
 * UI-only mapping from a [FileType] family to its fixed color.
 *
 * @return the tint applied to [icon] on the file rows
 */
internal val FileType.tint: Color
    get() = when (this) {
        FileType.CODE -> CodeTint
        FileType.BINARY -> BinaryTint
        FileType.ARCHIVE -> ArchiveTint
        FileType.IMAGE -> ImageTint
        FileType.AUDIO -> AudioTint
        FileType.VIDEO -> VideoTint
        FileType.TEXT -> TextTint
        FileType.PDF -> PdfTint
        FileType.DOCUMENT -> DocumentTint
    }

/**
 * UI-only mapping from an [IconVariant] (spec 006 rev 2) to the more specific
 * Material icon painted instead of the family icon. The color still comes
 * from the family ([FileType.tint], RF-12) except GIT (RF-13), JSON (RF-14),
 * KT (RF-15), JAVA (RF-16) and GRADLE (RF-18), which carry their own brand
 * tint, and PYTHON (RF-17), which bakes its colors in the glyph; README uses
 * the outlined info so it does not look identical to the row's filled info
 * IconButton.
 *
 * @return the refined icon, or `null` when the family icon must be used
 */
internal val IconVariant.icon: ImageVector?
    get() = when (this) {
        IconVariant.NONE -> null
        IconVariant.MARKDOWN -> MarkdownGlyph
        IconVariant.INFO -> Icons.Outlined.Info
        IconVariant.HISTORY -> Icons.Default.History
        IconVariant.SETTINGS -> Icons.Default.Settings
        IconVariant.JSON -> JsonGlyph
        IconVariant.JS -> Icons.Default.Javascript
        IconVariant.GIT -> GitGlyph
        IconVariant.KT -> KotlinGlyph
        IconVariant.JAVA -> JavaCoffeeGlyph
        IconVariant.PY -> PythonGlyph
        IconVariant.GRADLE -> GradleGlyph
    }

/** Official git brand color; printed as the GIT variant's own tint (RF-13). */
private val GitTint = Color(0xFFF03C2E)

/** JSON yellow: variant's own tint (RF-14, rev 4), Material Yellow 800 to read on light and dark. */
private val JsonTint = Color(0xFFF9A825)

/** Kotlin purple: the official flat brand color (RF-15, rev 5); the logo's radial
 * gradient (red-magenta-purple) can't ride along in an ImageVector, so the flat
 * extreme tone is used instead. */
private val KotlinTint = Color(0xFF7F52FF)

/** Java red: brand color of the classic Duke cup logo (`#EA2D2E`, RF-16, rev 6). */
private val JavaTint = Color(0xFFEA2D2E)

/** Gradle blue own tint (RF-18, rev 8): the provided elephant drawable came with
 * a black fill and the user asked for blue, so Material Light Blue 800 is used:
 * readable on both themes and clearly bluer than the family blues (ImageTint
 * `#1565C0`, TextTint indigo `#3F51B5`). */
private val GradleTint = Color(0xFF0288D1)

/**
 * UI-only mapping from an [IconVariant] to its own tint, or `null` when the
 * tint must come from the family ([FileType.tint], RF-12). GIT and JSON and KT
 * and JAVA and GRADLE have an own brand color (RF-13 / RF-14 / RF-15 / RF-16 /
 * RF-18); PYTHON returns [Color.Unspecified] so the Icon paints its glyph
 * without a ColorFilter and its two baked brand colors survive (RF-17).
 *
 * @return the variant tint, `Unspecified` for multicolor glyphs or `null` to
 * fall back to the family tint
 */
internal val IconVariant.tint: Color?
    get() = when (this) {
        IconVariant.GIT -> GitTint
        IconVariant.JSON -> JsonTint
        IconVariant.KT -> KotlinTint
        IconVariant.JAVA -> JavaTint
        IconVariant.PY -> Color.Unspecified
        IconVariant.GRADLE -> GradleTint
        else -> null
    }

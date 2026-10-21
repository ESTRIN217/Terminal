package com.estrin217.filemanager.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * Vendored Material Symbols glyphs (spec 006 rev 2, RF-10): they are NOT part
 * of androidx material-icons-extended (checked against the artifact), so the
 * Kotlin `ImageVector` builders were downloaded from the official Material
 * Symbols endpoint and adapted (renamed to the module prefix, internal):
 *
 *   https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/markdown.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
 *   https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/file_json.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
 *   https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/local_cafe.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50 (rev 6, Java)
 *
 * Rev 3 (RF-13) adds the official git glyph, vendored from the vector drawable
 * provided by the user (viewport 78x78, single path, brand color #F03C2E is
 * applied by the caller as the variant tint instead of the path fill).
 *
 * Rev 8 (RF-18) adds the official gradle elephant glyph, also vendored from a
 * user-provided drawable (viewport 341.71x250, single path). Its source fill
 * was black and the user asked for blue, so the blue is applied by the caller
 * as the variant tint (RF-13 pattern) instead of the path fill; the wide
 * viewport is square-padded with a group translation so the mark is not
 * stretched in the square icon boxes.
 *
 * Tinting works like any vector icon: the painted color comes from the
 * family tint (RF-12), so the SolidColor below is the icon's own black
 * material default and the caller overrides it.
 */

/** Verbose tag for the file (Termux repo convention). */
private const val LOG_TAG = "FileTypeGlyphs"

/**
 * Material Symbols *markdown* glyph (24dp, FILL 0), for markdown sources.
 *
 * @return the shared cached vector
 */
internal val MarkdownGlyph: ImageVector
    get() {
        if (GlyphCache.markdown != null) {
            return GlyphCache.markdown!!
        }
        GlyphCache.markdown =
            ImageVector.Builder(
                name = "markdown",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
                .apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    ) {
                        moveTo(16f, 15f)
                        lineToRelative(3f, -3f)
                        lineTo(17.95f, 10.93f)
                        lineToRelative(-1.2f, 1.2f)
                        verticalLineTo(9f)
                        horizontalLineToRelative(-1.5f)
                        verticalLineToRelative(3.13f)
                        lineToRelative(-1.2f, -1.2f)
                        lineTo(13f, 12f)
                        lineToRelative(3f, 3f)
                        close()
                        moveTo(4f, 20f)
                        quadTo(3.18f, 20f, 2.59f, 19.41f)
                        reflectiveQuadTo(2f, 18f)
                        verticalLineTo(6f)
                        quadTo(2f, 5.18f, 2.59f, 4.59f)
                        reflectiveQuadTo(4f, 4f)
                        horizontalLineTo(20f)
                        quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                        quadTo(22f, 5.18f, 22f, 6f)
                        verticalLineTo(18f)
                        quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                        reflectiveQuadTo(20f, 20f)
                        horizontalLineTo(4f)
                        close()
                        moveTo(4f, 18f)
                        horizontalLineTo(20f)
                        verticalLineTo(6f)
                        horizontalLineTo(4f)
                        verticalLineTo(18f)
                        close()
                        moveToRelative(0f, 0f)
                        verticalLineTo(6f)
                        verticalLineTo(18f)
                        close()
                        moveTo(5.5f, 15f)
                        horizontalLineTo(7f)
                        verticalLineTo(10.5f)
                        horizontalLineTo(8f)
                        verticalLineToRelative(3f)
                        horizontalLineTo(9.5f)
                        verticalLineToRelative(-3f)
                        horizontalLineToRelative(1f)
                        verticalLineTo(15f)
                        horizontalLineTo(12f)
                        verticalLineTo(10f)
                        quadTo(12f, 9.57f, 11.71f, 9.29f)
                        reflectiveQuadTo(11f, 9f)
                        horizontalLineTo(6.5f)
                        quadTo(6.08f, 9f, 5.79f, 9.29f)
                        reflectiveQuadTo(5.5f, 10f)
                        verticalLineToRelative(5f)
                        close()
                    }
                }
                .build()
        return GlyphCache.markdown!!
    }

/**
 * Material Symbols *file_json* glyph (24dp, FILL 0), for JSON data files.
 *
 * @return the shared cached vector
 */
internal val JsonGlyph: ImageVector
    get() {
        if (GlyphCache.json != null) {
            return GlyphCache.json!!
        }
        GlyphCache.json =
            ImageVector.Builder(
                name = "file_json",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
                .apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    ) {
                        moveTo(4.75f, 15f)
                        horizontalLineTo(6.5f)
                        quadToRelative(0.43f, 0f, 0.71f, -0.29f)
                        reflectiveQuadTo(7.5f, 14f)
                        verticalLineTo(9f)
                        horizontalLineTo(6f)
                        verticalLineToRelative(4.75f)
                        horizontalLineTo(5f)
                        verticalLineTo(12.5f)
                        horizontalLineTo(3.75f)
                        verticalLineTo(14f)
                        quadToRelative(0f, 0.42f, 0.29f, 0.71f)
                        reflectiveQuadTo(4.75f, 15f)
                        close()
                        moveToRelative(4.43f, 0f)
                        horizontalLineToRelative(1.5f)
                        quadToRelative(0.43f, 0f, 0.71f, -0.29f)
                        reflectiveQuadTo(11.68f, 14f)
                        verticalLineTo(12.5f)
                        quadToRelative(0f, -0.43f, -0.29f, -0.71f)
                        reflectiveQuadTo(10.68f, 11.5f)
                        horizontalLineTo(9.43f)
                        verticalLineTo(10.25f)
                        horizontalLineToRelative(1f)
                        verticalLineToRelative(0.5f)
                        horizontalLineToRelative(1.25f)
                        verticalLineTo(10f)
                        quadToRelative(0f, -0.43f, -0.29f, -0.71f)
                        reflectiveQuadTo(10.68f, 9f)
                        horizontalLineTo(9.18f)
                        quadTo(8.75f, 9f, 8.46f, 9.29f)
                        reflectiveQuadTo(8.18f, 10f)
                        verticalLineToRelative(1.5f)
                        quadToRelative(0f, 0.42f, 0.29f, 0.71f)
                        reflectiveQuadTo(9.18f, 12.5f)
                        horizontalLineToRelative(1.25f)
                        verticalLineToRelative(1.25f)
                        horizontalLineToRelative(-1f)
                        verticalLineToRelative(-0.5f)
                        horizontalLineTo(8.18f)
                        verticalLineTo(14f)
                        quadToRelative(0f, 0.42f, 0.29f, 0.71f)
                        reflectiveQuadTo(9.18f, 15f)
                        close()
                        moveToRelative(4.4f, -1.5f)
                        verticalLineToRelative(-3f)
                        horizontalLineToRelative(1f)
                        verticalLineToRelative(3f)
                        horizontalLineToRelative(-1f)
                        close()
                        moveTo(13.33f, 15f)
                        horizontalLineToRelative(1.5f)
                        quadToRelative(0.42f, 0f, 0.71f, -0.29f)
                        reflectiveQuadTo(15.83f, 14f)
                        verticalLineTo(10f)
                        quadToRelative(0f, -0.43f, -0.29f, -0.71f)
                        reflectiveQuadTo(14.83f, 9f)
                        horizontalLineToRelative(-1.5f)
                        quadTo(12.9f, 9f, 12.61f, 9.29f)
                        reflectiveQuadTo(12.33f, 10f)
                        verticalLineToRelative(4f)
                        quadToRelative(0f, 0.42f, 0.29f, 0.71f)
                        reflectiveQuadTo(13.33f, 15f)
                        close()
                        moveToRelative(3.17f, 0f)
                        horizontalLineToRelative(1.25f)
                        verticalLineTo(12.38f)
                        lineToRelative(1f, 2.63f)
                        horizontalLineTo(20f)
                        verticalLineTo(9f)
                        horizontalLineTo(18.75f)
                        verticalLineToRelative(2.63f)
                        lineTo(17.75f, 9f)
                        horizontalLineTo(16.5f)
                        verticalLineToRelative(6f)
                        close()
                        moveTo(3f, 20f)
                        quadTo(2.18f, 20f, 1.59f, 19.41f)
                        reflectiveQuadTo(1f, 18f)
                        verticalLineTo(6f)
                        quadTo(1f, 5.18f, 1.59f, 4.59f)
                        reflectiveQuadTo(3f, 4f)
                        horizontalLineTo(21f)
                        quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                        quadTo(23f, 5.18f, 23f, 6f)
                        verticalLineTo(18f)
                        quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                        reflectiveQuadTo(21f, 20f)
                        horizontalLineTo(3f)
                        close()
                        moveTo(3f, 18f)
                        horizontalLineTo(21f)
                        verticalLineTo(6f)
                        horizontalLineTo(3f)
                        verticalLineTo(18f)
                        close()
                        moveToRelative(0f, 0f)
                        verticalLineTo(6f)
                        verticalLineTo(18f)
                        close()
                    }
                }
                .build()
        return GlyphCache.json!!
    }

/** Cache holder to keep the glyph getters extension-free and threadsafe-ish. */
private object GlyphCache {
    /** Cached markdown glyph. */
    @Volatile
    var markdown: ImageVector? = null

    /** Cached file_json glyph. */
    @Volatile
    var json: ImageVector? = null

    /** Cached git glyph (rev 3). */
    @Volatile
    var git: ImageVector? = null

    /** Cached kotlin glyph (rev 5). */
    @Volatile
    var kotlin: ImageVector? = null

    /** Cached local_cafe (Java coffee cup) glyph (rev 6). */
    @Volatile
    var javaCoffee: ImageVector? = null

    /** Cached two-tone Python glyph (rev 7). */
    @Volatile
    var python: ImageVector? = null

    /** Cached gradle elephant glyph (rev 8). */
    @Volatile
    var gradle: ImageVector? = null
}

/** Official git logo path (the user-provided drawable, verbatim `pathData`). */
private const val GIT_PATH_DATA =
    "M42.54 76.48c-1.96 1.95-5.12 1.95-7.08 0L1.52 42.54c-1.95-1.96-1.95-5.12 0-7.08l23.34-23.33L33.73 21c-0.8 2.13-0.33 4.62 1.38 6.33 0.52 0.52 1.11 0.93 1.74 1.22v21.61c-0.63 0.29-1.22 0.7-1.74 1.21-2.34 2.35-2.34 6.15 0 8.49s6.14 2.34 8.49 0c2.34-2.34 2.34-6.14 0-8.49-0.52-0.52-1.12-0.92-1.75-1.2V29.12L50 37.26c-0.79 2.13-0.33 4.62 1.38 6.34 2.35 2.34 6.15 2.34 8.49 0 2.34-2.35 2.34-6.15 0-8.49-1.71-1.71-4.2-2.17-6.33-1.38l-8.55-8.55c0.79-2.13 0.33-4.62-1.38-6.33-1.72-1.71-4.2-2.18-6.34-1.39L28.4 8.6l7.07-7.07c1.96-1.95 5.12-1.95 7.08 0l33.94 33.94c1.95 1.96 1.95 5.12 0 7.08z"

/**
 * Official git glyph, vendored from the drawable attached in rev 3 (RF-13).
 *
 * @return the shared cached vector (78x78 viewport)
 */
internal val GitGlyph: ImageVector
    get() {
        if (GlyphCache.git != null) {
            return GlyphCache.git!!
        }
        GlyphCache.git =
            ImageVector.Builder(
                name = "git",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 78f,
                viewportHeight = 78f,
            )
                .apply {
                    addPath(
                        pathData = addPathNodes(GIT_PATH_DATA),
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    )
                }
                .build()
        return GlyphCache.git!!
    }

/** Official Kotlin logo path (the user-provided drawable rev 5, `pathData` verbatim). */
private const val KOTLIN_PATH_DATA = "M48 48H0V0h48L23.5 23.65 48 48Z"

/**
 * Material Symbols *local_cafe* glyph (24dp, FILL 0), for Java sources (rev 6,
 * RF-16); the cup is painted in the Java brand red (see JavaTint).
 *
 * @return the shared cached vector
 */
internal val JavaCoffeeGlyph: ImageVector
    get() {
        if (GlyphCache.javaCoffee != null) {
            return GlyphCache.javaCoffee!!
        }
        GlyphCache.javaCoffee =
            ImageVector.Builder(
                name = "local_cafe",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
                .apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    ) {
                        moveTo(4f, 21f)
                        verticalLineTo(19f)
                        horizontalLineTo(20f)
                        verticalLineToRelative(2f)
                        horizontalLineTo(4f)
                        close()
                        moveTo(8f, 17f)
                        quadTo(6.35f, 17f, 5.18f, 15.83f)
                        reflectiveQuadTo(4f, 13f)
                        verticalLineTo(3f)
                        horizontalLineTo(20f)
                        quadToRelative(0.83f, 0f, 1.41f, 0.59f)
                        reflectiveQuadTo(22f, 5f)
                        verticalLineTo(8f)
                        quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                        reflectiveQuadTo(20f, 10f)
                        horizontalLineTo(18f)
                        verticalLineToRelative(3f)
                        quadToRelative(0f, 1.65f, -1.18f, 2.82f)
                        reflectiveQuadTo(14f, 17f)
                        horizontalLineTo(8f)
                        close()
                        moveTo(8f, 15f)
                        horizontalLineToRelative(6f)
                        quadToRelative(0.83f, 0f, 1.41f, -0.59f)
                        reflectiveQuadTo(16f, 13f)
                        verticalLineTo(5f)
                        horizontalLineTo(6f)
                        verticalLineToRelative(8f)
                        quadToRelative(0f, 0.82f, 0.59f, 1.41f)
                        reflectiveQuadTo(8f, 15f)
                        close()
                        moveTo(18f, 8f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(5f)
                        horizontalLineTo(18f)
                        verticalLineTo(8f)
                        close()
                        moveTo(8f, 15f)
                        quadTo(7.18f, 15f, 6.59f, 15f)
                        reflectiveQuadTo(6f, 15f)
                        horizontalLineTo(16f)
                        quadToRelative(0f, 0f, -0.59f, 0f)
                        reflectiveQuadTo(14f, 15f)
                        horizontalLineTo(8f)
                        close()
                    }
                }
                .build()
        return GlyphCache.javaCoffee!!
    }

/** Python brand colors for the two-snake glyph (RF-17, rev 7): the user-provided
 * drawable came with both snakes black; each path got its official language color
 * baked in (top snake blue, bottom snake yellow). */
private val PythonBlue = Color(0xFF3776AB)

/** Python yellow (bottom snake). */
private val PythonYellow = Color(0xFFFFD43B)

/**
 * Official Python glyph (the two interlocked snakes), vendored from the drawable
 * attached in rev 7 (RF-17). Both fills are BAKED with the brand colors; painting
 * must skip the tint (IconVariant.tint returns Color.Unspecified) or the two
 * tones would be wiped by the ColorFilter.
 *
 * @return the shared cached vector (17.06x17.06 viewport)
 */
internal val PythonGlyph: ImageVector
    get() {
        if (GlyphCache.python != null) {
            return GlyphCache.python!!
        }
        GlyphCache.python =
            ImageVector.Builder(
                name = "python",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 17.06f,
                viewportHeight = 17.06f,
            )
                .apply {
                    addPath(
                        pathData = addPathNodes(
                            "M11.3 8.02c1.3-0.59 1.49-5.05 0.72-6.37-1-1.72-5.74-1.37-7.24-0.15-0.17 0.61-0.15 1.72-0.14 2.6h4.7v0.41h-4.7l-2.1 0.02c-3.6 0-3.53 7.83 1.23 7.83 0 0 0.18-1.73 0.48-2.6 0.95-2.69 4.92-0.77 7.05-1.74zM6.37 3.47c-0.54 0-0.98-0.44-0.98-0.99 0-0.54 0.44-0.98 0.99-0.98 0.54 0 0.98 0.44 0.98 0.98S6.92 3.46 6.37 3.46z"
                        ),
                        fill = SolidColor(PythonBlue),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    )
                    addPath(
                        pathData = addPathNodes(
                            "M13.12 4.7s-0.13 1.73-0.43 2.6c-0.95 2.68-4.91 0.77-7.05 1.73-1.29 0.6-1.48 5.06-0.72 6.38 1 1.72 5.74 1.37 7.24 0.14 0.17-0.6 0.15-1.72 0.15-2.6H7.6v-0.4h4.7l2.31-0.02c3.33 0 3.63-7.84-1.5-7.84z m-2.72 8.72c0.54 0 0.98 0.44 0.98 0.98s-0.44 0.98-0.98 0.98-0.98-0.44-0.98-0.98 0.44-0.98 0.98-0.98z"
                        ),
                        fill = SolidColor(PythonYellow),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    )
                }
                .build()
        return GlyphCache.python!!
    }

/**
 * Official Kotlin glyph (tile with the K), vendored from the drawable attached
 * in rev 5 (RF-15). The drawable's radial brand gradient can not ride along
 * in an ImageVector; the purple flat tone of the gradient's end
 * (`#7F52FF`) is applied as the variant tint in [KotlinTint].
 *
 * @return the shared cached vector (48x48 viewport)
 */
internal val KotlinGlyph: ImageVector
    get() {
        if (GlyphCache.kotlin != null) {
            return GlyphCache.kotlin!!
        }
        GlyphCache.kotlin =
            ImageVector.Builder(
                name = "kotlin",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 48f,
                viewportHeight = 48f,
            )
                .apply {
                    addPath(
                        pathData = addPathNodes(KOTLIN_PATH_DATA),
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    )
                }
                .build()
        return GlyphCache.kotlin!!
    }

/** Official gradle elephant path (the user-provided drawable rev 8, `pathData`
 * verbatim from the attached XML). */
private const val GRADLE_PATH_DATA =
    "M322.77 15.82c-20.6-20.59-53.83-21.15-75.11-1.26-1.02 0.94-1.61 2.27-1.61 3.66-0.03 1.4 0.51 2.74 1.5 3.74l6.81 6.8c1.82 1.81 4.68 2.01 6.73 0.47 5.34-4.02 11.85-6.19 18.54-6.17 17.11 0 30.98 13.85 30.98 30.94 0 8.2-3.26 16.06-9.05 21.86-42.79 42.98-100.23-77.1-230.18-15.33-8.83 4.1-12.67 14.57-8.57 23.4 0.21 0.45 0.44 0.9 0.69 1.34l22.28 38.49c4.84 8.33 15.47 11.26 23.9 6.57l0.55-0.31-0.43 0.31 9.88-5.54c11.02-6.82 21.44-14.57 31.14-23.16 1.97-1.84 5-1.94 7.09-0.24 2.17 1.77 2.5 4.96 0.73 7.14-0.22 0.27-0.46 0.51-0.73 0.73-10.12 9.1-21.01 17.31-32.56 24.53l-0.35 0.2-9.88 5.5c-4.17 2.36-8.87 3.59-13.66 3.58-10.1 0.02-19.44-5.32-24.53-14.04L55.83 92.7C15.35 121.23-9.21 176.24 3.97 245.83c0.47 2.43 2.6 4.18 5.08 4.17h23.97c2.66 0.1 4.95-1.85 5.28-4.48 2.51-19.25 20.17-32.82 39.45-30.31 15.83 2.06 28.29 14.5 30.35 30.31 0.31 2.53 2.45 4.44 5 4.48h23.38c2.59 0 4.78-1.92 5.12-4.48 2.43-19.24 20.01-32.87 39.27-30.45 15.93 2 28.48 14.54 30.49 30.45 0.32 2.56 2.5 4.48 5.08 4.48h23.11c2.79 0 5.07-2.24 5.12-5.03 0.55-32.56 9.33-69.95 34.41-88.66 86.57-64.8 63.78-120.35 43.7-140.49Zm-88.45 98.02v-0.04l-16.54-8.3c-0.02-5.73 4.61-10.4 10.35-10.42 5.74-0.02 10.41 4.61 10.43 10.34 0.01 3.32-1.57 6.45-4.25 8.41Z"

/**
 * Official gradle elephant glyph, vendored from the drawable attached in rev 8
 * (RF-18). The source viewport is 341.71x250; it is square-padded to
 * 341.71x341.71 with a group translationY of (341.71 - 250) / 2 so the wide
 * elephant is centered (not stretched) in the square icon boxes of the list
 * rows (28.dp) and grid tiles (44.dp). The drawable's fill is black and the
 * user asked for blue, so the painted color comes from the caller's brand
 * tint (see GradleTint), like the git glyph.
 *
 * @return the shared cached vector (341.71x341.71 viewport)
 */
internal val GradleGlyph: ImageVector
    get() {
        if (GlyphCache.gradle != null) {
            return GlyphCache.gradle!!
        }
        GlyphCache.gradle =
            ImageVector.Builder(
                name = "gradle",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 341.71f,
                viewportHeight = 341.71f,
            )
                .apply {
                    group(translationY = 45.855f) {
                        addPath(
                            pathData = addPathNodes(GRADLE_PATH_DATA),
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        )
                    }
                }
                .build()
        return GlyphCache.gradle!!
    }

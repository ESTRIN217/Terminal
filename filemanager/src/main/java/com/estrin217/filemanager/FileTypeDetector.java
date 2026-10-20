package com.estrin217.filemanager;

import java.util.Locale;

/**
 * Pure extension-to-family mapping for the file manager list icons (spec 006).
 *
 * <p>The detector only looks at the file <em>name</em> (its extension), like
 * {@link FileSortOption} and {@link FileOperationsHelper#getMimeType(String)}
 * already do; mapping a family onto an actual icon and color is the UI's job
 * ({@code FileTypeIcons} in the compose package).
 *
 * <p>Domains are lowercase extension names without the dot. Precedence rules
 * live in the caller: directories, links and broken links never reach this
 * detector.
 */
public final class FileTypeDetector {

    private static final String LOG_TAG = "FileTypeDetector";

    /** File families recognized by the icon mapping (spec 006, RF-2). */
    public enum FileType {
        /** Source code: kt, java, py, sh, bash, zsh, c, cpp, h, hpp, rs, go, js, ts, rb, pl, lua, sql. */
        CODE,
        /** Binaries and apps: apk, jar, dex, so, deb, exe, bin. */
        BINARY,
        /** Compressed archives: zip, tar, gz, tgz, bz2, xz, 7z, rar. */
        ARCHIVE,
        /** Images: png, jpg, jpeg, gif, webp, bmp, svg, ico. */
        IMAGE,
        /** Audio: mp3, flac, ogg, oga, wav, opus, m4a, mid. */
        AUDIO,
        /** Video: mp4, mkv, avi, webm, mov, m4v. */
        VIDEO,
        /** Plain text and data files: txt, md, json, xml, html, css, yaml, yml, toml, csv, ini, conf, log, prop, properties. */
        TEXT,
        /** PDF documents: pdf. */
        PDF,
        /** Rich documents: doc, docx, odt, rtf. */
        DOCUMENT
    }

    /**
     * Returns the lowercase extension of {@code name}, without the dot, or the
     * empty string when there is no usable extension.
     *
     * <p>Edge cases: {@code "app.tar.gz"} returns {@code "gz"} (last extension);
     * {@code ".bashrc"} returns {@code ""} (hidden file, dot at index 0);
     * {@code "file."} returns {@code ""} (trailing dot); {@code null} returns
     * {@code ""}.
     *
     * @param name file name (may be null)
     * @return lowercase extension without the dot, or {@code ""}
     */
    public static String getFileExtension(String name) {
        if (name == null) {
            return "";
        }
        int idx = name.lastIndexOf('.');
        if (idx <= 0 || idx == name.length() - 1) {
            return "";
        }
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Maps a file name to its icon family, or {@code null} when the extension
     * is not recognized (RF-3: the file keeps the generic icon). The lookup is
     * case-insensitive (RF-5).
     *
     * @param name file name (may be null)
     * @return the {@link FileType} family, or {@code null} when unrecognized
     */
    public static FileType familyFor(String name) {
        switch (getFileExtension(name)) {
            case "kt": case "java": case "py": case "sh": case "bash": case "zsh":
            case "c": case "cpp": case "h": case "hpp": case "rs": case "go":
            case "js": case "ts": case "rb": case "pl": case "lua": case "sql":
                return FileType.CODE;
            case "apk": case "jar": case "dex": case "so": case "deb": case "exe": case "bin":
                return FileType.BINARY;
            case "zip": case "tar": case "gz": case "tgz": case "bz2": case "xz": case "7z": case "rar":
                return FileType.ARCHIVE;
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "bmp": case "svg": case "ico":
                return FileType.IMAGE;
            case "mp3": case "flac": case "ogg": case "oga": case "wav": case "opus": case "m4a": case "mid":
                return FileType.AUDIO;
            case "mp4": case "mkv": case "avi": case "webm": case "mov": case "m4v":
                return FileType.VIDEO;
            case "txt": case "md": case "json": case "xml": case "html": case "css":
            case "yaml": case "yml": case "toml": case "csv": case "ini": case "conf":
            case "log": case "prop": case "properties":
                return FileType.TEXT;
            case "pdf":
                return FileType.PDF;
            case "doc": case "docx": case "odt": case "rtf":
                return FileType.DOCUMENT;
            default:
                return null;
        }
    }

    /**
     * Icon refinements spec 006 rev 2 (RF-7 to RF-11): a more specific icon
     * for a well-known name or extension, painted in its family's tint.
     *
     * <p>Precedence: {@code readme.md} &gt; {@code changelog.md} &gt; the
     * extension variants ({@code md}, {@code json}, {@code properties},
     * {@code js}). {@code NONE} means "use the family icon".
     */
    public enum IconVariant {
        /** No refinement; the family icon is used. */
        NONE,
        /** Markdown sources: the vendored Material Symbols markdown glyph. */
        MARKDOWN,
        /** README documentation: outlined info (RF-7). */
        INFO,
        /** CHANGELOG: history (RF-8). */
        HISTORY,
        /** Java property files: settings (RF-9). */
        SETTINGS,
        /** JSON data: the vendored Material Symbols file_json glyph. */
        JSON,
        /** JavaScript sources (RF-11). */
        JS,
        /** Git dotfiles: the vendored official git glyph (RF-13). */
        GIT,
        /** Kotlin sources (rev 5, RF-15): the vendored official Kotlin glyph. */
        KT,
        /** Java sources (rev 6, RF-16): the coffee cup glyph in Java red. */
        JAVA,
        /** Python sources (rev 7, RF-17): the two-tone official Python glyph
         * (blue and yellow baked in; the variant kills the tint). */
        PY
    }

    /**
     * Maps a file name to its icon variant, or {@code NONE} when the file just
     * uses its family icon (RF-12). Name-based variants win over
     * extension-based ones, and both are case-insensitive.
     *
     * @param name file name (may be null)
     * @return the {@link IconVariant}, or {@link IconVariant#NONE}
     */
    public static IconVariant iconVariantFor(String name) {
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            String bare = lower.startsWith(".") ? lower.substring(1) : lower;
            switch (bare) {
                case "gitignore": case "gitattributes": case "gitconfig":
                case "gitmodules": case "gitkeep":
                    return IconVariant.GIT;
                default:
                    break;
            }
            if (lower.equals("readme.md") || lower.equals("readme.markdown")) {
                return IconVariant.INFO;
            }
            if (lower.equals("changelog.md") || lower.equals("changelog.markdown")) {
                return IconVariant.HISTORY;
            }
        }
        switch (getFileExtension(name)) {
            case "md": case "markdown":
                return IconVariant.MARKDOWN;
            case "json":
                return IconVariant.JSON;
            case "properties": case "prop":
                return IconVariant.SETTINGS;
            case "js":
                return IconVariant.JS;
            case "kt": case "kts":
                return IconVariant.KT;
            case "java":
                return IconVariant.JAVA;
            case "py":
                return IconVariant.PY;
            default:
                return IconVariant.NONE;
        }
    }

    private FileTypeDetector() {
        // Static utility class; no instances (Termux codebase pattern).
    }
}

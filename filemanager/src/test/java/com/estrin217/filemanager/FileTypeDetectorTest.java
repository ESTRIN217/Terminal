package com.estrin217.filemanager;

import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for {@link FileTypeDetector}, the pure extension-to-family mapping
 * used by the file manager list icons (spec 006, RF-2, RF-3, RF-5).
 *
 * <p>Covers every family with at least one extension from the spec list, plus
 * the edge cases defined in the spec: no dot, trailing dot, multiple dots,
 * hidden files and mixed case.
 */
public class FileTypeDetectorTest {

    // -- RF-2: one extension per family, list order as in the spec ------------

    @Test
    public void testCodeFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("MainActivity.kt"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("Main.java"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("script.py"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("run.sh"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor(".zshrc_script.zsh"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("main.c"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("geo.cpp"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("lib.rs"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("server.go"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("app.js"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("app.ts"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("tool.rb"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("script.pl"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("game.lua"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("query.sql"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("native.if.h"));
    }

    @Test
    public void testBinaryFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("termux.apk"));
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("lwjgl.jar"));
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("classes.dex"));
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("libcore.so"));
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("pkg.deb"));
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("setup.exe"));
        Assert.assertEquals(FileTypeDetector.FileType.BINARY, FileTypeDetector.familyFor("image.bin"));
    }

    @Test
    public void testArchiveFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("backup.zip"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("rootfs.tar"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("app.tar.gz"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("app.tgz"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("core.bz2"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("rootfs.xz"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("pack.7z"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("dl.rar"));
    }

    @Test
    public void testImageFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("wall.png"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("photo.jpg"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("photo.jpeg"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("anim.gif"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("bg.webp"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("icon.bmp"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("logo.svg"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("fav.ico"));
    }

    @Test
    public void testAudioFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("song.mp3"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("song.flac"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("tone.ogg"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("track.oga"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("alarm.wav"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("voice.opus"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("audiobook.m4a"));
        Assert.assertEquals(FileTypeDetector.FileType.AUDIO, FileTypeDetector.familyFor("chime.mid"));
    }

    @Test
    public void testVideoFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.VIDEO, FileTypeDetector.familyFor("clip.mp4"));
        Assert.assertEquals(FileTypeDetector.FileType.VIDEO, FileTypeDetector.familyFor("film.mkv"));
        Assert.assertEquals(FileTypeDetector.FileType.VIDEO, FileTypeDetector.familyFor("old.avi"));
        Assert.assertEquals(FileTypeDetector.FileType.VIDEO, FileTypeDetector.familyFor("clip.webm"));
        Assert.assertEquals(FileTypeDetector.FileType.VIDEO, FileTypeDetector.familyFor("shot.mov"));
        Assert.assertEquals(FileTypeDetector.FileType.VIDEO, FileTypeDetector.familyFor("itunes.m4v"));
    }

    @Test
    public void testTextFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("notes.txt"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("README.md"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("package.json"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("layout.xml"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("index.html"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("style.css"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("config.yaml"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("config.yml"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("Cargo.toml"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("table.csv"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("setup.ini"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("app.conf"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("boot.log"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("build.prop"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("sdk.properties"));
    }

    @Test
    public void testPdfFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.PDF, FileTypeDetector.familyFor("manual.pdf"));
    }

    @Test
    public void testDocumentFamily() {
        Assert.assertEquals(FileTypeDetector.FileType.DOCUMENT, FileTypeDetector.familyFor("contract.doc"));
        Assert.assertEquals(FileTypeDetector.FileType.DOCUMENT, FileTypeDetector.familyFor("contract.docx"));
        Assert.assertEquals(FileTypeDetector.FileType.DOCUMENT, FileTypeDetector.familyFor("thesis.odt"));
        Assert.assertEquals(FileTypeDetector.FileType.DOCUMENT, FileTypeDetector.familyFor("letter.rtf"));
    }

    // -- RF-3: unknown files stay generic ------------------------------------

    @Test
    public void testUnknownExtensionIsGeneric() {
        Assert.assertNull(FileTypeDetector.familyFor("archive.zst"));
        Assert.assertNull(FileTypeDetector.familyFor("blob.dat"));
        Assert.assertNull(FileTypeDetector.familyFor("disk.img"));
        Assert.assertNull(FileTypeDetector.familyFor("README.TXT.UNKNOWN"));
    }

    // -- RF-3: files without an extension stay generic ------------------------

    @Test
    public void testNoExtensionIsGeneric() {
        Assert.assertNull(FileTypeDetector.familyFor("Makefile"));
        Assert.assertNull(FileTypeDetector.familyFor("LICENSE"));
        Assert.assertNull(FileTypeDetector.familyFor("."));
        Assert.assertNull(FileTypeDetector.familyFor(".."));
    }

    // -- Edge cases from the spec ---------------------------------------------

    @Test
    public void testExtensionIgnoresCase() {
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("APP.PY"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("App.Py"));
        Assert.assertEquals(FileTypeDetector.FileType.CODE, FileTypeDetector.familyFor("script.SH"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("IMG.Jpg"));
        Assert.assertEquals(FileTypeDetector.FileType.IMAGE, FileTypeDetector.familyFor("img.JPG"));
    }

    @Test
    public void testMultipleDotsUsesLastExtension() {
        Assert.assertEquals("gz", FileTypeDetector.getFileExtension("app.tar.gz"));
        Assert.assertEquals(FileTypeDetector.FileType.ARCHIVE, FileTypeDetector.familyFor("app.tar.gz"));
        Assert.assertEquals("txt", FileTypeDetector.getFileExtension("Mi documento final.txt"));
        Assert.assertEquals(FileTypeDetector.FileType.TEXT, FileTypeDetector.familyFor("Mi documento final.txt"));
    }

    @Test
    public void testHiddenFileHasNoExtension() {
        Assert.assertEquals("", FileTypeDetector.getFileExtension(".bashrc"));
        Assert.assertNull(FileTypeDetector.familyFor(".bashrc"));
        Assert.assertNull(FileTypeDetector.familyFor(".gitignore"));
    }

    @Test
    public void testTrailingDotHasNoExtension() {
        Assert.assertEquals("", FileTypeDetector.getFileExtension("file."));
        Assert.assertNull(FileTypeDetector.familyFor("file."));
    }

    // -- RF-7 a RF-11: icon variants (rev 2) -------------------------------

    @Test
    public void testReadmeIsInfo() {
        Assert.assertEquals(FileTypeDetector.IconVariant.INFO, FileTypeDetector.iconVariantFor("README.md"));
        Assert.assertEquals(FileTypeDetector.IconVariant.INFO, FileTypeDetector.iconVariantFor("readme.md"));
    }

    @Test
    public void testChangelogIsHistory() {
        Assert.assertEquals(FileTypeDetector.IconVariant.HISTORY, FileTypeDetector.iconVariantFor("CHANGELOG.md"));
        Assert.assertEquals(FileTypeDetector.IconVariant.HISTORY, FileTypeDetector.iconVariantFor("changelog.MD"));
    }

    @Test
    public void testMarkdownVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.MARKDOWN, FileTypeDetector.iconVariantFor("notes.md"));
        Assert.assertEquals(FileTypeDetector.IconVariant.MARKDOWN, FileTypeDetector.iconVariantFor("notes.markdown"));
        Assert.assertEquals(FileTypeDetector.IconVariant.MARKDOWN, FileTypeDetector.iconVariantFor("Notas.MD"));
    }

    @Test
    public void testJsonVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.JSON, FileTypeDetector.iconVariantFor("package.json"));
        Assert.assertEquals(FileTypeDetector.IconVariant.JSON, FileTypeDetector.iconVariantFor("Package.JSON"));
    }

    @Test
    public void testSettingsVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.SETTINGS, FileTypeDetector.iconVariantFor("sdk.properties"));
        Assert.assertEquals(FileTypeDetector.IconVariant.SETTINGS, FileTypeDetector.iconVariantFor("build.prop"));
        Assert.assertEquals(FileTypeDetector.IconVariant.SETTINGS, FileTypeDetector.iconVariantFor("BUILD.PROP"));
    }

    @Test
    public void testJavaScriptVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.JS, FileTypeDetector.iconVariantFor("app.js"));
        Assert.assertEquals(FileTypeDetector.IconVariant.JS, FileTypeDetector.iconVariantFor("APP.JS"));
    }

    @Test
    public void testNameSpecificBeatsExtensionVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.INFO, FileTypeDetector.iconVariantFor("README.markdown"));
        Assert.assertEquals(FileTypeDetector.IconVariant.HISTORY, FileTypeDetector.iconVariantFor("changelog.markdown"));
    }

    // -- RF-13: git dotfiles (rev 3) -------------------------------------------

    @Test
    public void testGitDotfilesAreGitVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor(".gitignore"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor(".gitattributes"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor(".gitconfig"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor(".gitmodules"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor(".gitkeep"));
    }

    @Test
    public void testGitVariantIgnoresCaseAndLeadingDot() {
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor("gitignore"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor("GITATTRIBUTES"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor(".GITMODULES"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GIT, FileTypeDetector.iconVariantFor("GitConfig"));
    }

    @Test
    public void testGitLookalikesStayNone() {
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("gitui.cfg"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor(".profile"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor(".gitignore.bak"));
        // "github_notes.md" is a regular markdown file (ext md), NOT a git
        // dotfile: the name match is exact, never a substring.
        Assert.assertEquals(FileTypeDetector.IconVariant.MARKDOWN, FileTypeDetector.iconVariantFor("github_notes.md"));
    }

    // -- RF-15: Kotlin variants (rev 5) ----------------------------------------

    @Test
    public void testKotlinExtensionsAreKtVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.KT, FileTypeDetector.iconVariantFor("MainActivity.kt"));
        Assert.assertEquals(FileTypeDetector.IconVariant.KT, FileTypeDetector.iconVariantFor("script.kts"));
        Assert.assertEquals(FileTypeDetector.IconVariant.KT, FileTypeDetector.iconVariantFor("NOTES.KT"));
        Assert.assertEquals(FileTypeDetector.IconVariant.KT, FileTypeDetector.iconVariantFor("Util.KTS"));
    }

    @Test
    public void testKotlinLookalikesStayNone() {
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("a.kt.bak"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("test.ktsx"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("ledger.kt.bkup"));
    }

    // -- RF-16: Java coffee cup (rev 6) -----------------------------------------

    @Test
    public void testJavaExtensionIsCafeVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.JAVA, FileTypeDetector.iconVariantFor("Main.java"));
        Assert.assertEquals(FileTypeDetector.IconVariant.JAVA, FileTypeDetector.iconVariantFor("Main.JAVA"));
        Assert.assertEquals(FileTypeDetector.IconVariant.JAVA, FileTypeDetector.iconVariantFor("My.Class.java"));
    }

    @Test
    public void testJavaLookalikesStayNone() {
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("Main.javac"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("a.java.bak"));
        // "notes.java.md" ends in .md: regular markdown, NOT a Java file.
        Assert.assertEquals(FileTypeDetector.IconVariant.MARKDOWN, FileTypeDetector.iconVariantFor("notes.java.md"));
    }

    // -- RF-17: Python two-tone (rev 7) ------------------------------------------

    @Test
    public void testPythonExtensionIsPyVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.PY, FileTypeDetector.iconVariantFor("main.py"));
        Assert.assertEquals(FileTypeDetector.IconVariant.PY, FileTypeDetector.iconVariantFor("Main.PY"));
        Assert.assertEquals(FileTypeDetector.IconVariant.PY, FileTypeDetector.iconVariantFor("v.poli.py"));
    }

    @Test
    public void testPythonLookalikesStayNone() {
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("a.py.bak"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("p.pyw"));
        // "audit.python.md" ends in .md: regular markdown.
        Assert.assertEquals(FileTypeDetector.IconVariant.MARKDOWN, FileTypeDetector.iconVariantFor("audit.python.md"));
    }

    // -- RF-18: Gradle elephant (rev 8) ------------------------------------------

    @Test
    public void testGradleExtensionIsGradleVariant() {
        Assert.assertEquals(FileTypeDetector.IconVariant.GRADLE, FileTypeDetector.iconVariantFor("build.gradle"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GRADLE, FileTypeDetector.iconVariantFor("settings.GRADLE"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GRADLE, FileTypeDetector.iconVariantFor("my.project.gradle"));
        // Kotlin DSL: the ".gradle.kts" name suffix wins over the plain kts variant.
        Assert.assertEquals(FileTypeDetector.IconVariant.GRADLE, FileTypeDetector.iconVariantFor("build.gradle.kts"));
        Assert.assertEquals(FileTypeDetector.IconVariant.GRADLE, FileTypeDetector.iconVariantFor("SETTINGS.GRADLE.KTS"));
        // Build scripts have no family (like script.kts); the variant carries the icon alone.
        Assert.assertNull(FileTypeDetector.familyFor("build.gradle"));
        Assert.assertNull(FileTypeDetector.familyFor("build.gradle.kts"));
    }

    @Test
    public void testGradleLookalikesStayNoneOrKts() {
        // Lookalikes keep the generic icon (exact extensions only).
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("a.gradle.bak"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("plugin.gradlew"));
        // Hidden file and dotless names never reach the variant.
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor(".gradle"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("gradle"));
        // Plain Kotlin DSL scripts stay the Kotlin variant; the suffix is exact.
        Assert.assertEquals(FileTypeDetector.IconVariant.KT, FileTypeDetector.iconVariantFor("script.kts"));
        Assert.assertEquals(FileTypeDetector.IconVariant.KT, FileTypeDetector.iconVariantFor("Util.KTS"));
        // gradle.properties is a property file: it keeps its settings gear.
        Assert.assertEquals(FileTypeDetector.IconVariant.SETTINGS, FileTypeDetector.iconVariantFor("gradle.properties"));
    }

    @Test
    public void testNoVariantFallsBackToFamily() {
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("app.ts"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("a.md.txt"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor("Makefile"));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor(null));
        Assert.assertEquals(FileTypeDetector.IconVariant.NONE, FileTypeDetector.iconVariantFor(""));
    }
}

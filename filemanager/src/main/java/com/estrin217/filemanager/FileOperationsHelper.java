package com.estrin217.filemanager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileOperationsHelper {

    private static final String LOG_TAG = "FileOperationsHelper";
    private static List<File> sClipboardFiles = new ArrayList<>();
    private static ClipboardOperation sClipboardOperation = ClipboardOperation.NONE;

    public enum ClipboardOperation {
        NONE, COPY, CUT
    }

    public static List<File> getClipboardFiles() {
        return sClipboardFiles;
    }

    public static ClipboardOperation getClipboardOperation() {
        return sClipboardOperation;
    }

    public static boolean hasClipboard() {
        return sClipboardOperation != ClipboardOperation.NONE && !sClipboardFiles.isEmpty();
    }

    public static void setClipboard(List<File> files, ClipboardOperation operation) {
        sClipboardFiles = new ArrayList<>(files);
        sClipboardOperation = operation;
    }

    public static void clearClipboard() {
        sClipboardFiles.clear();
        sClipboardOperation = ClipboardOperation.NONE;
    }

    /**
     * Whether {@code file} is a symbolic link.
     *
     * <p>Uses {@code android.system.Os.readlink} via reflection (available since
     * API 21, so it also works below API 26 where {@code java.nio.file} is
     * missing) with a {@code java.nio} fallback for JVM unit tests.
     *
     * @param file The file to check.
     * @return {@code true} if it is a symlink, {@code false} otherwise.
     */
    @SuppressLint("NewApi") // java.nio.file guarded by try/catch; JVM unit-test fallback (minSdk 24 devices use Os.readlink first)
    public static boolean isSymlink(File file) {
        if (file == null) return false;
        if (readlinkViaOs(file.getAbsolutePath()) != null) return true;
        try {
            return Files.isSymbolicLink(file.toPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Raw target of a symlink as stored in the link (may be relative).
     *
     * @param file The symlink to read.
     * @return The raw target, or {@code null} if not a symlink or unreadable.
     */
    @SuppressLint("NewApi") // java.nio.file guarded by try/catch; JVM unit-test fallback (minSdk 24 devices use Os.readlink first)
    public static String readSymlinkTargetRaw(File file) {
        if (file == null) return null;
        String raw = readlinkViaOs(file.getAbsolutePath());
        if (raw != null) return raw;
        try {
            if (Files.isSymbolicLink(file.toPath()))
                return Files.readSymbolicLink(file.toPath()).toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Resolves the target of {@code file} against its parent when relative.
     *
     * @param file The symlink to resolve.
     * @return The target file, or {@code null} if {@code file} is not a symlink.
     */
    public static File resolveSymlinkTarget(File file) {
        String raw = readSymlinkTargetRaw(file);
        if (raw == null) return null;
        File rawFile = new File(raw);
        if (rawFile.isAbsolute()) return rawFile;
        File parent = file.getParentFile();
        return parent != null ? new File(parent, raw) : rawFile;
    }

    /**
     * Whether {@code file} is a symlink whose target does not exist.
     *
     * <p>Symlinks managed by Android for shared storage (e.g. {@code /sdcard}
     * or {@code /storage/emulated/0}) are never treated as broken, even when
     * their internal target chain ({@code /storage/self/primary} or
     * {@code /mnt/user/0/...}) is not directly statable with
     * {@code java.io.File}: those are system-managed paths, not dangling links.
     *
     * @param file The file to check.
     * @return {@code true} for broken (dangling) symlinks.
     */
    public static boolean isBrokenSymlink(File file) {
        if (!isSymlink(file)) return false;
        File target = resolveSymlinkTarget(file);
        if (target == null) return true;
        if (isSharedStoragePath(file) || isSharedStoragePath(target)) return false;
        return !target.exists();
    }

    /**
     * Whether {@code file} is a symlink resolving to an existing directory.
     *
     * @param file The file to check.
     * @return {@code true} if linked target is a directory.
     */
    public static boolean resolvesToDirectory(File file) {
        if (!isSymlink(file)) return false;
        File target = resolveSymlinkTarget(file);
        return target != null && target.isDirectory();
    }

    /**
     * File to actually open/share/navigate for {@code file}: the canonical
     * target when it is a healthy symlink, otherwise {@code file} itself.
     *
     * @param file The file the user tapped.
     * @return The resolved file, never {@code null} (falls back to input).
     */
    public static File resolveFileForOpen(File file) {
        if (file == null) return null;
        File target = resolveSymlinkTarget(file);
        if (target == null || !target.exists()) return file;
        // Never canonicalize inside shared storage: the FUSE-accessible paths
        // (/sdcard, /storage/emulated/0/...) must be kept as-is, otherwise the
        // chain to /storage/self/primary or /mnt/user/0/... cannot be listed
        // by java.io.File even for an app granted MANAGE_EXTERNAL_STORAGE.
        if (isSharedStoragePath(target)) return target;
        try {
            return target.getCanonicalFile();
        } catch (IOException | SecurityException e) {
            return target;
        }
    }

    /**
     * Whether {@code path} lives on Android shared storage. Such paths (and
     * their symlink targets) must never be canonicalized or resolved, since
     * only the FUSE-visible spelling is listable from {@code java.io.File}.
     *
     * @param file The file to check.
     * @return {@code true} when the path is on shared storage.
     */
    public static boolean isSharedStoragePath(File file) {
        if (file == null) return false;
        String path = file.getAbsolutePath();
        return path.equals("/sdcard") || path.startsWith("/sdcard/") ||
            path.equals("/storage") || path.startsWith("/storage/");
    }

    /**
     * Lists the children of {@code dir}, falling back to the native
     * {@code android.system.Os.opendir}/{@code readdir} pair when
     * {@code java.io.File.listFiles()} returns {@code null} (some shared
     * storage mounts only enumerate through the raw syscalls the terminal
     * uses). Returns {@code null} when the directory cannot be listed at all.
     *
     * @param dir The directory to list.
     * @return The children, or {@code null} on failure.
     */
    public static File[] listFiles(File dir) {
        if (dir == null) return null;
        File[] listed = dir.listFiles();
        if (listed != null) return listed;
        if (!isSharedStoragePath(dir) && !dir.isDirectory()) return null;
        List<String> names = listNamesViaOs(dir.getAbsolutePath());
        if (names == null) return null;
        File[] files = new File[names.size()];
        for (int i = 0; i < names.size(); i++)
            files[i] = new File(dir, names.get(i));
        return files;
    }

    /**
     * Lists entry names via {@code android.system.Os.opendir}/{@code readdir}
     * reflectively (the same native calls {@code ls} uses) so it also degrades
     * to {@code null} on JVM unit tests where the Android runtime is absent.
     *
     * @param path Directory to enumerate.
     * @return Entry names without {@code .} and {@code ..}, or {@code null}.
     */
    private static List<String> listNamesViaOs(String path) {
        try {
            Class<?> osClass = Class.forName("android.system.Os");
            Class<?> fdClass = Class.forName("android.system.StructDirFd");
            Object dirFd = osClass.getMethod("opendir", String.class).invoke(null, path);
            List<String> names = new ArrayList<>();
            String name;
            while ((name = (String) osClass.getMethod("readdir", fdClass).invoke(null, dirFd)) != null) {
                if (name.equals(".") || name.equals("..")) continue;
                names.add(name);
            }
            osClass.getMethod("closedir", fdClass).invoke(null, dirFd);
            return names;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Creates a symlink {@code name} inside {@code parent} pointing at {@code target}.
     *
     * @param parent The directory holding the new link.
     * @param name   Name of the new link.
     * @param target Link target (absolute or relative to {@code parent}).
     * @return {@code true} on success.
     */
    public static boolean createSymlink(File parent, String name, String target) {
        if (parent == null || name == null || name.isEmpty() || target == null || target.isEmpty())
            return false;
        File link = new File(parent, name);
        if (link.exists() || isSymlink(link)) return false;
        return createSymlinkAt(link, target);
    }

    /**
     * Reads a link target via {@code android.system.Os.readlink} reflectively
     * so the class stays loadable on JVM unit tests (no Android runtime).
     *
     * @param path Absolute path to read.
     * @return The raw target, or {@code null} when not a link / on error.
     */
    private static String readlinkViaOs(String path) {
        try {
            Class<?> osClass = Class.forName("android.system.Os");
            Method readlink = osClass.getMethod("readlink", String.class);
            Object result = readlink.invoke(null, path);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Creates a symlink at {@code link} pointing at {@code target}.
     *
     * @param link   Full path of the link to create.
     * @param target Raw target stored in the link.
     * @return {@code true} on success.
     */
    @SuppressLint("NewApi") // java.nio.file guarded by try/catch; JVM unit-test fallback (minSdk 24 devices use Os.symlink first)
    private static boolean createSymlinkAt(File link, String target) {
        try {
            Class<?> osClass = Class.forName("android.system.Os");
            Method symlink = osClass.getMethod("symlink", String.class, String.class);
            symlink.invoke(null, target, link.getAbsolutePath());
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean createFile(File parent, String name) {
        File file = new File(parent, name);
        try {
            return file.createNewFile();
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean createDirectory(File parent, String name) {
        File dir = new File(parent, name);
        return dir.mkdir();
    }

    public static boolean renameFile(File file, String newName) {
        File dest = new File(file.getParent(), newName);
        return file.renameTo(dest);
    }

    public static boolean deleteFile(File file) {
        if (file == null) return false;
        // Unlink only: never recurse into a symlink to a directory, otherwise
        // the target's contents would be deleted instead of just the link.
        if (isSymlink(file)) return file.delete();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteFile(child)) return false;
                }
            }
        }
        return file.delete();
    }

    public static boolean copyFile(File source, File dest) {
        if (source == null || dest == null) return false;
        // Preserve links as links instead of copying the target's contents.
        if (isSymlink(source)) {
            String raw = readSymlinkTargetRaw(source);
            if (raw == null) return false;
            if (dest.exists() && !dest.delete()) return false;
            if (dest.getParentFile() != null && !dest.getParentFile().exists())
                dest.getParentFile().mkdirs();
            return createSymlinkAt(dest, raw);
        }
        if (source.isDirectory()) {
            if (!dest.mkdirs()) return false;
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!copyFile(child, new File(dest, child.getName()))) return false;
                }
            }
            return true;
        } else {
            try {
                if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    public static boolean moveFile(File source, File dest) {
        if (source.renameTo(dest)) return true;
        if (copyFile(source, dest)) {
            deleteFile(source);
            return true;
        }
        return false;
    }

    public static String getFileDetails(Context context, File file) {
        StringBuilder details = new StringBuilder();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        details.append(context.getString(R.string.detail_name)).append(": ").append(file.getName()).append("\n");
        details.append(context.getString(R.string.detail_path)).append(": ").append(file.getAbsolutePath()).append("\n");
        details.append(context.getString(R.string.detail_size)).append(": ").append(formatSize(file.length())).append("\n");
        details.append(context.getString(R.string.detail_modified)).append(": ").append(dateFormat.format(new Date(file.lastModified()))).append("\n");

        String perms = getPermissions(file);
        details.append(context.getString(R.string.detail_permissions)).append(": ").append(perms).append("\n");

        String mimeType = getMimeType(file.getName());
        details.append(context.getString(R.string.detail_mime)).append(": ").append(mimeType).append("\n");

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            int count = children != null ? children.length : 0;
            details.append(context.getString(R.string.detail_contents)).append(": ").append(count).append(" ").append(context.getString(R.string.detail_items));
        }

        return details.toString();
    }

    private static String getPermissions(File file) {
        StringBuilder perms = new StringBuilder();
        perms.append(file.isDirectory() ? "d" : "-");
        perms.append(file.canRead() ? "r" : "-");
        perms.append(file.canWrite() ? "w" : "-");
        perms.append(file.canExecute() ? "x" : "-");
        perms.append("---");
        perms.append("---");
        return perms.toString();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        if (exp > 6) exp = 6;
        String pre = "KMGTPE".charAt(exp - 1) + "iB";
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024, exp), pre);
    }

    /**
     * Computes the apparent size of a directory in bytes by recursively summing
     * the {@link File#length()} of every regular file below it.
     *
     * <p>Symbolic links are skipped so link loops cannot recurse forever and
     * the same inode is not counted twice. Directories that cannot be listed
     * (e.g. missing permissions) are counted as empty.
     *
     * @param dir The directory to measure.
     * @return The total size in bytes, or {@code 0} if {@code dir} is not a
     *         directory or cannot be read.
     */
    public static long getDirectorySize(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        long total = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (isSymlink(child)) continue;
                total += child.isDirectory() ? getDirectorySize(child) : child.length();
            }
        }
        return total;
    }

    public static String getMimeType(String fileName) {
        String ext = fileName.contains(".") ?
            fileName.substring(fileName.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT) : "";
        switch (ext) {
            case ".txt": case ".md": case ".log": case ".sh": case ".py":
            case ".java": case ".xml": case ".json": case ".yml": case ".yaml":
            case ".conf": case ".cfg": case ".ini": case ".properties":
                return "text/plain";
            case ".png": return "image/png";
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".pdf": return "application/pdf";
            case ".html": case ".htm": return "text/html";
            case ".zip": return "application/zip";
            case ".tar": case ".gz": return "application/gzip";
            case ".mp3": return "audio/mpeg";
            case ".mp4": return "video/mp4";
            default: return "*/*";
        }
    }

    public static void shareFiles(Context context, List<File> files) {
        if (files.isEmpty()) return;
        List<File> resolved = new ArrayList<>(files.size());
        for (File f : files) resolved.add(resolveFileForOpen(f));
        Intent intent;
        if (resolved.size() == 1) {
            intent = new Intent(Intent.ACTION_SEND);
            Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", resolved.get(0));
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setType(getMimeType(resolved.get(0).getName()));
        } else {
            intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            ArrayList<Uri> uris = new ArrayList<>();
            String mimeType = "*/*";
            for (File f : resolved) {
                Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", f);
                uris.add(uri);
                String mt = getMimeType(f.getName());
                if (!mt.equals("*/*")) mimeType = mt;
            }
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.setType(mimeType);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)));
    }
}

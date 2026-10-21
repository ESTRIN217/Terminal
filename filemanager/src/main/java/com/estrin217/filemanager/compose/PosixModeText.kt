package com.estrin217.filemanager.compose

import com.termux.shared.file.filesystem.FilePermission
import com.termux.shared.file.filesystem.FilePermissions

/**
 * Pure renderer of the permissions-tab mode row (spec 009, RF-12): merges
 * the symbolic form that `FilePermissions.toString` already computes for
 * `FileAttributes.permissions()` with this module's octal suffix. No POSIX
 * bit logic is re-implemented here.
 */
object PosixModeText {

    /**
     * Formats a raw POSIX mode plus its derived permission set as
     * `"rwxr-xr-x (755)"`.
     *
     * @param mode Raw `st_mode` value from `FileAttributes.mode()`; the
     * octal suffix renders the lower 12 bits (`0o7777`), so set-bit specials
     * (setuid, setgid, sticky) survive while the file-type prefix does not
     * pollute the digits.
     * @param permissions The permission set derived by
     * `FileAttributes.permissions()` (the 9 POSIX bits; specials are not
     * representable in its enum, mirroring ojluni's PosixFilePermission).
     * @return The dialog text, e.g. `"rw-r--r-- (644)"`.
     */
    fun format(mode: Int, permissions: Set<FilePermission>): String {
        val symbolic = FilePermissions.toString(permissions)
        val octal = java.lang.Integer.toString(mode and 0xFFF, 8)
        return "$symbolic ($octal)"
    }
}

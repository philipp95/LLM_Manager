package com.llmmanager.server.api

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import org.slf4j.LoggerFactory

/** Owner-only files and directories for secrets and server state. No-ops where POSIX permissions do not exist. */
object SecureFiles {
    private val log = LoggerFactory.getLogger(SecureFiles::class.java)
    private val GROUP_OR_OTHER = setOf(
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
    )

    private fun posix(file: File) = file.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

    /**
     * Creates [dir] (and missing parents) as 0700. An existing directory is tightened to 0700
     * only with [tighten], for directories that belong to this server alone; otherwise a
     * too-open directory is reported, not changed.
     */
    fun privateDir(dir: File, tighten: Boolean = false): File {
        dir.parentFile?.mkdirs()
        if (!dir.exists()) {
            if (posix(dir)) Files.createDirectory(dir.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else dir.mkdirs()
        } else if (posix(dir) && Files.getPosixFilePermissions(dir.toPath()).any { it in GROUP_OR_OTHER }) {
            if (tighten) {
                log.warn("Tightening permissions of {} to 0700", dir)
                Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
            } else {
                log.warn("{} is accessible to other users; consider chmod 700", dir)
            }
        }
        return dir
    }

    /** Whether [file] is readable by the owner only (always true without POSIX permissions). */
    fun isPrivate(file: File): Boolean = !posix(file) || Files.getPosixFilePermissions(file.toPath()).none { it in GROUP_OR_OTHER }

    /**
     * Writes [text] to [file] as 0600 from the start: a temporary file created owner-only, then
     * moved over [file], so a secret is never readable by others, not even for a moment.
     */
    fun writePrivate(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.delete()
        if (posix(file)) {
            Files.createFile(temp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        temp.writeText(text)
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** Tightens a file this server owns, such as its own token, to 0600. */
    fun makePrivate(file: File) {
        if (posix(file) && !isPrivate(file)) {
            log.warn("Tightening permissions of {} to 0600", file)
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }
}

package com.dimonchik.kvas.security

import java.io.File

/** Path boundary helper for future file tools; rejects traversal and symlink escapes. */
class WorkspaceGuard(root: File) {
    private val canonicalRoot = root.canonicalFile

    fun resolve(relativePath: String): File {
        require(!relativePath.startsWith("/")) { "Use a path relative to the selected workspace." }
        val resolved = File(canonicalRoot, relativePath).canonicalFile
        require(resolved.toPath().startsWith(canonicalRoot.toPath())) { "Path escapes the selected workspace." }
        return resolved
    }
}

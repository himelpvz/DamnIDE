package com.hypex.damnide.core.terminal.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorException
import org.apache.commons.compress.compressors.CompressorStreamFactory

data class ResolvedRootfs(
    val uri: Uri,
    val displayName: String,
    val absolutePath: String,
)

object RootfsResolver {
    fun persistPermission(contentResolver: ContentResolver, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    fun validateAndResolve(context: Context, uri: Uri): Result<ResolvedRootfs> {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: return Result.failure(IllegalArgumentException("Unable to access selected rootfs tree."))

        val hasBin = tree.findFile("bin")?.isDirectory == true
        val hasUsr = tree.findFile("usr")?.isDirectory == true
        val hasLib = tree.findFile("lib")?.isDirectory == true || tree.findFile("lib64")?.isDirectory == true

        if (!hasBin || !hasUsr || !hasLib) {
            return Result.failure(
                IllegalArgumentException("Invalid rootfs: expected /bin, /usr, and /lib (or /lib64)."),
            )
        }

        val path = resolveFilesystemPath(uri)
            ?: return Result.failure(
                IllegalArgumentException("Unable to resolve filesystem path from SAF URI. Select a local storage folder."),
            )

        val validation = validateDirectoryPath(path)
        if (validation.isFailure) return Result.failure(validation.exceptionOrNull()!!)

        return Result.success(
            ResolvedRootfs(
                uri = uri,
                displayName = tree.name ?: "rootfs",
                absolutePath = path,
            ),
        )
    }

    fun validateRootfsTree(context: Context, uri: Uri): Result<Unit> {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: return Result.failure(IllegalArgumentException("Unable to access selected rootfs tree."))
        val hasBin = tree.findFile("bin")?.isDirectory == true
        val hasUsr = tree.findFile("usr")?.isDirectory == true
        val hasLib = tree.findFile("lib")?.isDirectory == true || tree.findFile("lib64")?.isDirectory == true
        val hasShell = tree.findFile("bin")?.findFile("sh")?.isFile == true
        if (!hasBin || !hasUsr || !hasLib || !hasShell) {
            return Result.failure(
                IllegalArgumentException("Invalid rootfs directory: expected /bin, /usr, /lib (or /lib64), and /bin/sh."),
            )
        }
        return Result.success(Unit)
    }

    fun validateDirectoryPath(path: String): Result<String> {
        val root = File(path)
        if (!root.exists() || !root.isDirectory) {
            return Result.failure(IllegalArgumentException("Rootfs directory not found: $path"))
        }

        val hasBin = File(root, "bin").isDirectory
        val hasUsr = File(root, "usr").isDirectory
        val hasLib = File(root, "lib").isDirectory || File(root, "lib64").isDirectory
        val hasShell = File(root, "bin/sh").isFile

        if (!hasBin || !hasUsr || !hasLib || !hasShell) {
            return Result.failure(
                IllegalArgumentException("Invalid rootfs directory: expected /bin, /usr, /lib (or /lib64), and /bin/sh."),
            )
        }

        return Result.success(root.absolutePath)
    }

    fun installArchiveToDirectory(context: Context, sourceUri: Uri, targetRootfsDir: File): Result<String> {
        val sourceName = DocumentFile.fromSingleUri(context, sourceUri)?.name ?: "rootfs"

        if (targetRootfsDir.exists()) {
            targetRootfsDir.deleteRecursively()
        }
        if (!targetRootfsDir.mkdirs()) {
            return Result.failure(IllegalStateException("Unable to create install directory: ${targetRootfsDir.absolutePath}"))
        }

        val extractResult = extractArchive(context.contentResolver, sourceUri, sourceName, targetRootfsDir)
        if (extractResult.isFailure) return Result.failure(extractResult.exceptionOrNull()!!)

        return detectInstalledRootfs(targetRootfsDir).fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )
    }

    private fun detectInstalledRootfs(baseDir: File): Result<String> {
        val direct = validateDirectoryPath(baseDir.absolutePath).getOrNull()
        if (direct != null) return Result.success(direct)

        val child = baseDir.listFiles()
            ?.firstOrNull { it.isDirectory && validateDirectoryPath(it.absolutePath).isSuccess }
            ?.absolutePath
        if (child != null) return Result.success(child)

        return Result.failure(
            IllegalArgumentException("Installed archive does not contain a valid rootfs layout."),
        )
    }

    private fun extractArchive(
        contentResolver: ContentResolver,
        uri: Uri,
        sourceName: String,
        targetDir: File,
    ): Result<Unit> {
        return if (sourceName.lowercase().endsWith(".zip")) {
            extractZip(contentResolver, uri, targetDir)
        } else {
            extractTarLike(contentResolver, uri, sourceName, targetDir)
        }
    }

    private fun extractZip(contentResolver: ContentResolver, uri: Uri, targetDir: File): Result<Unit> {
        val input = contentResolver.openInputStream(uri)
            ?: return Result.failure(IllegalArgumentException("Unable to open selected rootfs file."))
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name).canonicalFile
                    if (!outFile.path.startsWith(targetDir.canonicalPath + File.separator)) {
                        return Result.failure(IllegalArgumentException("Invalid archive entry path."))
                    }

                    if (entry.isDirectory) {
                        if (!outFile.exists() && !outFile.mkdirs()) {
                            return Result.failure(IllegalStateException("Unable to create directory: ${outFile.absolutePath}"))
                        }
                    } else {
                        outFile.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) {
                                return Result.failure(IllegalStateException("Unable to create directory: ${parent.absolutePath}"))
                            }
                        }
                        FileOutputStream(outFile).use { out ->
                            zip.copyTo(out)
                        }
                        if (entry.name.endsWith("/bin/sh") || entry.name.endsWith("bin/sh")) {
                            outFile.setExecutable(true, true)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return Result.success(Unit)
    }

    private fun extractTarLike(
        contentResolver: ContentResolver,
        uri: Uri,
        sourceName: String,
        targetDir: File,
    ): Result<Unit> {
        val input = contentResolver.openInputStream(uri)
            ?: return Result.failure(IllegalArgumentException("Unable to open selected rootfs file."))
        input.use { base ->
            val buffered = BufferedInputStream(base)
            val payload = openTarPayloadStream(buffered, sourceName)
            if (payload.isFailure) return Result.failure(payload.exceptionOrNull()!!)

            payload.getOrThrow().use { tarInput ->
                TarArchiveInputStream(tarInput).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val writeResult = writeTarEntry(entry, tar, targetDir)
                        if (writeResult.isFailure) return Result.failure(writeResult.exceptionOrNull()!!)
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
        return Result.success(Unit)
    }

    private fun openTarPayloadStream(input: InputStream, sourceName: String): Result<InputStream> {
        val lower = sourceName.lowercase()
        val explicitCompressor = when {
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> CompressorStreamFactory.XZ
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> CompressorStreamFactory.GZIP
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> CompressorStreamFactory.BZIP2
            lower.endsWith(".tar") -> null
            else -> null
        }

        if (explicitCompressor == null && lower.endsWith(".tar")) {
            return Result.success(input)
        }

        if (input.markSupported()) {
            input.mark(64 * 1024)
        }

        return try {
            if (explicitCompressor != null) {
                Result.success(CompressorStreamFactory(true).createCompressorInputStream(explicitCompressor, input))
            } else {
                Result.success(CompressorStreamFactory(true).createCompressorInputStream(input))
            }
        } catch (_: CompressorException) {
            if (explicitCompressor != null) {
                return Result.failure(
                    IllegalArgumentException("Failed to decompress rootfs archive: $sourceName"),
                )
            }
            if (input.markSupported()) {
                kotlin.runCatching { input.reset() }
                    .onFailure { return Result.failure(IllegalStateException("Failed to reset archive stream.")) }
            }
            Result.success(input)
        }
    }

    private fun writeTarEntry(entry: TarArchiveEntry, tar: TarArchiveInputStream, targetDir: File): Result<Unit> {
        val outFile = File(targetDir, entry.name).canonicalFile
        if (!outFile.path.startsWith(targetDir.canonicalPath + File.separator)) {
            return Result.failure(IllegalArgumentException("Invalid archive entry path."))
        }

        if (entry.isDirectory) {
            if (!outFile.exists() && !outFile.mkdirs()) {
                return Result.failure(IllegalStateException("Unable to create directory: ${outFile.absolutePath}"))
            }
            return Result.success(Unit)
        }

        if (entry.isSymbolicLink) {
            return try {
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) outFile.delete()
                Os.symlink(entry.linkName, outFile.absolutePath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Unable to create symlink ${outFile.absolutePath}", e))
            }
        }

        outFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                return Result.failure(IllegalStateException("Unable to create directory: ${parent.absolutePath}"))
            }
        }
        FileOutputStream(outFile).use { out ->
            tar.copyTo(out)
        }
        val mode = entry.mode
        outFile.setExecutable((mode and 0b001_001_001) != 0, false)
        outFile.setReadable((mode and 0b100_100_100) != 0, false)
        outFile.setWritable((mode and 0b010_010_010) != 0, false)
        return Result.success(Unit)
    }

    private fun resolveFilesystemPath(uri: Uri): String? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val parts = treeId.split(':', limit = 2)
        if (parts.isEmpty()) return null

        val volume = parts[0]
        val relative = parts.getOrElse(1) { "" }

        return when {
            volume.equals("primary", ignoreCase = true) -> {
                if (relative.isBlank()) {
                    "/storage/emulated/0"
                } else {
                    "/storage/emulated/0/$relative"
                }
            }
            volume.startsWith("raw", ignoreCase = true) -> treeId.removePrefix("raw:")
            else -> {
                if (relative.isBlank()) {
                    "/storage/$volume"
                } else {
                    "/storage/$volume/$relative"
                }
            }
        }
    }
}

package com.hypex.damnide.core.terminal.utils

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File

class RootfsManager(
    private val context: Context,
) {
    private val prootDir: File
        get() = File(context.filesDir, "bin")

    private val prootBinary: File
        get() = File(prootDir, "proot")

    fun validateRootfs(rootfsPath: String): Result<String> {
        val root = File(rootfsPath)
        if (!root.exists() || !root.isDirectory) {
            return Result.failure(IllegalArgumentException("Rootfs path does not exist: $rootfsPath"))
        }

        val hasBin = File(root, "bin").isDirectory
        val hasUsr = File(root, "usr").isDirectory
        val hasEtc = File(root, "etc").isDirectory
        val hasLib = File(root, "lib").isDirectory || File(root, "lib64").isDirectory
        if (!hasBin || !hasUsr || !hasEtc || !hasLib) {
            return Result.failure(
                IllegalArgumentException("Invalid rootfs: expected /bin, /usr, /etc, and /lib (or /lib64)."),
            )
        }

        val bash = File(root, "bin/bash")
        if (!bash.isFile) {
            return Result.failure(IllegalArgumentException("Invalid rootfs: missing /bin/bash"))
        }

        ensureTmpDirectory(root)
        return Result.success(root.canonicalPath)
    }

    fun ensureProotInstalled(): Result<String> {
        return kotlin.runCatching {
            if (!prootDir.exists() && !prootDir.mkdirs()) {
                error("Unable to create proot directory: ${prootDir.absolutePath}")
            }

            val sourceAsset = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> "proot/proot-aarch64"
                "x86_64" -> "proot/proot-x86_64"
                else -> error("Unsupported ABI for proot: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            }

            if (!prootBinary.exists()) {
                context.assets.open(sourceAsset).use { input ->
                    prootBinary.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            Os.chmod(prootBinary.absolutePath, 0b111_000_000)

            if (!prootBinary.canExecute()) {
                error("Unable to mark proot binary as executable")
            }

            prootBinary.absolutePath
        }
    }

    fun getProotCommand(rootfsPath: String): Result<Pair<String, List<String>>> {
        val validatedRootfs = validateRootfs(rootfsPath).getOrElse { return Result.failure(it) }
        val prootPath = ensureProotInstalled().getOrElse { return Result.failure(it) }
        val filesPath = context.filesDir.absolutePath

        return Result.success(
            prootPath to listOf(
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r", validatedRootfs,
                "-b", "/dev",
                "-b", "/dev/urandom",
                "-b", "/proc",
                "-b", "/proc/mounts:/etc/mtab",
                "-b", "/sys",
                "-b", "$filesPath:/host-rootfs",
                "-w", "/root",
                "/usr/bin/env", "-i",
                "HOME=/root",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TMPDIR=/tmp",
                "/bin/bash", "--login",
            ),
        )
    }

    private fun ensureTmpDirectory(rootfsDir: File) {
        val tmp = File(rootfsDir, "tmp")
        if (!tmp.exists()) {
            tmp.mkdirs()
        }
        tmp.setReadable(true, false)
        tmp.setWritable(true, false)
        tmp.setExecutable(true, false)
    }
}

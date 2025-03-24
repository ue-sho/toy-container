package com.toycontainer.fs

import java.io.File
import java.lang.ProcessBuilder
import java.lang.RuntimeException

/**
 * Manages filesystem operations for containers.
 * Creates a new mount namespace and sets up mounts for container isolation.
 */
class FilesystemManager(private val containerId: String) {
    private val containerRoot = "/tmp/toy-containers/$containerId"
    private val mountPoints = mapOf(
        "proc" to "proc",
        "sys" to "sysfs",
        "dev" to "devtmpfs",
        "tmp" to "tmpfs",
        "run" to "tmpfs"
    )

    /**
     * Initialize container filesystem.
     * Creates necessary directories and sets up mount points.
     */
    fun initialize() {
        try {
            // Create container root directory
            File(containerRoot).mkdirs()

            // Create basic directory structure
            listOf(
                "bin", "etc", "lib", "lib64", "usr", "sbin",
                "proc", "sys", "dev", "tmp", "run", "var", "home"
            ).forEach { dir ->
                File("$containerRoot/$dir").mkdirs()
            }

            // Create minimal filesystem structure
            setupMinimalFilesystem()

            println("Container filesystem initialized at: $containerRoot")
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize container filesystem: ${e.message}")
        }
    }

    /**
     * Set up minimal filesystem structure using bind mounts.
     * This is done in the parent process, before the namespace separation.
     */
    private fun setupMinimalFilesystem() {
        // Bind mount essential directories from host
        listOf(
            "bin", "etc", "lib", "lib64", "usr", "sbin", "var"
        ).forEach { dir ->
            bindMount("/$dir", "$containerRoot/$dir")
        }
    }

    /**
     * Perform a bind mount.
     * @param source Source path on host
     * @param target Target path in container
     */
    private fun bindMount(source: String, target: String) {
        if (!File(source).exists()) {
            println("Warning: Source directory does not exist: $source")
            return
        }

        try {
            val processBuilder = ProcessBuilder(
                "mount",
                "--bind",
                source,
                target
            )
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                println("Warning: Failed to mount $source to $target")
            }
        } catch (e: Exception) {
            println("Warning: Failed to mount $source to $target: ${e.message}")
        }
    }

    /**
     * Mount virtual filesystems in the container.
     * This is called after the container process is started with its own mount namespace.
     */
    fun mountVirtualFilesystems() {
        // Mount special filesystems
        mountPoints.forEach { (point, fsType) ->
            try {
                val processBuilder = ProcessBuilder(
                    "mount",
                    "-t",
                    fsType,
                    fsType,
                    "$containerRoot/$point"
                )
                val process = processBuilder.start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    println("Warning: Failed to mount $fsType to $point")
                }
            } catch (e: Exception) {
                println("Warning: Failed to mount $fsType to $point: ${e.message}")
            }
        }
    }

    /**
     * Get the path to the container's root filesystem.
     */
    fun getContainerRoot(): String = containerRoot

    /**
     * Clean up container filesystem.
     */
    fun cleanup() {
        try {
            // Unmount all filesystems in reverse order
            mountPoints.keys.reversed().forEach { point ->
                try {
                    val process = ProcessBuilder(
                        "umount",
                        "-f",
                        "$containerRoot/$point"
                    ).start()
                    process.waitFor()
                } catch (e: Exception) {
                    println("Warning: Failed to unmount $point: ${e.message}")
                }
            }

            // Unmount bind mounts in reverse order
            listOf(
                "var", "sbin", "usr", "lib64", "lib", "etc", "bin"
            ).forEach { dir ->
                try {
                    val process = ProcessBuilder(
                        "umount",
                        "-f",
                        "$containerRoot/$dir"
                    ).start()
                    process.waitFor()
                } catch (e: Exception) {
                    println("Warning: Failed to unmount $dir: ${e.message}")
                }
            }

            // Remove container root directory
            File(containerRoot).deleteRecursively()
        } catch (e: Exception) {
            println("Warning: Failed to cleanup container filesystem: ${e.message}")
        }
    }
}
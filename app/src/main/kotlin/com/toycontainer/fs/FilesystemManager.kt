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
            // Create container root directory with sudo to ensure proper permissions
            val mkdirProcess = ProcessBuilder(
                "sudo",
                "mkdir",
                "-p",
                containerRoot
            ).start()

            if (mkdirProcess.waitFor() != 0) {
                throw RuntimeException("Failed to create container root directory")
            }

            // Change ownership of the container root to current user
            val chownProcess = ProcessBuilder(
                "sudo",
                "chown",
                "${System.getProperty("user.name")}:",
                containerRoot
            ).start()

            if (chownProcess.waitFor() != 0) {
                println("Warning: Failed to change ownership of container root directory")
            }

            // Create basic directory structure
            listOf(
                "bin", "etc", "lib", "usr", "sbin",
                "proc", "sys", "dev", "tmp", "run", "var", "home"
            ).forEach { dir ->
                val dirPath = "$containerRoot/$dir"
                File(dirPath).mkdirs()

                // Set appropriate permissions on directories
                File(dirPath).setExecutable(true, false)
                File(dirPath).setReadable(true, false)
                File(dirPath).setWritable(true, false)
            }

            // Check if /lib64 exists on host, only create it if it exists
            if (File("/lib64").exists()) {
                File("$containerRoot/lib64").mkdirs()
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
        val essentialDirs = mutableListOf("bin", "etc", "lib", "usr", "sbin", "var")

        // Add lib64 only if it exists on the host
        if (File("/lib64").exists()) {
            essentialDirs.add("lib64")
        }

        essentialDirs.forEach { dir ->
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
            // Use sudo to perform the mount operation
            val processBuilder = ProcessBuilder(
                "sudo",
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
                    "sudo",
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
                        "sudo",
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
            val essentialDirs = mutableListOf("var", "sbin", "usr", "lib", "etc", "bin")

            // Add lib64 only if it exists on the host
            if (File("/lib64").exists()) {
                essentialDirs.add(0, "lib64")  // Add at the beginning to unmount first
            }

            essentialDirs.forEach { dir ->
                try {
                    val process = ProcessBuilder(
                        "sudo",
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
            val process = ProcessBuilder(
                "sudo",
                "rm",
                "-rf",
                containerRoot
            ).start()
            process.waitFor()
        } catch (e: Exception) {
            println("Warning: Failed to cleanup container filesystem: ${e.message}")
        }
    }
}
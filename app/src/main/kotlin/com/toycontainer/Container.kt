package com.toycontainer

import com.toycontainer.cgroups.CgroupManager
import com.toycontainer.fs.FilesystemManager
import com.toycontainer.image.ImageStorage
import java.io.File
import java.lang.ProcessBuilder
import java.lang.RuntimeException
import java.util.UUID

class Container {
    companion object {
        private val imageStorage = ImageStorage()

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.isEmpty()) {
                printHelp()
                return
            }

            when (args.firstOrNull()) {
                "run" -> run(args.drop(1).toTypedArray())
                "images" -> listImages()
                "build" -> buildImage(args.drop(1).toTypedArray())
                "rmi" -> removeImage(args.drop(1).toTypedArray())
                else -> printHelp()
            }
        }

        private fun printHelp() {
            println("""
                ToyContainer - A simple container runtime in Kotlin

                Usage:
                  run [options] <image> <command> [args...]  Run a command in a new container
                  images                                     List images
                  build <path> <name>[:<tag>]               Build an image from a directory
                  rmi <name>[:<tag>]                        Remove an image

                Example:
                  toy-container run alpine:latest /bin/sh
                  toy-container build /path/to/rootfs myimage:latest
            """.trimIndent())
        }

        private fun run(args: Array<String>) {
            if (args.isEmpty()) {
                println("Error: Image name is required")
                return
            }

            val imageRef = args.firstOrNull() ?: throw RuntimeException("Image is required")
            val commandArgs = args.drop(1).toTypedArray()

            if (commandArgs.isEmpty()) {
                println("Error: Command is required")
                return
            }

            val command = commandArgs.firstOrNull() ?: throw RuntimeException("Command is required")

            // Parse image name and tag
            val (imageName, imageTag) = parseImageNameTag(imageRef)

            // Check if image exists
            val image = imageStorage.getImage(imageName, imageTag)
            if (image == null) {
                println("Error: Image $imageName:$imageTag not found")
                return
            }

            println("Running ${commandArgs.joinToString(" ")} in $imageName:$imageTag")

            // Generate unique container ID
            val containerId = UUID.randomUUID().toString().substring(0, 8)
            println("Container ID: $containerId")

            // Initialize managers
            val cgroupManager = CgroupManager(containerId)
            val fsManager = FilesystemManager(containerId)

            try {
                // Initialize container resources
                cgroupManager.initialize()
                fsManager.initialize()

                // Set resource limits (example: 512MB memory, 50% CPU)
                cgroupManager.setMemoryLimit(512 * 1024 * 1024) // 512MB
                cgroupManager.setCpuQuota(50000) // 50% CPU

                // Extract image to container root
                val extractResult = imageStorage.extractImage(imageName, imageTag, fsManager.getContainerRoot())
                if (extractResult.status != com.toycontainer.image.ImageOperationStatus.SUCCESS) {
                    throw RuntimeException("Failed to extract image: ${extractResult.message}")
                }

                /*
                 * Note: Ideally, we should use direct syscalls with clone flags (CLONE_NEWUTS | CLONE_NEWPID | CLONE_NEWNS)
                 * However, Kotlin doesn't provide direct access to these syscall flags without using JNI.
                 * As a workaround, we use the unshare command which internally uses these syscalls.
                 * This is not the best practice for a container runtime, but it's a practical solution for this implementation.
                 */

                /*
                 * Create a shell script that will be executed inside the container
                 * This script will:
                 * 1. Mount /proc and other virtual filesystems
                 * 2. chroot to the new root
                 * 3. Execute the requested command
                 */
                val scriptPath = "${fsManager.getContainerRoot()}/container_init.sh"

                // Write the script with bash she-bang to ensure compatibility
                File(scriptPath).writeText("""
                    #!/bin/bash
                    echo "Container initializing..."

                    # Mount proc filesystem
                    mount -t proc proc /proc

                    # Mount other virtual filesystems
                    mount -t sysfs sysfs /sys
                    mount -t devtmpfs devtmpfs /dev
                    mount -t tmpfs tmpfs /tmp
                    mount -t tmpfs tmpfs /run

                    echo "Filesystems mounted"

                    # Make sure the command has execute permissions
                    chmod +x $command

                    # Execute the command
                    exec $command ${commandArgs.drop(1).joinToString(" ")}
                """.trimIndent())

                // Make the script executable
                val chmodProcess = ProcessBuilder(
                    "sudo",
                    "chmod",
                    "+x",
                    scriptPath
                ).start()

                if (chmodProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to make container init script executable")
                }

                // Setup unshare command with all required namespaces
                val processBuilder = ProcessBuilder(
                    "sudo",
                    "unshare",
                    "--pid",     // Separate PID namespace
                    "--uts",     // Separate UTS namespace
                    "--mount",   // Separate mount namespace
                    "--fork",    // Fork new process
                    "--mount-proc", // Mount /proc filesystem
                    "--kill-child", // Kill the child process when the parent dies
                    "chroot",    // Change root directory
                    fsManager.getContainerRoot(),
                    "/container_init.sh"
                )
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)

                /*
                 * Start the container process and wait for it to complete.
                 * The process runs in its own namespaces (PID, UTS, mount) as a child process.
                 * When the parent process dies, the child process will be killed automatically (--kill-child).
                 */
                try {
                    val process = processBuilder.start()
                    println("Container process started with PID: ${process.pid()}")

                    // Add process to cgroup
                    cgroupManager.addProcess(process.pid())

                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        throw RuntimeException("Container process exited with code $exitCode")
                    }
                } catch (e: Exception) {
                    throw RuntimeException("Failed to run container process", e)
                }
            } finally {
                // Cleanup resources
                cgroupManager.cleanup()
                fsManager.cleanup()
            }
        }

        private fun listImages() {
            val images = imageStorage.listImages()
            if (images.isEmpty()) {
                println("No images found")
                return
            }

            println("REPOSITORY          TAG          IMAGE ID          CREATED          SIZE")
            images.forEach { image ->
                val createdStr = image.createdAt.toString().substringBefore("T")
                val sizeStr = formatSize(image.size)
                println("${image.name.padEnd(20)}${image.tag.padEnd(14)}${image.id.substring(0, 12).padEnd(20)}${createdStr.padEnd(18)}$sizeStr")
            }
        }

        private fun buildImage(args: Array<String>) {
            if (args.size < 2) {
                println("Error: Path and name are required")
                return
            }

            val path = args[0]
            val imageRef = args[1]

            val (name, tag) = parseImageNameTag(imageRef)

            // Check if path exists
            val sourceDir = File(path)
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                println("Error: Path does not exist or is not a directory: $path")
                return
            }

            println("Building image $name:$tag from $path")
            val result = imageStorage.buildImage(path, name, tag)
            println(result.message)
        }

        private fun removeImage(args: Array<String>) {
            if (args.isEmpty()) {
                println("Error: Image name is required")
                return
            }

            val imageRef = args[0]
            val (name, tag) = parseImageNameTag(imageRef)

            println("Removing image $name:$tag")
            val result = imageStorage.deleteImage(name, tag)
            println(result.message)
        }

        /**
         * Parse image name and tag from an image reference.
         * @param imageRef Image reference (e.g. "name:tag")
         * @return Pair of name and tag
         */
        private fun parseImageNameTag(imageRef: String): Pair<String, String> {
            val parts = imageRef.split(":")
            return if (parts.size > 1) {
                Pair(parts[0], parts[1])
            } else {
                Pair(parts[0], "latest")
            }
        }

        /**
         * Format size in bytes to a human-readable string.
         * @param bytes Size in bytes
         * @return Formatted size string
         */
        private fun formatSize(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0

            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }

            return "%.2f %s".format(size, units[unitIndex])
        }
    }
}
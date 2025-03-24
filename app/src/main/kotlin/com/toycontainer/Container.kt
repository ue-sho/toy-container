package com.toycontainer

import com.toycontainer.cgroups.CgroupManager
import com.toycontainer.fs.FilesystemManager
import java.io.File
import java.lang.ProcessBuilder
import java.lang.RuntimeException
import java.util.UUID

class Container {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            when (args.firstOrNull()) {
                "run" -> run(args.drop(1).toTypedArray())
                else -> throw RuntimeException("help")
            }
        }

        private fun run(args: Array<String>) {
            println("Running ${args.joinToString(" ")}")

            val command = args.firstOrNull() ?: throw RuntimeException("Command is required")

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

                    # Execute the command
                    exec $command ${args.drop(1).joinToString(" ")}
                """.trimIndent())

                File(scriptPath).setExecutable(true)

                // Setup unshare command with all required namespaces
                val processBuilder = ProcessBuilder(
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
    }
}
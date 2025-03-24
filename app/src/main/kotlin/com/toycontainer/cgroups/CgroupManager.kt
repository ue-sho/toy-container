package com.toycontainer.cgroups

import java.io.File
import java.lang.ProcessBuilder
import java.lang.RuntimeException

/**
 * Manages cgroup resources for container processes.
 * This implementation uses cgroup v2 which is the current standard.
 * Note: Requires root privileges to manage cgroups.
 */
class CgroupManager(private val containerName: String) {
    // Use the root cgroup directory directly instead of creating a subdirectory
    private val cgroupBasePath = "/sys/fs/cgroup"
    private val containerPath = "$cgroupBasePath/$containerName"

    /**
     * Initialize cgroup for the container.
     * Creates necessary directories and sets up basic configurations.
     */
    fun initialize() {
        try {
            // Check if cgroup2 is mounted
            if (!File(cgroupBasePath).exists()) {
                throw RuntimeException("cgroup filesystem is not mounted at $cgroupBasePath")
            }

            println("Initializing cgroup at $containerPath")

            // Create container cgroup directory structure
            val containerDir = File(containerPath)
            if (containerDir.exists()) {
                println("Container cgroup directory already exists, removing it first")
                val removeProcess = ProcessBuilder(
                    "sudo",
                    "rmdir",
                    containerPath
                ).redirectError(ProcessBuilder.Redirect.INHERIT)
                 .start()

                val removeResult = removeProcess.waitFor()
                if (removeResult != 0) {
                    println("Warning: Failed to remove existing cgroup directory. Will attempt to continue...")
                }
            }

            // Use sudo to create the cgroup directory with proper permissions
            println("Creating cgroup directory...")
            val createDirProcess = ProcessBuilder(
                "sudo",
                "mkdir",
                "-p",
                containerPath
            ).redirectError(ProcessBuilder.Redirect.PIPE)
             .start()

            val createExitCode = createDirProcess.waitFor()
            val createErrorOutput = createDirProcess.errorStream.bufferedReader().readText()

            if (createExitCode != 0) {
                println("Warning: Failed to create cgroup directory at $containerPath")
                if (createErrorOutput.isNotEmpty()) {
                    println("Error details: $createErrorOutput")
                }
                return
            } else {
                println("Successfully created cgroup directory")
            }

            // Set proper permissions
            val chownProcess = ProcessBuilder(
                "sudo",
                "chown",
                "-R",
                "root:root",
                containerPath
            ).redirectError(ProcessBuilder.Redirect.INHERIT)
             .start()

            if (chownProcess.waitFor() != 0) {
                println("Warning: Failed to set ownership on cgroup directory")
            }

            val chmodProcess = ProcessBuilder(
                "sudo",
                "chmod",
                "-R",
                "755",
                containerPath
            ).redirectError(ProcessBuilder.Redirect.INHERIT)
             .start()

            if (chmodProcess.waitFor() != 0) {
                println("Warning: Failed to set permissions on cgroup directory")
            }

            // Enable memory and cpu controllers using sudo
            try {
                // Check available controllers
                val controllersFile = File("$cgroupBasePath/cgroup.controllers")
                if (controllersFile.exists()) {
                    val availableControllers = controllersFile.readText().trim().split(" ")
                    println("Available controllers: ${availableControllers.joinToString(", ")}")

                    // Only enable controllers that are available
                    val enableControllers = mutableListOf<String>()
                    if ("memory" in availableControllers) enableControllers.add("+memory")
                    if ("cpu" in availableControllers) enableControllers.add("+cpu")

                    if (enableControllers.isNotEmpty()) {
                        println("Enabling controllers: ${enableControllers.joinToString(" ")}")
                        val enableProcess = ProcessBuilder(
                            "sudo",
                            "sh",
                            "-c",
                            "echo '${enableControllers.joinToString(" ")}' > $cgroupBasePath/cgroup.subtree_control"
                        ).redirectError(ProcessBuilder.Redirect.PIPE)
                         .start()

                        val enableExitCode = enableProcess.waitFor()
                        val enableErrorOutput = enableProcess.errorStream.bufferedReader().readText()

                        if (enableExitCode != 0) {
                            println("Warning: Failed to enable controllers. Exit code: $enableExitCode")
                            if (enableErrorOutput.isNotEmpty()) {
                                println("Error details: $enableErrorOutput")
                            }
                        } else {
                            println("Successfully enabled controllers")
                        }
                    }
                } else {
                    println("Warning: cgroup.controllers file not found at $cgroupBasePath/cgroup.controllers")
                }

                // Verify cgroup.procs file exists
                val procsFile = File("$containerPath/cgroup.procs")
                if (procsFile.exists()) {
                    println("cgroup.procs file exists at $containerPath/cgroup.procs")
                } else {
                    println("Warning: cgroup.procs file does not exist at $containerPath/cgroup.procs")
                }
            } catch (e: Exception) {
                println("Warning: Failed to enable controllers: ${e.message}")
                e.printStackTrace()
                // Continue execution as some systems might have different controller configurations
            }
        } catch (e: Exception) {
            println("Warning: Failed to initialize cgroup: ${e.message}")
            e.printStackTrace()
            // Continue execution to allow the container to run without cgroup limitations
        }
    }

    /**
     * Set memory limit for the container.
     * @param limitInBytes Memory limit in bytes
     */
    fun setMemoryLimit(limitInBytes: Long) {
        try {
            val memoryFile = "$containerPath/memory.max"
            // Use sudo to write to the memory.max file
            val process = ProcessBuilder(
                "sudo",
                "sh",
                "-c",
                "echo $limitInBytes > $memoryFile"
            ).start()

            if (process.waitFor() != 0) {
                println("Warning: Failed to set memory limit")
            }
        } catch (e: Exception) {
            println("Warning: Failed to set memory limit: ${e.message}")
        }
    }

    /**
     * Set CPU quota for the container.
     * @param cpuQuota CPU quota in microseconds per CPU period
     * @param cpuPeriod CPU period in microseconds
     */
    fun setCpuQuota(cpuQuota: Long, cpuPeriod: Long = 100000) {
        try {
            val cpuFile = "$containerPath/cpu.max"
            // Use sudo to write to the cpu.max file
            val process = ProcessBuilder(
                "sudo",
                "sh",
                "-c",
                "echo '$cpuQuota $cpuPeriod' > $cpuFile"
            ).start()

            if (process.waitFor() != 0) {
                println("Warning: Failed to set CPU quota")
            }
        } catch (e: Exception) {
            println("Warning: Failed to set CPU quota: ${e.message}")
        }
    }

    /**
     * Add a process to the container's cgroup.
     * @param pid Process ID to add
     */
    fun addProcess(pid: Long) {
        try {
            val procsFile = "$containerPath/cgroup.procs"
            println("Attempting to add PID $pid to cgroup at $procsFile")

            // Check if file exists
            if (!File(procsFile).exists()) {
                println("Error: cgroup.procs file does not exist at $procsFile")
                return
            }

            // Use sudo to write to the cgroup.procs file
            val process = ProcessBuilder(
                "sudo",
                "sh",
                "-c",
                "echo $pid > $procsFile"
            ).redirectError(ProcessBuilder.Redirect.PIPE)
             .start()

            val exitCode = process.waitFor()
            val errorOutput = process.errorStream.bufferedReader().readText()

            if (exitCode != 0) {
                println("Warning: Failed to add process to cgroup. Exit code: $exitCode")
                if (errorOutput.isNotEmpty()) {
                    println("Error details: $errorOutput")
                }
            } else {
                println("Successfully added PID $pid to cgroup")
            }
        } catch (e: Exception) {
            println("Warning: Failed to add process to cgroup: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Clean up cgroup resources.
     */
    fun cleanup() {
        try {
            // Use sudo to remove the cgroup directory
            val process = ProcessBuilder(
                "sudo",
                "rmdir",
                containerPath
            ).start()

            if (process.waitFor() != 0) {
                println("Warning: Failed to cleanup cgroup")
            }
        } catch (e: Exception) {
            println("Warning: Failed to cleanup cgroup: ${e.message}")
        }
    }
}
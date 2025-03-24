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

            // Create container cgroup directory structure
            val containerDir = File(containerPath)

            // Use sudo to create the cgroup directory with proper permissions
            val createDirProcess = ProcessBuilder(
                "sudo",
                "mkdir",
                "-p",
                containerPath
            ).start()

            if (createDirProcess.waitFor() != 0) {
                println("Warning: Failed to create cgroup directory at $containerPath")
                return
            }

            // Enable memory and cpu controllers using sudo
            try {
                // Check available controllers
                val controllersFile = File("$cgroupBasePath/cgroup.controllers")
                if (controllersFile.exists()) {
                    val availableControllers = controllersFile.readText().trim().split(" ")

                    // Only enable controllers that are available
                    val enableControllers = mutableListOf<String>()
                    if ("memory" in availableControllers) enableControllers.add("+memory")
                    if ("cpu" in availableControllers) enableControllers.add("+cpu")

                    if (enableControllers.isNotEmpty()) {
                        val enableProcess = ProcessBuilder(
                            "sudo",
                            "sh",
                            "-c",
                            "echo '${enableControllers.joinToString(" ")}' > $cgroupBasePath/cgroup.subtree_control"
                        ).start()

                        if (enableProcess.waitFor() != 0) {
                            println("Warning: Failed to enable controllers")
                        }
                    }
                }
            } catch (e: Exception) {
                println("Warning: Failed to enable controllers: ${e.message}")
                // Continue execution as some systems might have different controller configurations
            }
        } catch (e: Exception) {
            println("Warning: Failed to initialize cgroup: ${e.message}")
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
            // Use sudo to write to the cgroup.procs file
            val process = ProcessBuilder(
                "sudo",
                "sh",
                "-c",
                "echo $pid > $procsFile"
            ).start()

            if (process.waitFor() != 0) {
                println("Warning: Failed to add process to cgroup")
            }
        } catch (e: Exception) {
            println("Warning: Failed to add process to cgroup: ${e.message}")
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
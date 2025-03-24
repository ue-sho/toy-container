package com.toycontainer.cgroups

import java.io.File
import java.lang.RuntimeException

/**
 * Manages cgroup resources for container processes.
 * This implementation uses cgroup v2 which is the current standard.
 * Note: Requires root privileges to manage cgroups.
 */
class CgroupManager(private val containerName: String) {
    private val cgroupBasePath = "/sys/fs/cgroup"
    private val containerPath = "$cgroupBasePath/toy-containers/$containerName"

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
            containerDir.mkdirs()

            if (!containerDir.exists()) {
                throw RuntimeException("Failed to create cgroup directory at $containerPath")
            }

            // Enable memory and cpu controllers
            try {
                File("$containerPath/cgroup.subtree_control").writeText("+memory +cpu")
            } catch (e: Exception) {
                println("Warning: Failed to enable controllers: ${e.message}")
                // Continue execution as some systems might have different controller configurations
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to initialize cgroup: ${e.message}")
        }
    }

    /**
     * Set memory limit for the container.
     * @param limitInBytes Memory limit in bytes
     */
    fun setMemoryLimit(limitInBytes: Long) {
        try {
            File("$containerPath/memory.max").writeText(limitInBytes.toString())
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
            File("$containerPath/cpu.max").writeText("$cpuQuota $cpuPeriod")
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
            File("$containerPath/cgroup.procs").writeText(pid.toString())
        } catch (e: Exception) {
            println("Warning: Failed to add process to cgroup: ${e.message}")
        }
    }

    /**
     * Clean up cgroup resources.
     */
    fun cleanup() {
        try {
            File(containerPath).deleteRecursively()
        } catch (e: Exception) {
            println("Warning: Failed to cleanup cgroup: ${e.message}")
        }
    }
}
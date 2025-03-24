package com.toycontainer

import java.lang.ProcessBuilder
import java.lang.RuntimeException

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

            /*
             * Note: Ideally, we should use direct syscalls with clone flags (CLONE_NEWUTS | CLONE_NEWPID | CLONE_NEWNS)
             * However, Kotlin doesn't provide direct access to these syscall flags without using JNI.
             * As a workaround, we use the unshare command which internally uses these syscalls.
             * This is not the best practice for a container runtime, but it's a practical solution for this implementation.
             */
            val processBuilder = ProcessBuilder(
                "unshare",
                "--pid",     // Separate PID namespace
                "--uts",     // Separate UTS namespace
                "--mount",   // Separate mount namespace
                "--fork",    // Fork new process
                "--mount-proc", // Mount /proc filesystem
                "--kill-child", // Kill the child process when the parent dies
                command,
                *args.drop(1).toTypedArray()
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

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw RuntimeException("Container process exited with code $exitCode")
                }
            } catch (e: Exception) {
                throw RuntimeException("Failed to run container process", e)
            }
        }
    }
}
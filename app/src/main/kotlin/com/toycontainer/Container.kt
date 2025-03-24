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
            val processBuilder = ProcessBuilder(command, *args.drop(1).toTypedArray())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)

            // Note: Kotlinでは直接syscallのフラグを設定することはできません
            // JNIやNative実装が必要になります

            try {
                val process = processBuilder.start()
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw RuntimeException("Process exited with code $exitCode")
                }
            } catch (e: Exception) {
                throw RuntimeException("Failed to run command", e)
            }
        }
    }
}
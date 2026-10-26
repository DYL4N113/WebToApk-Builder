package com.dyl4n.webtoapk.engine

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class NativeTools {
    fun runCommand(binary: File, args: List<String>, workDir: File): ProcessResult {
        require(binary.isFile) { "Executable not found: ${binary.absolutePath}" }
        require(workDir.isDirectory) { "Working directory not found: ${workDir.absolutePath}" }

        val process = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(workDir)
            .start()
        val outputReaders = Executors.newFixedThreadPool(2)
        val stdout: Future<String> = outputReaders.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderr: Future<String> = outputReaders.submit<String> {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        try {
            val exitCode = process.waitFor()
            return ProcessResult(exitCode, stdout.get(), stderr.get())
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw error
        } finally {
            outputReaders.shutdownNow()
        }
    }
}

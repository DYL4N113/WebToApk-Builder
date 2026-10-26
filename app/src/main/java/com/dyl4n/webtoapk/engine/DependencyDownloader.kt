package com.dyl4n.webtoapk.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL

class DependencyDownloader {
    suspend fun prepareDependencies(context: Context, onProgress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            suspend fun reportProgress(message: String) {
                withContext(Dispatchers.Main) {
                    onProgress(message)
                }
            }

            val androidJar = File(context.filesDir, "android.jar")
            if (!androidJar.isFile || androidJar.length() == 0L) {
                reportProgress("Mengunduh android.jar...")
                download(
                    url = "https://github.com/Sable/android-platforms/raw/master/android-34/android.jar",
                    destination = androidJar,
                )
            }

            reportProgress("Menyiapkan template DEX shell...")
            val templateDex = File(context.filesDir, "template.dex")
            val temporaryDex = File(context.filesDir, "template.dex.part")
            try {
                context.assets.open("template.dex").use { input ->
                    temporaryDex.outputStream().use { output -> input.copyTo(output) }
                }
                check(isDexFile(temporaryDex)) {
                    "Template DEX shell tidak valid atau kosong."
                }
                check(!templateDex.exists() || templateDex.delete()) {
                    "Tidak dapat mengganti template DEX lama: ${templateDex.absolutePath}"
                }
                check(temporaryDex.renameTo(templateDex)) {
                    "Tidak dapat menyimpan template DEX ke ${templateDex.absolutePath}"
                }
            } finally {
                if (temporaryDex.exists()) {
                    check(temporaryDex.delete()) {
                        "Tidak dapat menghapus file sementara ${temporaryDex.absolutePath}"
                    }
                }
            }

        }

    private fun download(url: String, destination: File) {
        val temporaryFile = File(destination.parentFile, "${destination.name}.part")
        try {
            URL(url).openStream().use { input ->
                temporaryFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            check(temporaryFile.length() > 0L) { "Unduhan kosong dari $url" }
            check(temporaryFile.renameTo(destination)) {
                "Tidak dapat menyimpan file unduhan ke ${destination.absolutePath}"
            }
        } finally {
            if (temporaryFile.exists()) {
                check(temporaryFile.delete()) {
                    "Tidak dapat menghapus file sementara ${temporaryFile.absolutePath}"
                }
            }
        }
    }

    private fun isDexFile(file: File): Boolean {
        if (!file.isFile || file.length() < 8L) return false
        val header = ByteArray(8)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val bytesRead = input.read(header, offset, header.size - offset)
                if (bytesRead <= 0) return false
                offset += bytesRead
            }
        }
        return header[0] == 'd'.code.toByte() &&
            header[1] == 'e'.code.toByte() &&
            header[2] == 'x'.code.toByte() &&
            header[3] == '\n'.code.toByte() &&
            header[7] == 0.toByte()
    }
}

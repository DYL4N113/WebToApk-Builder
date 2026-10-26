package com.dyl4n.webtoapk.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BuildConfig(
    val appName: String,
    val packageName: String,
    val sourceUri: Uri?,
    val sourceUrl: String?,
    val iconUri: Uri? = null,
)

class BuildEngine {
    suspend fun build(
        context: Context,
        config: BuildConfig,
        onProgress: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        suspend fun reportProgress(message: String) {
            withContext(Dispatchers.Main) {
                onProgress(message)
            }
        }

        suspend fun runNativeCommand(binary: File, args: List<String>, workDir: File): ProcessResult {
            val result = try {
                NativeTools().runCommand(binary, args, workDir)
            } catch (error: Exception) {
                reportProgress(error.message ?: "Gagal menjalankan ${binary.name}")
                throw error
            }

            if (result.exitCode != 0) {
                val errorMessage = result.stderr.ifBlank { result.stdout }
                    .ifBlank { "${binary.name} keluar dengan kode ${result.exitCode}" }
                reportProgress(errorMessage)
                throw IllegalStateException(errorMessage)
            }

            if (result.stdout.isNotBlank()) {
                reportProgress(result.stdout.trim())
            }
            return result
        }

        val aapt2 = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        val zipalign = File(context.applicationInfo.nativeLibraryDir, "libzipalign.so")

        reportProgress("Menyiapkan workspace...")
        val workspace = File(context.cacheDir, "build-${System.currentTimeMillis()}")
        val apkAssetsDirectory = File(workspace, "assets")
        val assetsDirectory = File(apkAssetsDirectory, "www")
        val resourcesDirectory = File(workspace, "res")
        val drawableDirectory = File(resourcesDirectory, "drawable")
        val compiledDirectory = File(workspace, "compiled")
        val outputDirectory = File(workspace, "output")
        val hasIcon = config.iconUri != null
        listOf(
            apkAssetsDirectory,
            compiledDirectory,
            outputDirectory,
        ).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Tidak dapat membuat folder: ${directory.absolutePath}"
            }
        }

        reportProgress("Copy assets...")
        val websiteUrl = config.sourceUrl?.trim()?.takeIf(String::isNotEmpty)
        if (websiteUrl != null) {
            File(apkAssetsDirectory, "url.txt").writeText(websiteUrl, Charsets.UTF_8)
        } else if (config.sourceUri != null) {
            check(assetsDirectory.isDirectory || assetsDirectory.mkdirs()) {
                "Tidak dapat membuat folder aset: ${assetsDirectory.absolutePath}"
            }
            val sourceDirectory = DocumentFile.fromTreeUri(context, config.sourceUri)
                ?: throw IllegalArgumentException(
                    "Folder sumber tidak dapat dibuka: ${config.sourceUri}",
                )
            copyDocumentFileToDir(context, sourceDirectory, assetsDirectory)
        } else {
            throw IllegalArgumentException("Pilih folder HTML atau isi URL website.")
        }

        reportProgress("Menyiapkan ikon aplikasi...")
        if (hasIcon) {
            check(drawableDirectory.isDirectory || drawableDirectory.mkdirs()) {
                "Tidak dapat membuat folder ikon: ${drawableDirectory.absolutePath}"
            }
            val iconFile = File(drawableDirectory, "icon.png")
            val iconStream = context.contentResolver.openInputStream(checkNotNull(config.iconUri))
                ?: throw IllegalStateException("Tidak dapat membaca gambar ikon.")
            val bitmap = iconStream.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("File yang dipilih bukan gambar yang valid.")
            try {
                FileOutputStream(iconFile).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Tidak dapat mengubah ikon ke format PNG."
                    }
                }
            } finally {
                bitmap.recycle()
            }
            check(iconFile.isFile && iconFile.length() > 0L) {
                "File ikon tidak ditemukan atau kosong: ${iconFile.absolutePath}"
            }
        }

        val manifestFile = File(workspace, "AndroidManifest.xml")
        manifestFile.writeText(
            TemplateLoader().generateManifest(
                context,
                config.appName,
                config.packageName,
                hasIcon = hasIcon,
            ),
            Charsets.UTF_8,
        )

        check(aapt2.canExecute()) { "Binary aapt2 tidak dapat dieksekusi: ${aapt2.absolutePath}" }

        reportProgress("Compile resources...")
        val resourcesZip = File(compiledDirectory, "resources.zip")
        if (hasIcon) {
            runNativeCommand(
                binary = aapt2,
                args = listOf(
                    "compile",
                    "--dir",
                    resourcesDirectory.absolutePath,
                    "-o",
                    resourcesZip.absolutePath,
                ),
                workDir = workspace,
            )
        }

        val androidJar = File(context.filesDir, "android.jar")
        check(androidJar.isFile) {
            "android.jar tidak ditemukan: ${androidJar.absolutePath}"
        }

        val expectedAssetPath = if (websiteUrl != null) {
            "assets/url.txt"
        } else {
            "assets/www/index.html"
        }
        check(File(workspace, expectedAssetPath).exists()) {
            if (websiteUrl != null) {
                "File url.txt tidak ditemukan di workspace."
            } else {
                "File index.html tidak ditemukan di folder yang dipilih!"
            }
        }

        reportProgress("Link resources...")
        val unsignedApk = File(outputDirectory, "unsigned.apk")
        val linkArgs = mutableListOf(
            "link",
            "-o",
            unsignedApk.absolutePath,
            "-I",
            androidJar.absolutePath,
            "--manifest",
            manifestFile.absolutePath,
            "-A",
            apkAssetsDirectory.absolutePath,
        )
        if (hasIcon) {
            linkArgs += listOf("-R", resourcesZip.absolutePath)
        }
        runNativeCommand(
            binary = aapt2,
            args = linkArgs,
            workDir = workspace,
        )

        check(unsignedApk.isFile) {
            "aapt2 link selesai tetapi APK tidak ditemukan: ${unsignedApk.absolutePath}"
        }
        val hasWebsiteAsset = ZipFile(unsignedApk).use { apk ->
            apk.getEntry(expectedAssetPath) != null
        }
        val assetLabel = if (websiteUrl != null) "url.txt" else "index.html"
        reportProgress("$assetLabel masuk ke APK: $hasWebsiteAsset")
        check(hasWebsiteAsset) {
            "aapt2 link tidak menyertakan $expectedAssetPath."
        }

        reportProgress("Menyiapkan classes.dex...")
        val templateDex = File(context.filesDir, "template.dex")
        check(templateDex.isFile && templateDex.length() > 0L) {
            "Template DEX tidak ditemukan"
        }
        validateDexFile(templateDex)

        val withDexApk = File(outputDirectory, "with_dex.apk")
        val alignedApk = File(outputDirectory, "aligned.apk")
        val finalSignedApk = File(outputDirectory, "final_signed.apk")
        listOf(withDexApk, alignedApk, finalSignedApk).forEach { outputFile ->
            check(!outputFile.exists() || outputFile.delete()) {
                "Tidak dapat menghapus file output lama: ${outputFile.absolutePath}"
            }
        }

        createApkWithDex(unsignedApk, templateDex, withDexApk)
        check(withDexApk.isFile && withDexApk.length() > 0L) {
            "APK dengan classes.dex tidak ditemukan atau kosong: ${withDexApk.absolutePath}"
        }

        reportProgress("Melakukan zipalign...")
        check(zipalign.canExecute()) {
            "Binary zipalign tidak dapat dieksekusi: ${zipalign.absolutePath}"
        }
        runNativeCommand(
            binary = zipalign,
            args = listOf("-f", "4", withDexApk.absolutePath, alignedApk.absolutePath),
            workDir = workspace,
        )
        check(alignedApk.isFile && alignedApk.length() > 0L) {
            "zipalign selesai tetapi APK tidak ditemukan atau kosong: ${alignedApk.absolutePath}"
        }

        reportProgress("Signing APK...")
        val keystore = SignerHelper.getOrCreateKeystore(context)
        try {
            SignerHelper.signApk(alignedApk, finalSignedApk, keystore)
            SignerHelper.verifyApk(finalSignedApk)
        } catch (error: Exception) {
            reportProgress(error.message ?: "Gagal menandatangani atau memverifikasi APK.")
            throw error
        }
        reportProgress("Signing dan verifikasi APK selesai...")

        val hasClassesDex = ZipFile(finalSignedApk).use { apk ->
            apk.getEntry("classes.dex")?.size?.let { it > 0L } == true
        }
        reportProgress("classes.dex ditemukan: $hasClassesDex")
        check(hasClassesDex) {
            "APK final tidak memiliki entry classes.dex yang valid."
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS,
        )
        val outputFolder = File(downloadsDir, "Web2APK")
        check(outputFolder.isDirectory || outputFolder.mkdirs()) {
            "Tidak dapat membuat folder output: ${outputFolder.absolutePath}"
        }

        val safeAppName = config.appName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        require(safeAppName.isNotBlank() && safeAppName != "." && safeAppName != "..") {
            "Nama aplikasi tidak valid untuk nama file."
        }
        val finalOutputFile = File(outputFolder, "$safeAppName.apk")
        finalSignedApk.copyTo(finalOutputFile, overwrite = true)
        check(finalSignedApk.delete()) {
            "APK sudah disalin tetapi file sementara tidak dapat dihapus: ${finalSignedApk.absolutePath}"
        }

        reportProgress("APK tersimpan di: ${finalOutputFile.absolutePath}")
        finalOutputFile
    }

    private fun createApkWithDex(unsignedApk: File, dexFile: File, outputApk: File) {
        require(unsignedApk.isFile) {
            "APK unsigned tidak ditemukan: ${unsignedApk.absolutePath}"
        }
        require(dexFile.isFile && dexFile.length() > 0L) {
            "File classes.dex tidak ditemukan atau kosong: ${dexFile.absolutePath}"
        }

        ZipInputStream(FileInputStream(unsignedApk)).use { input ->
            ZipOutputStream(FileOutputStream(outputApk)).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val entry = input.nextEntry ?: break
                    check(entry.name != "classes.dex") {
                        "unsigned.apk sudah memiliki entry classes.dex."
                    }
                    val copiedEntry = copyZipEntryMetadata(entry)
                    output.putNextEntry(copiedEntry)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                    }
                    output.closeEntry()
                    input.closeEntry()
                }

                output.putNextEntry(ZipEntry("classes.dex"))
                FileInputStream(dexFile).use { dexInput -> dexInput.copyTo(output) }
                output.closeEntry()
            }
        }
    }

    private fun copyZipEntryMetadata(entry: ZipEntry): ZipEntry {
        val copiedEntry = ZipEntry(entry.name).apply {
            time = entry.time
            entry.comment?.let { comment = it }
            entry.extra?.let { extra = it }
            method = entry.method
        }
        if (entry.method == ZipEntry.STORED) {
            check(entry.size >= 0L && entry.crc >= 0L) {
                "Metadata entry ZIP tidak lengkap untuk ${entry.name}."
            }
            copiedEntry.size = entry.size
            copiedEntry.compressedSize = entry.size
            copiedEntry.crc = entry.crc
        } else {
            check(entry.method == ZipEntry.DEFLATED) {
                "Metode kompresi ZIP tidak didukung untuk ${entry.name}: ${entry.method}"
            }
            if (entry.size >= 0L) {
                copiedEntry.size = entry.size
            }
        }
        return copiedEntry
    }

    private fun validateDexFile(dexFile: File) {
        val headerSize = 0x70
        check(dexFile.length() >= headerSize) {
            "Template DEX tidak valid: ukuran file lebih kecil dari header DEX."
        }

        val header = ByteArray(headerSize)
        FileInputStream(dexFile).use { input ->
            var offset = 0
            while (offset < header.size) {
                val bytesRead = input.read(header, offset, header.size - offset)
                check(bytesRead > 0) {
                    "Template DEX tidak valid: header tidak lengkap."
                }
                offset += bytesRead
            }
        }

        val isDexMagic = header[0] == 'd'.code.toByte() &&
            header[1] == 'e'.code.toByte() &&
            header[2] == 'x'.code.toByte() &&
            header[3] == '\n'.code.toByte() &&
            header[4] in '0'.code.toByte()..'9'.code.toByte() &&
            header[5] in '0'.code.toByte()..'9'.code.toByte() &&
            header[6] in '0'.code.toByte()..'9'.code.toByte() &&
            header[7] == 0.toByte()
        check(isDexMagic) {
            "Template DEX tidak valid: magic DEX tidak ditemukan."
        }

        val dexHeader = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        check(dexHeader.getInt(32).toLong() == dexFile.length()) {
            "Template DEX tidak valid: ukuran pada header tidak cocok dengan ukuran file."
        }
        check(dexHeader.getInt(36) == headerSize && dexHeader.getInt(40) == 0x12345678) {
            "Template DEX tidak valid: header atau endian tag tidak valid."
        }
    }

    private fun copyDocumentFileToDir(
        context: Context,
        source: DocumentFile,
        destination: File,
    ) {
        if (source.isDirectory) {
            check(destination.isDirectory || destination.mkdirs()) {
                "Tidak dapat membuat folder aset: ${destination.absolutePath}"
            }
            source.listFiles().forEach { child ->
                val childName = child.name
                    ?: throw IllegalArgumentException("Item dalam folder sumber tidak memiliki nama")
                require(childName != "." && childName != ".." && File(childName).name == childName) {
                    "Nama item sumber tidak valid: $childName"
                }
                copyDocumentFileToDir(context, child, File(destination, childName))
            }
            return
        }

        val input = context.contentResolver.openInputStream(source.uri)
            ?: throw IllegalStateException("Tidak dapat membaca file sumber: ${source.uri}")
        input.use { inputStream ->
            FileOutputStream(destination).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
    }
}

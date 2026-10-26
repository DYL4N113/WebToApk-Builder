package com.example.webtoapk

import android.os.Bundle
import android.net.Uri
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.dyl4n.webtoapk.engine.BuildConfig as EngineBuildConfig
import com.dyl4n.webtoapk.engine.BuildEngine
import com.dyl4n.webtoapk.engine.DependencyDownloader
import com.example.webtoapk.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedFolderUri: Uri? = null
    private var selectedIconUri: Uri? = null
    private var pendingInstallApk: File? = null

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                selectedFolderUri = uri
                val folderName = DocumentFile.fromTreeUri(this, uri)?.name
                    ?: uri.lastPathSegment
                    ?: uri.toString()
                binding.tvStatus.text = "Folder terpilih: $folderName"
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startBuild()
            } else {
                binding.tvStatus.text = "Izin penyimpanan diperlukan untuk menyimpan APK."
            }
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstallApk
            pendingInstallApk = null
            if (apk != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.canRequestPackageInstalls()
            ) {
                try {
                    installApk(apk)
                } catch (error: Exception) {
                    val message = error.localizedMessage ?: error.toString()
                    binding.tvStatus.text = "Gagal membuka installer: $message"
                    showToast("Gagal membuka installer: $message")
                }
            } else if (apk != null) {
                showToast("Izinkan instalasi aplikasi dari sumber ini untuk melanjutkan.")
            }
        }

    private val iconPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                selectedIconUri = uri
                binding.tvStatus.text = "Logo aplikasi terpilih."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBuild.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.tvStatus.text = "Menyiapkan dependency..."
        lifecycleScope.launch {
            try {
                DependencyDownloader().prepareDependencies(this@MainActivity) { message ->
                    binding.tvStatus.text = message
                }
                binding.tvStatus.text = "Dependency siap. Silakan siapkan project."
                binding.btnBuild.isEnabled = true
            } catch (error: Exception) {
                binding.tvStatus.text =
                    "Gagal menyiapkan dependency: ${error.localizedMessage ?: error}"
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
            }
        }

        binding.btnPickFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnPickIcon.setOnClickListener {
            iconPickerLauncher.launch(arrayOf("image/*"))
        }

        binding.btnBuild.setOnClickListener {
            when {
                binding.etAppName.text.isNullOrBlank() ->
                    showToast("Nama aplikasi wajib diisi!")

                binding.etPackageName.text.isNullOrBlank() ->
                    showToast("Package name wajib diisi!")

                selectedFolderUri == null && binding.etUrl.text.isNullOrBlank() ->
                    showToast("Pilih folder HTML atau isi URL!")

                else -> startBuild()
            }
        }
    }

    private fun startBuild() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val config = EngineBuildConfig(
            appName = binding.etAppName.text.toString().trim(),
            packageName = binding.etPackageName.text.toString().trim(),
            sourceUri = if (binding.etUrl.text.isNullOrBlank()) selectedFolderUri else null,
            sourceUrl = binding.etUrl.text?.toString()?.trim()?.takeIf(String::isNotEmpty),
            iconUri = selectedIconUri,
        )

        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnBuild.visibility = android.view.View.GONE
        binding.tvStatus.text = "Memulai build..."

        lifecycleScope.launch {
            try {
                val apkFile = BuildEngine().build(this@MainActivity, config) { message ->
                    binding.tvStatus.text = message
                }
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnBuild.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = "APK berhasil dibuat di: ${apkFile.absolutePath}"
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Build selesai")
                    .setMessage("APK berhasil dibuat di: ${apkFile.absolutePath}")
                    .setNeutralButton("Tutup", null)
                    .setPositiveButton("Install Sekarang") { _, _ ->
                        try {
                            installApk(apkFile)
                        } catch (error: Exception) {
                            val message = error.localizedMessage ?: error.toString()
                            binding.tvStatus.text = "Gagal membuka installer: $message"
                            showToast("Gagal membuka installer: $message")
                        }
                    }
                    .show()
            } catch (error: Exception) {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnBuild.visibility = android.view.View.VISIBLE
                binding.tvStatus.text = "Build gagal: ${error.localizedMessage ?: error}"
            }
        }
    }

    private fun installApk(file: File) {
        check(file.isFile) { "File APK tidak ditemukan: ${file.absolutePath}" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallApk = file
            installPermissionLauncher.launch(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }

        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

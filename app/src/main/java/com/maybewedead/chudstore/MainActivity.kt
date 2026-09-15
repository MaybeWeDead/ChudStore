package com.maybewedead.chudstore

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maybewedead.chudstore.ui.MainScreen
import com.maybewedead.chudstore.ui.MainViewModel
import com.maybewedead.chudstore.ui.ChudStoreTheme
import java.io.File
import java.io.FileOutputStream

/**
 * MainActivity — единственная Activity в приложении (single-activity,
 * Compose-driven — см. обсуждение в чате: только 2 состояния экрана,
 * Navigation-Compose был бы избыточен). Файловый пикер — стандартный
 * SAF (Storage Access Framework) ACTION_OPEN_DOCUMENT, потому что
 * пользователь сам кладёт APK куда угодно на устройство (см. решение
 * в чате — без предзаданной "папки со сборками", как и оригинальный
 * TrollStore на iOS).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { onApkPicked(it) }
    }

    /** APK, скопированный из SAF Uri во внутренний cache — см. onApkPicked(). */
    private var pendingApkFile: File? = null
    private var showDowngradeDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ChudStoreTheme {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    MainScreen(
                        viewModel = viewModel,
                        onInstallClick = { pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive")) },
                    )
                }

                if (showDowngradeDialog.value) {
                    DowngradeConfirmDialog(
                        onConfirm = { allowDowngrade ->
                            showDowngradeDialog.value = false
                            pendingApkFile?.let { file ->
                                viewModel.installApk(this, file, allowDowngrade)
                            }
                        },
                        onDismiss = { showDowngradeDialog.value = false },
                    )
                }
            }
        }
    }

    /**
     * SAF даёt только Uri (content://...), а PackageInstaller.Session.
     * openWrite() работает с обычным файлом/потоком — копируем содержимое
     * во внутренний cache-файл перед установкой. Это лишний I/O (APK
     * копируется дважды — SAF->cache, потом cache->PackageInstaller
     * session), но избегает возни с ContentResolver InputStream
     * напрямую в installer-слое, который тогда пришлось бы делать
     * Uri-осведомлённым — а он должен оставаться простым "File in,
     * result out" API, независимым от того, откуда файл взялся (кстати
     * то же самое понадобится, если позже появится сетевая загрузка
     * APK — installer-слой её тоже не заметит).
     */
    private fun onApkPicked(uri: Uri) {
        val cacheFile = File(cacheDir, "pending_install.apk")

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(this, "Could not read selected file", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read APK: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        pendingApkFile = cacheFile
        showDowngradeDialog.value = true
    }
}

@androidx.compose.runtime.Composable
private fun DowngradeConfirmDialog(
    onConfirm: (allowDowngrade: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val allowDowngrade = remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install APK") },
        text = {
            Column {
                Text("This will install silently without any system confirmation dialog.")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allowDowngrade.value,
                        onCheckedChange = { allowDowngrade.value = it },
                    )
                    Text("Allow downgrade (if an older version)")
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onConfirm(allowDowngrade.value) }) {
                Text("Install")
            }
        },
        dismissButton = {
            androidx.compose.material3.Button(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

package com.maybewedead.chudstore.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maybewedead.chudstore.data.InstalledApp
import com.maybewedead.chudstore.data.InstalledAppsRepository
import com.maybewedead.chudstore.installer.PackageInstallerService
import com.maybewedead.chudstore.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MainViewModel — единственный держатель состояния экрана. Все
 * привилегированные операции (install/uninstall) уходят на
 * Dispatchers.IO, потому что PackageInstallerService — БЛОКИРУЮЩИЙ
 * (см. waitForInstallResult/waitForUninstallResult с polling внутри) —
 * вызов на главном потоке подвесил бы UI на до 30-60 секунд таймаута.
 */
class MainViewModel : ViewModel() {

    var shizukuAvailable by mutableStateOf(false)
        private set
    var shizukuPermissionGranted by mutableStateOf(false)
        private set

    var installedApps by mutableStateOf<List<InstalledApp>>(emptyList())
        private set

    var isBusy by mutableStateOf(false)
        private set

    var lastActionMessage by mutableStateOf<String?>(null)
        private set

    fun refreshShizukuStatus() {
        shizukuAvailable = ShizukuBridge.isAvailable()
        shizukuPermissionGranted = ShizukuBridge.hasPermission()
    }

    fun requestShizukuPermission() {
        ShizukuBridge.requestPermission { granted ->
            shizukuPermissionGranted = granted
        }
    }

    fun refreshInstalledApps(context: Context) {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                InstalledAppsRepository.getInstalledApps(context.applicationContext)
            }
            installedApps = apps
        }
    }

    fun installApk(context: Context, apkFile: File, allowDowngrade: Boolean) {
        viewModelScope.launch {
            isBusy = true
            lastActionMessage = null

            val result = withContext(Dispatchers.IO) {
                PackageInstallerService.installSilently(apkFile, allowDowngrade)
            }

            isBusy = false
            lastActionMessage = when (result) {
                is PackageInstallerService.InstallResult.Success -> "Installed successfully"
                is PackageInstallerService.InstallResult.Failure -> "Install failed: ${result.message}"
            }

            if (result is PackageInstallerService.InstallResult.Success) {
                refreshInstalledApps(context)
            }
        }
    }

    fun uninstallApp(context: Context, packageName: String) {
        viewModelScope.launch {
            isBusy = true
            lastActionMessage = null

            val result = withContext(Dispatchers.IO) {
                PackageInstallerService.uninstallSilently(packageName)
            }

            isBusy = false
            lastActionMessage = when (result) {
                is PackageInstallerService.InstallResult.Success -> "Uninstalled successfully"
                is PackageInstallerService.InstallResult.Failure -> "Uninstall failed: ${result.message}"
            }

            if (result is PackageInstallerService.InstallResult.Success) {
                refreshInstalledApps(context)
            }
        }
    }

    fun clearActionMessage() {
        lastActionMessage = null
    }
}

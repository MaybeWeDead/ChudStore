package com.maybewedead.chudstore.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * InstalledAppsRepository — список установленных приложений через
 * ОБЫЧНЫЙ публичный PackageManager API, никакого reflection/Shizuku
 * здесь не нужно: чтение списка пакетов и их метаданных (иконка, имя,
 * версия) не требует привилегий выше обычного приложения — привилегии
 * нужны только для МУТИРУЮЩИХ операций (install/uninstall/grant), см.
 * installer/PackageInstallerService.kt.
 *
 * QUERY_ALL_PACKAGES permission в манифесте — без него на Android 11+
 * getInstalledPackages() вернул бы урезанный список (только пакеты, с
 * которыми мы явно взаимодействовали) из-за package visibility filtering.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val icon: Drawable,
    val isSystemApp: Boolean,
)

object InstalledAppsRepository {

    /**
     * includeSystemApps=false по умолчанию — список системных пакетов
     * (com.android.systemui, com.android.providers.* и т.д.) обычно
     * бесполезен пользователю стора и просто засоряет список сотнями
     * записей, которые никто не будет ни удалять, ни обновлять через
     * это приложение (см. обсуждение UX в чате).
     */
    fun getInstalledApps(context: Context, includeSystemApps: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager

        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

        return packages.mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            if (isSystem && !includeSystemApps) return@mapNotNull null

            InstalledApp(
                packageName = pkg.packageName,
                label = appInfo.loadLabel(pm).toString(),
                versionName = pkg.versionName,
                versionCode = getVersionCodeCompat(pkg),
                icon = appInfo.loadIcon(pm),
                isSystemApp = isSystem,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * PackageInfo.versionCode (Int) deprecated с API 28 в пользу
     * PackageInfo.longVersionCode (Long) — оборачиваем разницу здесь,
     * чтобы остальной код всегда работал с Long и не думал про API level.
     */
    @Suppress("DEPRECATION")
    private fun getVersionCodeCompat(pkg: android.content.pm.PackageInfo): Long {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            pkg.versionCode.toLong()
        }
    }

    fun findApp(context: Context, packageName: String): InstalledApp? {
        return try {
            val pm = context.packageManager
            val pkg = pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            val appInfo = pkg.applicationInfo ?: return null

            InstalledApp(
                packageName = pkg.packageName,
                label = appInfo.loadLabel(pm).toString(),
                versionName = pkg.versionName,
                versionCode = getVersionCodeCompat(pkg),
                icon = appInfo.loadIcon(pm),
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}

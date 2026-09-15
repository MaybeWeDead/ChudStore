package com.maybewedead.chudstore.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * ShizukuBridge — единственная точка входа ко всему, что требует
 * shell UID (2000) привилегий: тихая установка/удаление APK, grant
 * runtime permissions. Всё остальное приложение НЕ должно напрямую
 * трогать rikka.shizuku.* классы или reflection — только через этот
 * объект, чтобы вся хрупкая, версия-зависимая логика была в одном
 * месте (см. обсуждение в чате: API системных сервисов отличается
 * между Android-версиями, SystemServiceHelper.getTransactionCode может
 * промахнуться на некоторых API — используем ShizukuBinderWrapper +
 * AIDL Stub.asInterface, задокументированный надёжный путь, а не
 * ручной transactRemote).
 *
 * Жизненный цикл:
 *   1. init() — регистрирует permission result listener, ОБЯЗАН
 *      вызываться из Application.onCreate() ДО первого обращения к
 *      Shizuku где-либо (сам класс Shizuku использует статические
 *      слушатели, разделяемые на весь процесс).
 *   2. isAvailable() — проверяет, жив ли биндер вообще (Shizuku app
 *      установлен и запущен, либо Sui-backend активен).
 *   3. hasPermission() — проверяет, дали ли НАМ разрешение
 *      использовать Shizuku (отдельно от "доступен ли Shizuku вообще" —
 *      пользователь может отклонить permission dialog).
 *   4. requestPermission() — запускает системный permission dialog
 *      Shizuku (НЕ обычный Android permission — это диалог самого
 *      Shizuku-приложения, "разрешить TrollStore использовать Shizuku").
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val PERMISSION_REQUEST_CODE = 0x5A11

    private var permissionGrantedListener: ((Boolean) -> Unit)? = null

    /**
     * Регистрирует слушателя результата permission-запроса.
     * Должен вызываться один раз при старте процесса (Application.onCreate).
     */
    fun init() {
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@addRequestPermissionResultListener

            val granted = grantResult == PackageManager.PERMISSION_GRANTED

            Log.i(TAG, "Shizuku permission result: granted=$granted")

            permissionGrantedListener?.invoke(granted)
        }
    }

    /**
     * true, если сам Shizuku-биндер жив.
     */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /**
     * Проверяет, выдано ли приложению разрешение Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isAvailable()) return false

        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Запрашивает permission через системный UI Shizuku.
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            onResult(true)
            return
        }

        if (!isAvailable()) {
            onResult(false)
            return
        }

        permissionGrantedListener = { granted ->
            permissionGrantedListener = null
            onResult(granted)
        }

        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
    }

    /**
     * Возвращает Binder к системному сервису, обёрнутый
     * в ShizukuBinderWrapper.
     */
    private fun getSystemServiceBinder(serviceName: String): IBinder {
        val raw = SystemServiceHelper.getSystemService(serviceName)
            ?: error(
                "System service '$serviceName' not found " +
                    "(returned null binder)"
            )

        return ShizukuBinderWrapper(raw)
    }

    /**
     * Достаёт AIDL Stub-класс через reflection.
     */
    private fun stubAsInterfaceMethod(
        stubClassName: String
    ): Pair<Class<*>, Method> {
        val stubClass = Class.forName(stubClassName)

        val method = HiddenApiBypass
            .getDeclaredMethods(stubClass)
            .filterIsInstance<Method>()
            .first { it.name == "asInterface" }

        return stubClass to method
    }

    /**
     * Получает AIDL-интерфейс системного сервиса через Shizuku.
     *
     * Пример:
     *
     * getHiddenSystemInterface(
     *     "package",
     *     "android.content.pm.IPackageManager\$Stub"
     * )
     */
    fun getHiddenSystemInterface(
        serviceName: String,
        stubClassName: String
    ): Any {
        check(hasPermission()) {
            "Shizuku permission not granted"
        }

        val binder = getSystemServiceBinder(serviceName)

        val (_, asInterfaceMethod) =
            stubAsInterfaceMethod(stubClassName)

        return asInterfaceMethod.invoke(null, binder)
            ?: error(
                "asInterface($stubClassName) returned null"
            )
    }
}

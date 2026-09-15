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
    private const val PERMISSION_REQUEST_CODE = 0x5A11 // произвольный, но стабильный код для requestCode/onRequestPermissionResult

    private var permissionGrantedListener: ((Boolean) -> Unit)? = null

    /**
     * Регистрирует слушателя результата permission-запроса. Должен
     * вызываться один раз при старте процесса (Application.onCreate).
     * Shizuku API использует статические callback-слушатели на уровне
     * процесса, а не на уровне конкретной Activity — поэтому регистрация
     * тут, а не в каждой Activity, которая могла бы запрашивать permission.
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
     * true, если сам Shizuku-биндер жив (Shizuku app запущен, ИЛИ
     * Sui-backend активен на рутованном устройстве) — НЕ означает, что
     * permission уже дан, только что "есть с кем разговаривать".
     */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        // pingBinder() бросает исключение (не возвращает false), если
        // биндер вообще не был получен ни разу за жизнь процесса —
        // это отличается от "биндер был, но умер", тот случай API
        // покрывает нормально через false. Ловим широкий Throwable
        // (не конкретный тип), потому что конкретное исключение тут
        // internal API деталь, которая может смениться между версиями
        // Shizuku-API — нам важен только факт "недоступен", не причина.
        false
    }

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Запрашивает permission через системный UI Shizuku. Результат
     * приходит асинхронно в listener, зарегистрированный через init() —
     * колбэк onResult вызывается ОДИН раз при получении ответа (не
     * оставляем listener висеть навсегда, чтобы не накапливать их при
     * повторных запросах с разных экранов).
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
     * Возвращает Binder к системному сервису по его ЛОКАЛЬНОМУ имени
     * (те же имена, что видны в `adb shell service list` — "package",
     * "package_installer", "activity" и т.д.), обёрнутый в
     * ShizukuBinderWrapper — вызовы через этот binder идут с shell UID
     * идентичностью, а не UID нашего приложения.
     *
     * Бросает IllegalStateException, если Shizuku недоступен/нет
     * permission — вызывающий код (installer/*, apps/*) обязан
     * проверить hasPermission() ДО обращения сюда, эта функция
     * намеренно не проверяет сама, чтобы не плодить двойные проверки
     * на каждый вызов при батч-операциях (например, установка сразу
     * нескольких APK подряд).
     */
    private fun getSystemServiceBinder(serviceName: String): IBinder {
        val raw = SystemServiceHelper.getSystemService(serviceName)
            ?: error("System service '$serviceName' not found (returned null binder)")
        return ShizukuBinderWrapper(raw)
    }

    /**
     * Достаёт AIDL Stub-класс через reflection вместо прямого import —
     * ОБЯЗАТЕЛЬНО для классов вроде android.content.pm.IPackageManager/
     * IPackageInstaller, которые находятся в hidden API greylist на
     * Android 9+ (см. обсуждение в чате). Прямой `import
     * android.content.pm.IPackageManager` компилируется, но рантайм
     * бросает NoSuchMethodError/ClassNotFoundException на части
     * устройств из-за политики non-SDK interface enforcement — этого
     * не происходит при доступе через Class.forName + HiddenApiBypass,
     * потому что тот явно снимает эту политику для нашего процесса.
     */
    private fun stubAsInterfaceMethod(stubClassName: String): Pair<Class<*>, Method> {
        val stubClass = Class.forName(stubClassName)
        val method = HiddenApiBypass.getDeclaredMethods(stubClass)
            .filterIsInstance<Method>()
            .first { it.name == "asInterface" }
        return stubClass to method
    }

    /**
     * Универсальный хелпер: получить AIDL-интерфейс системного сервиса
     * как shell UID. interfaceStubClassName — полное имя класса вида
     * "android.content.pm.IPackageManager$Stub", ЭТО НЕ опечатка на
     * "$Stub" — Stub.asInterface(IBinder) это стандартный AIDL-сгенерированный
     * статический метод, превращающий сырой Binder в типизированный
     * прокси-интерфейс.
     *
     * Возвращает Any (не конкретный интерфейс) — вызывающий код
     * (installer/apps слои) сам приводит результат через ещё один
     * reflection-вызов метода интерфейса, потому что типизированный
     * import самого IPackageManager/IPackageInstaller интерфейса ТОЖЕ
     * hidden API и его нельзя импортировать напрямую в исходники,
     * которые должны компилироваться без ошибок non-SDK usage.
     */
    fun getHiddenSystemInterface(serviceName: String, stubClassName: String): Any {
        check(hasPermission()) { "Shizuku permission not granted" }

        val binder = getSystemServiceBinder(serviceName)
        val (_, asInterfaceMethod) = stubAsInterfaceMethod(stubClassName)
        return asInterfaceMethod.invoke(null, binder)
            ?: error("asInterface($stubClassName) returned null")
    }
}

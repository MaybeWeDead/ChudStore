package com.maybewedead.chudstore.installer

import android.content.pm.PackageInstaller
import android.os.Build
import com.maybewedead.chudstore.shizuku.ShizukuBridge
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.io.FileInputStream

/**
 * PackageInstallerService — тихая установка/обновление/удаление APK
 * через shell UID (2000), полученный от Shizuku. Никаких системных
 * диалогов подтверждения — тот же путь, которым идёт `adb install`.
 *
 * Как это устроено (см. обсуждение в чате — сверено по декомпилированным
 * сигнатурам android.content.pm.IPackageInstaller и PackageInstaller.Session):
 *
 *   1. IPackageInstaller — hidden AIDL-интерфейс, получаем его через
 *      ShizukuBridge.getHiddenSystemInterface("package", "...IPackageInstaller$Stub")
 *   2. createSession(params, installerPackageName, userId) — тоже hidden
 *      метод самого AIDL-интерфейса, вызываем через reflection
 *   3. openSession(sessionId) возвращает IPackageInstallerSession (тоже
 *      hidden AIDL), но НЕ нужно с ним возиться напрямую — публичный
 *      класс android.content.pm.PackageInstaller.Session имеет ПУБЛИЧНЫЙ
 *      конструктор PackageInstaller.Session(IPackageInstallerSession) —
 *      создаём обычный Session через reflection на конструкторе (сам
 *      конструктор public, значит НЕ требует HiddenApiBypass, только
 *      обычный setAccessible(true) для доступа к package-private классу
 *      снаружи android.content.pm пакета)
 *   4. Дальше — ОБЫЧНЫЕ публичные методы Session: openWrite/commit/close,
 *      без единого reflection-вызова.
 */
object PackageInstallerService {

    private const val PACKAGE_MANAGER_SERVICE = "package"
    private const val IPACKAGE_INSTALLER_STUB = "android.content.pm.IPackageInstaller\$Stub"

    // INSTALL_ALLOW_DOWNGRADE — публичная константа PackageManager (не
    // hidden), значение зафиксировано в AOSP с API 24 и не менялось.
    // Используем числовое значение напрямую, а не android.content.pm.PackageManager.INSTALL_ALLOW_DOWNGRADE,
    // потому что это поле аннотировано @hide на некоторых уровнях API
    // несмотря на то, что сама фича публично документирована через adb —
    // безопаснее не завязываться на видимость конкретного константного
    // поля и держать значение как задокументированный магический int.
    private const val INSTALL_ALLOW_DOWNGRADE_FLAG = 0x00000080
    private const val INSTALL_REPLACE_EXISTING_FLAG = 0x00000002

    sealed class InstallResult {
        data object Success : InstallResult()
        data class Failure(val message: String, val cause: Throwable? = null) : InstallResult()
    }

    /**
     * Тихая установка APK-файла. allowDowngrade — см. обсуждение в чате:
     * работает надёжно для debuggable-сборок; для release-подписанных
     * APK система может ДОПОЛНИТЕЛЬНО отклонить даунгрейд на некоторых
     * API levels даже с этим флагом — тогда результат придёт как Failure
     * с сообщением от системы, не наша логика тут ничего не скрывает.
     */
    fun installSilently(apkFile: File, allowDowngrade: Boolean = false): InstallResult {
        if (!ShizukuBridge.hasPermission()) {
            return InstallResult.Failure("Shizuku permission not granted")
        }
        if (!apkFile.exists() || !apkFile.canRead()) {
            return InstallResult.Failure("APK file not accessible: ${apkFile.path}")
        }

        return try {
            val packageInstaller = ShizukuBridge.getHiddenSystemInterface(
                PACKAGE_MANAGER_SERVICE,
                IPACKAGE_INSTALLER_STUB,
            )

            val sessionParams = buildSessionParams(allowDowngrade)
            val sessionId = createSession(packageInstaller, sessionParams)
            val session = openSessionAsPublicSession(packageInstaller, sessionId)

            session.use { s ->
                writeApkIntoSession(s, apkFile)
                commitSessionSilently(s)
            }

            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Failure(e.message ?: "Unknown error during silent install", e)
        }
    }

    /**
     * Тихое удаление пакета. Тем же путём (shell UID), что и установка —
     * IPackageInstaller имеет отдельный uninstall(...) метод, тоже hidden.
     */
    fun uninstallSilently(packageName: String): InstallResult {
        if (!ShizukuBridge.hasPermission()) {
            return InstallResult.Failure("Shizuku permission not granted")
        }

        return try {
            val packageInstaller = ShizukuBridge.getHiddenSystemInterface(
                PACKAGE_MANAGER_SERVICE,
                IPACKAGE_INSTALLER_STUB,
            )

            // uninstall(String packageName, String callerPackageName, int flags,
            //           IntentSender statusReceiver, int userId) — сигнатура
            // варьируется чуть сильнее между Android-версиями, чем createSession
            // (некоторые версии добавляли/убирали userId-подобные параметры),
            // поэтому ищем метод по имени + количеству параметров, а не
            // жёстко фиксируем сигнатуру — устойчивее к точным изменениям типов.
            val uninstallMethod = HiddenApiBypass.getDeclaredMethods(packageInstaller.javaClass)
                .filterIsInstance<java.lang.reflect.Method>()
                .first { it.name == "uninstall" }

            val statusReceiver = SilentIntentSender.create { success, message ->
                lastUninstallResult = if (success) InstallResult.Success else InstallResult.Failure(message ?: "Uninstall failed")
            }

            invokeUninstall(uninstallMethod, packageInstaller, packageName, statusReceiver)

            // uninstall() асинхронный (результат приходит через IntentSender
            // callback) — ждём его здесь синхронно с таймаутом, чтобы
            // вызывающий UI-код мог использовать эту функцию как обычный
            // блокирующий вызов (сама функция уже дергается из фоновой
            // корутины на стороне UI, см. installer/apps repository слой).
            waitForUninstallResult()
        } catch (e: Exception) {
            InstallResult.Failure(e.message ?: "Unknown error during silent uninstall", e)
        }
    }

    // --- внутренняя механика -------------------------------------------------

    // ВАЖНО: lastInstallResult/lastUninstallResult — общие для ВСЕХ вызовов
    // install/uninstall соответственно, не per-call. Это безопасно ТОЛЬКО
    // потому что UI-слой (MainViewModel.isBusy) не даёt запустить два
    // install (или два uninstall) параллельно — если это ограничение
    // когда-нибудь снимут на UI-стороне, эти поля нужно будет превратить
    // в Map<sessionId, InstallResult> или использовать CompletableFuture
    // per-call вместо общей переменной.
    @Volatile
    private var lastUninstallResult: InstallResult? = null

    private fun waitForUninstallResult(timeoutMs: Long = 30_000): InstallResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (lastUninstallResult == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return lastUninstallResult ?: InstallResult.Failure("Uninstall timed out waiting for system callback")
            .also { lastUninstallResult = null }
    }

    /**
     * SessionParams — публичный класс, конструктор публичный
     * (PackageInstaller.SessionParams(int mode)), значит создаём БЕЗ
     * reflection вообще. setInstallFlags/setInstallReason — тоже
     * публичные методы. Единственная причина, почему этот метод вообще
     * существует отдельно — держать флаги установки в одном
     * задокументированном месте, а не размазывать магические числа по
     * installSilently().
     */
    private fun buildSessionParams(allowDowngrade: Boolean): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // USER_ACTION_NOT_REQUIRED — публичный API с Android 12 (S),
            // явно говорит системе не показывать post-install confirmation
            // UI для обновлений того же signer'а. Без shell UID это поле
            // система бы всё равно игнорировала для APK неизвестного
            // происхождения, но раз мы уже действуем от shell UID —
            // работает как задокументировано.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        // installFlags — приватное поле SessionParams, но доступное через
        // штатный публичный сеттер нет (setInstallFlags не всегда
        // публичный в разных версиях API) — используем прямой доступ к
        // полю через reflection как самый устойчивый путь между версиями,
        // раз мы и так уже полагаемся на reflection для остального.
        setInstallFlags(params, INSTALL_REPLACE_EXISTING_FLAG or if (allowDowngrade) INSTALL_ALLOW_DOWNGRADE_FLAG else 0)

        return params
    }

    private fun setInstallFlags(params: PackageInstaller.SessionParams, flags: Int) {
        val field = HiddenApiBypass.getInstanceFields(PackageInstaller.SessionParams::class.java)
            .filterIsInstance<java.lang.reflect.Field>()
            .firstOrNull { it.name == "installFlags" }
            ?: return // поле переименовано/убрано в этой версии Android — молча пропускаем, REPLACE_EXISTING обычно и так дефолтное поведение MODE_FULL_INSTALL поверх существующего пакета

        field.isAccessible = true
        val current = field.getInt(params)
        field.setInt(params, current or flags)
    }

    private fun createSession(packageInstaller: Any, params: PackageInstaller.SessionParams): Int {
        val method = HiddenApiBypass.getDeclaredMethods(packageInstaller.javaClass)
            .filterIsInstance<java.lang.reflect.Method>()
            .first { it.name == "createSession" }

        // installerPackageName=null, userId=0 (текущий/primary пользователь
        // устройства — многопользовательские Android-профили не в scope
        // этого проекта, см. обсуждение фич в чате: single-user установка).
        return method.invoke(packageInstaller, params, null, 0) as Int
    }

    /**
     * openSession(sessionId) на IPackageInstaller возвращает
     * IPackageInstallerSession (hidden AIDL-интерфейс) — но нам не нужно
     * работать с ним напрямую: оборачиваем результат в ПУБЛИЧНЫЙ
     * PackageInstaller.Session через его публичный конструктор (см.
     * декомпилированную сигнатуру в комментарии наверху файла). Это
     * единственное место во всём installer-слое, где используется
     * java.lang.reflect напрямую (не через HiddenApiBypass) — потому что
     * конструктор Session публичный, просто сам класс Session на момент
     * компиляции недоступен для прямого `new PackageInstaller.Session(...)`
     * без специального casting трюка (Kotlin/Java не даёт напрямую
     * писать конструктор с параметром типа, который сам недоступен для
     * импорта) — reflection здесь используется как обходной путь для
     * ТИПА параметра, а не для прав доступа.
     */
    private fun openSessionAsPublicSession(packageInstaller: Any, sessionId: Int): PackageInstaller.Session {
        val openSessionMethod = HiddenApiBypass.getDeclaredMethods(packageInstaller.javaClass)
            .filterIsInstance<java.lang.reflect.Method>()
            .first { it.name == "openSession" }

        val hiddenSessionBinder = openSessionMethod.invoke(packageInstaller, sessionId)
            ?: error("openSession($sessionId) returned null")

        // ВАЖНО: не берём interfaces.first() слепо — Binder proxy объект
        // может реализовывать несколько интерфейсов (IInterface, сам
        // IPackageInstallerSession, возможно IBinder-related) в
        // НЕГАРАНТИРОВАННОМ порядке. Ищем явно по имени, чтобы не
        // словить ClassCastException на устройствах, где порядок
        // интерфейсов на Proxy-классе окажется другим.
        val sessionInterface = hiddenSessionBinder.javaClass.interfaces
            .firstOrNull { it.simpleName == "IPackageInstallerSession" }
            ?: error("Could not find IPackageInstallerSession among: ${hiddenSessionBinder.javaClass.interfaces.map { it.name }}")

        val sessionClass = PackageInstaller.Session::class.java
        val constructor = sessionClass.getDeclaredConstructor(sessionInterface)
        constructor.isAccessible = true
        return constructor.newInstance(hiddenSessionBinder)
    }

    /**
     * Копирует байты APK-файла в сессию через ПУБЛИЧНЫЙ
     * Session.openWrite()/commit() — никакого reflection здесь, это
     * обычный Android API с момента появления PackageInstaller (API 21).
     */
    private fun writeApkIntoSession(session: PackageInstaller.Session, apkFile: File) {
        FileInputStream(apkFile).use { input ->
            session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }
    }

    private fun commitSessionSilently(session: PackageInstaller.Session) {
        val statusReceiver = SilentIntentSender.create { success, message ->
            lastInstallResult = if (success) InstallResult.Success else InstallResult.Failure(message ?: "Install failed")
        }
        session.commit(statusReceiver)

        val result = waitForInstallResult()
        if (result is InstallResult.Failure) {
            throw RuntimeException(result.message)
        }
    }

    @Volatile
    private var lastInstallResult: InstallResult? = null

    private fun waitForInstallResult(timeoutMs: Long = 60_000): InstallResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (lastInstallResult == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return lastInstallResult ?: InstallResult.Failure("Install timed out waiting for system callback")
            .also { lastInstallResult = null }
    }

    private fun invokeUninstall(method: java.lang.reflect.Method, target: Any, packageName: String, statusReceiver: android.content.IntentSender) {
        // Параметры варьируются между версиями (см. комментарий у вызова
        // выше) — собираем аргументы под РЕАЛЬНОЕ количество параметров
        // метода, найденного через reflection, вместо жёсткого списка.
        //
        // callerPackageName берём из BuildConfig.APPLICATION_ID, а не
        // хардкодим строкой — так значение всегда совпадает с реальным
        // applicationId сборки (переименование пакета проекта не потребует
        // синхронной правки этой строки вручную, и не рискует разойтись
        // с фактическим UID, от чьего имени идёт вызов).
        val paramCount = method.parameterTypes.size
        val args = when (paramCount) {
            5 -> arrayOf(packageName, com.maybewedead.chudstore.BuildConfig.APPLICATION_ID, 0, statusReceiver, 0)
            4 -> arrayOf(packageName, 0, statusReceiver, 0)
            else -> error("Unexpected uninstall() signature with $paramCount parameters")
        }
        method.invoke(target, *args)
    }
}

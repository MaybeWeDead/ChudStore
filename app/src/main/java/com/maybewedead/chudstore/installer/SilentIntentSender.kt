package com.maybewedead.chudstore.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger

/**
 * SilentIntentSender — PackageInstaller.Session.commit()/IPackageInstaller.uninstall()
 * оба принимают IntentSender как способ доставки результата (успех/
 * провал/требуется доп. подтверждение) — это ЕДИНСТВЕННЫЙ официальный
 * механизм получения статуса, привязанный к системе broadcast'ов, не
 * просто callback-объект. Раз наш installer-код синхронный (см.
 * PackageInstallerService — блокирующий wait с polling), оборачиваем
 * это через одноразовый BroadcastReceiver + PendingIntent под уникальным
 * action-именем на каждый вызов, чтобы параллельные install/uninstall
 * не путали чужие статусы друг с другом.
 *
 * ВАЖНО: требует Context на момент create() — держим ссылку на
 * Application context (не Activity), чтобы receiver переживал поворот
 * экрана/смену Activity во время долгой установки.
 */
object SilentIntentSender {

    // ACTION_PREFIX привязан к BuildConfig.APPLICATION_ID, а не хардкожен —
    // не критично функционально (это просто internal-use action name), но
    // исключает путаницу при переименовании пакета проекта.
    private val ACTION_PREFIX get() = "${com.maybewedead.chudstore.BuildConfig.APPLICATION_ID}.INSTALL_STATUS_"
    private val requestCodeCounter = AtomicInteger(0)

    private lateinit var appContext: Context

    /** Вызывается один раз из Application.onCreate() — см. ChudStoreApp.kt. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * onResult(success, message) — message заполнен только при неуспехе
     * (EXTRA_STATUS_MESSAGE от системы, человекочитаемая причина отказа,
     * например "INSTALL_FAILED_VERSION_DOWNGRADE" для попытки
     * даунгрейда без allowDowngrade=true).
     */
    fun create(onResult: (success: Boolean, message: String?) -> Unit): android.content.IntentSender {
        check(::appContext.isInitialized) { "SilentIntentSender.initialize() was not called" }

        val requestCode = requestCodeCounter.incrementAndGet()
        val action = "$ACTION_PREFIX$requestCode"

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

                // STATUS_PENDING_USER_ACTION означает, что система ВСЁ-ТАКИ
                // хочет показать диалог (обычно это происходит, если
                // shell UID почему-то не считается "доверенным" для
                // конкретного случая — редко, но возможно на некоторых
                // OEM-прошивках с усиленными проверками, см. Dhizuku
                // упоминание в поиске выше как раз про такие случаи) —
                // считаем это неуспехом silent-режима, а не полным провалом,
                // чтобы UI мог явно сказать пользователю "эта установка
                // требует ручного подтверждения" вместо голого "ошибка".
                val success = status == PackageInstaller.STATUS_SUCCESS
                val effectiveMessage = when (status) {
                    PackageInstaller.STATUS_SUCCESS -> null
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> "System requires manual confirmation for this install (not silent-capable on this device/package)"
                    else -> message ?: "Install/uninstall failed with status code $status"
                }

                onResult(success, effectiveMessage)

                appContext.unregisterReceiver(this)
            }
        }

        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        val intent = Intent(action).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        val pendingIntent = PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
        return pendingIntent.intentSender
    }
}

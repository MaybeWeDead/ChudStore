package com.maybewedead.chudstore

import android.app.Application
import com.maybewedead.chudstore.installer.SilentIntentSender
import com.maybewedead.chudstore.shizuku.ShizukuBridge

/**
 * ChudStoreApp — единственное место, где вызываются init()-методы
 * синглтонов, зависящих от жизненного цикла процесса (не Activity):
 *
 *   - ShizukuBridge.init() ДОЛЖЕН быть вызван до первого обращения к
 *     Shizuku где-либо (класс Shizuku использует статические слушатели
 *     на уровне процесса — см. подробный комментарий в ShizukuBridge.kt)
 *   - SilentIntentSender.initialize() нужен application context для
 *     регистрации BroadcastReceiver'ов, переживающих смену Activity во
 *     время долгой установки/удаления APK
 */
class ChudStoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.init()
        SilentIntentSender.initialize(this)
    }
}

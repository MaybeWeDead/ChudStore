package com.maybewedead.chudstore.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val PERMISSION_REQUEST_CODE = 0x5A11

    private var permissionGrantedListener: ((Boolean) -> Unit)? = null

    fun init() {
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) {
                return@addRequestPermissionResultListener
            }

            val granted = grantResult == PackageManager.PERMISSION_GRANTED

            Log.i(TAG, "Shizuku permission result: granted=$granted")

            permissionGrantedListener?.invoke(granted)
        }
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        if (!isAvailable()) {
            return false
        }

        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

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

        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            permissionGrantedListener = null
            Log.e(TAG, "Failed to request Shizuku permission", e)
            onResult(false)
        }
    }

    private fun getSystemServiceBinder(serviceName: String): IBinder {
        val binder = SystemServiceHelper.getSystemService(serviceName)
        return ShizukuBinderWrapper(binder)
    }

    private fun stubAsInterfaceMethod(stubClassName: String): Pair<Class<*>, Method> {
        val stubClass = Class.forName(stubClassName)

        val method = HiddenApiBypass
            .getDeclaredMethods(stubClass)
            .filterIsInstance<Method>()
            .first { it.name == "asInterface" }

        return stubClass to method
    }

    fun getHiddenSystemInterface(
        serviceName: String,
        stubClassName: String
    ): Any {
        check(hasPermission()) {
            "Shizuku permission not granted"
        }

        val binder = getSystemServiceBinder(serviceName)
        val (_, asInterfaceMethod) = stubAsInterfaceMethod(stubClassName)

        return asInterfaceMethod.invoke(null, binder)
            ?: error("asInterface($stubClassName) returned null")
    }
}

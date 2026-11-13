package com.kourosh.netoptimizer

import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.lang.reflect.Proxy

class KouroshVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeCoreInstance: Any? = null

    companion object {
        @Volatile
        var isRunning = false
        var activeServerStatus = "خاموش"
        var lastMeasuredDelayMs: Long = -1L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START_VPN"
        if (action == "STOP_VPN") {
            stopXrayAndVpn()
            return START_NOT_STICKY
        }

        val vlessUri = intent?.getStringExtra("VLESS_URI") ?: ""
        val cleanIp = intent?.getStringExtra("CLEAN_IP") ?: "162.159.135.42"

        startXrayAndVpn(vlessUri, cleanIp)
        return START_STICKY
    }

    private fun startXrayAndVpn(vlessUri: String, cleanIp: String) {
        try {
            stopXrayAndVpn()

            val configJson = if (vlessUri.startsWith("vless://")) {
                XrayConfigBuilder.buildJsonFromVlessUri(vlessUri, cleanIp)
            } else {
                throw IllegalArgumentException("لینک VLESS معتبر نیست.")
            }

            // ۱. ایجاد کارت شبکه مجازی اندروید (TUN) با MTU 1280 و پراکسی محلی 10809
            val builder = Builder()
                .setSession("Kourosh Xray VLESS Core")
                .setMtu(1280)
                .addAddress("10.200.200.1", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", 10809))
            }

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            vpnInterface = builder.establish()
            val tunFd = vpnInterface?.fd ?: 0

            // ۲. بارگذاری پویا و بدون خطای کامپایل هسته Xray (سازگار با CoreController جدید و V2RayPoint)
            val libClass = Class.forName("libv2ray.Libv2ray")

            try {
                val initMethod = libClass.methods.firstOrNull {
                    it.name == "initCoreEnv" && it.parameterTypes.size == 2
                }
                initMethod?.invoke(null, filesDir.absolutePath, "")
            } catch (_: Exception) {}

            val newCallbackClass = try {
                Class.forName("libv2ray.CoreCallbackHandler")
            } catch (_: Exception) {
                null
            }

            if (newCallbackClass != null) {
                val callbackProxy = Proxy.newProxyInstance(
                    newCallbackClass.classLoader,
                    arrayOf(newCallbackClass)
                ) { _, method, args ->
                    when (method.name) {
                        "startup" -> 0L
                        "shutdown" -> {
                            stopSelf()
                            0L
                        }
                        "onEmitStatus" -> {
                            if (args != null && args.size >= 2 && args[1] != null) {
                                activeServerStatus = args[1].toString()
                            }
                            0L
                        }
                        "protect" -> {
                            val fd = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                            if (fd >= 0) this@KouroshVpnService.protect(fd) else true
                        }
                        else -> 0L
                    }
                }

                val newCtrlMethod = libClass.getMethod("newCoreController", newCallbackClass)
                val controller = newCtrlMethod.invoke(null, callbackProxy)
                    ?: throw IllegalStateException("خطا در ساخت CoreController")
                activeCoreInstance = controller

                val startLoopMethods = controller.javaClass.methods.filter { it.name == "startLoop" }
                val startTwoArgs = startLoopMethods.firstOrNull { it.parameterTypes.size == 2 }
                val startOneArg = startLoopMethods.firstOrNull { it.parameterTypes.size == 1 }

                if (startTwoArgs != null) {
                    startTwoArgs.invoke(controller, configJson, 0)
                } else if (startOneArg != null) {
                    startOneArg.invoke(controller, configJson)
                }
            } else {
                val oldCallbackClass = Class.forName("libv2ray.V2RayVPNServiceSupportsSet")
                val callbackProxy = Proxy.newProxyInstance(
                    oldCallbackClass.classLoader,
                    arrayOf(oldCallbackClass)
                ) { _, method, args ->
                    when (method.name) {
                        "shutdown", "prepare", "setup" -> 0L
                        "onEmitStatus" -> {
                            if (args != null && args.size >= 2 && args[1] != null) {
                                activeServerStatus = args[1].toString()
                            }
                            0L
                        }
                        "protect" -> {
                            val fd = (args?.getOrNull(0) as? Number)?.toInt() ?: -1
                            if (fd >= 0) this@KouroshVpnService.protect(fd) else true
                        }
                        else -> 0L
                    }
                }

                val newPointMethod = libClass.methods.first { it.name == "newV2RayPoint" }
                val point = if (newPointMethod.parameterTypes.size == 2) {
                    newPointMethod.invoke(null, callbackProxy, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1)
                } else {
                    newPointMethod.invoke(null, callbackProxy)
                } ?: throw IllegalStateException("خطا در ساخت V2RayPoint")

                activeCoreInstance = point
                point.javaClass.getMethod("setConfigureFileContent", String::class.java).invoke(point, configJson)
                point.javaClass.getMethod("setDomainName", String::class.java).invoke(point, "speed.cloudflare.com:443")
                val runLoop = point.javaClass.methods.first { it.name == "runLoop" }
                if (runLoop.parameterTypes.size == 1) {
                    runLoop.invoke(point, false)
                } else {
                    runLoop.invoke(point)
                }
            }

            isRunning = vpnInterface != null
            activeServerStatus = "✅ هسته Xray فعال است (پورت 10808/10809 + TUN FD:$tunFd)"

            // ۳. تست پینگ واقعی از دل تانل Xray
            Thread {
                try {
                    Thread.sleep(1500)
                    val ctrl = activeCoreInstance ?: return@Thread
                    val measureMethod = ctrl.javaClass.methods.firstOrNull {
                        it.name == "measureDelay" && it.parameterTypes.size == 1
                    }
                    val delayRes = measureMethod?.invoke(ctrl, "https://cp.cloudflare.com/generate_204") as? Number
                    lastMeasuredDelayMs = delayRes?.toLong() ?: -1L
                    if (lastMeasuredDelayMs > 0) {
                        activeServerStatus = "✅ متصل به Xray | پینگ واقعی تانل: $lastMeasuredDelayMs ms"
                    }
                } catch (_: Exception) {}
            }.start()

        } catch (e: Exception) {
            val causeMsg = e.cause?.message ?: e.message ?: "Unknown Error"
            isRunning = vpnInterface != null
            activeServerStatus = "⚠️ وضعیت هسته: $causeMsg"
        }
    }

    private fun stopXrayAndVpn() {
        try {
            activeCoreInstance?.let { ctrl ->
                val stopMethod = ctrl.javaClass.methods.firstOrNull {
                    it.name == "stopLoop" && it.parameterTypes.isEmpty()
                }
                stopMethod?.invoke(ctrl)
            }
        } catch (_: Exception) {}
        activeCoreInstance = null

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        isRunning = false
        lastMeasuredDelayMs = -1L
        activeServerStatus = "🛑 هسته Xray متوقف شد."
    }

    override fun onDestroy() {
        stopXrayAndVpn()
        super.onDestroy()
    }
}

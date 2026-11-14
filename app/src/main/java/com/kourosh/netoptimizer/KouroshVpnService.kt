package com.kourosh.netoptimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

class KouroshVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeCoreInstance: Any? = null

    companion object {
        @Volatile
        var isRunning = false
        @Volatile
        var isVerifiedBypass = false
        @Volatile
        var activeServerStatus = "خاموش"
        @Volatile
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
        val mtuValue = intent?.getIntExtra("OPTIMAL_MTU", 1280) ?: 1280

        promoteToStealthForeground("رادار هوشمند و تانل Xray در پس‌زمینه فعال است")
        startXrayAndVpn(vlessUri, cleanIp, mtuValue)
        return START_STICKY
    }

    private fun promoteToStealthForeground(statusText: String) {
        try {
            val channelId = "kourosh_stealth_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    channelId,
                    "Kourosh Background Radar",
                    NotificationManager.IMPORTANCE_LOW
                )
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(chan)
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

            // استفاده از آیکون عمومی و استاندارد اندروید (ic_lock_lock)
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle("Kourosh NetRadar AI (حالت مخفی فعال)")
                    .setContentText(statusText)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("Kourosh NetRadar AI")
                    .setContentText(statusText)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            }
            startForeground(1001, notif)
        } catch (_: Exception) {}
    }

    private fun startXrayAndVpn(vlessUri: String, cleanIp: String, mtuValue: Int) {
        try {
            stopCoreOnly()
            isVerifiedBypass = false

            val configJson = if (vlessUri.startsWith("vless://")) {
                XrayConfigBuilder.buildJsonFromVlessUri(vlessUri, cleanIp)
            } else {
                throw IllegalArgumentException("لینک VLESS معتبر نیست.")
            }

            val builder = Builder()
                .setSession("Kourosh Smart Xray Tunnel")
                .setMtu(mtuValue.coerceIn(1280, 1500))
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
                        "onEmitStatus" -> 0L
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
                        "shutdown", "prepare", "setup", "onEmitStatus" -> 0L
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
            activeServerStatus = "⏳ هسته روشن شد؛ در حال تست واقعی عبور از فیلتر ایران..."

            Thread {
                try {
                    Thread.sleep(1400)
                    val proxy = java.net.Proxy(
                        java.net.Proxy.Type.HTTP,
                        InetSocketAddress("127.0.0.1", 10809)
                    )
                    val t0 = System.currentTimeMillis()
                    val conn = (URL("https://cp.cloudflare.com/generate_204").openConnection(proxy) as HttpURLConnection).apply {
                        connectTimeout = 4500
                        readTimeout = 4500
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        lastMeasuredDelayMs = System.currentTimeMillis() - t0
                        isVerifiedBypass = true
                        activeServerStatus = "✅ عبور از فیلتر تایید شد! پینگ واقعی تانل: $lastMeasuredDelayMs ms"
                        promoteToStealthForeground("متصل و بدون فیلتر | پینگ: $lastMeasuredDelayMs ms")
                    } else {
                        isVerifiedBypass = false
                        activeServerStatus = "⚠️ تانل برقرار است اما پاسخ HTTP $code دریافت شد."
                    }
                } catch (e: Exception) {
                    isVerifiedBypass = false
                    activeServerStatus = "⚠️ سرور فعلی از فیلتر رد نشد؛ دکمه دریافت هوشمند را بزنید."
                }
            }.start()

        } catch (e: Exception) {
            val causeMsg = e.cause?.message ?: e.message ?: "Unknown Error"
            isRunning = vpnInterface != null
            activeServerStatus = "⚠️ وضعیت هسته: $causeMsg"
        }
    }

    private fun stopCoreOnly() {
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
    }

    private fun stopXrayAndVpn() {
        stopCoreOnly()
        isRunning = false
        isVerifiedBypass = false
        lastMeasuredDelayMs = -1L
        activeServerStatus = "🛑 هسته Xray متوقف شد."
        try {
            @Suppress("DEPRECATION")
            stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopXrayAndVpn()
        super.onDestroy()
    }
}

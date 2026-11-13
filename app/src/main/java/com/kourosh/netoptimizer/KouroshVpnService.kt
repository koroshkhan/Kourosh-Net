package com.kourosh.netoptimizer

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

class KouroshVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null

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

            val callback = object : V2RayVPNServiceSupportsSet {
                override fun shutdown(): Long {
                    stopSelf()
                    return 0
                }

                override fun prepare(): Long {
                    return 0
                }

                override fun protect(socketFd: Long): Boolean {
                    return this@KouroshVpnService.protect(socketFd.toInt())
                }

                override fun onEmitStatus(status: Long, info: String?): Long {
                    activeServerStatus = info ?: "متصل به هسته Xray"
                    return 0
                }

                override fun setup(parameters: String?): Long {
                    return 0
                }
            }

            val needAsyncResolve = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
            v2rayPoint = Libv2ray.newV2RayPoint(callback, needAsyncResolve)
            v2rayPoint?.configureFileContent = configJson
            v2rayPoint?.domainName = "speed.cloudflare.com:443"
            v2rayPoint?.runLoop(false)

            val builder = Builder()
                .setSession("Kourosh Xray VLESS Core")
                .setMtu(1280)
                .addAddress("10.200.200.1", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(android.net.ProxyInfo.buildDirectProxy("127.0.0.1", 10809))
            }

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            vpnInterface = builder.establish()
            isRunning = (v2rayPoint?.isRunning == true)
            activeServerStatus = if (isRunning) "✅ هسته Xray فعال است (پورت 10808/10809 + TUN)" else "❌ خطا در اجرای هسته"

            Thread {
                try {
                    Thread.sleep(1200)
                    lastMeasuredDelayMs = v2rayPoint?.measureDelay("https://cp.cloudflare.com/generate_204") ?: -1L
                    if (lastMeasuredDelayMs > 0) {
                        activeServerStatus = "✅ متصل به Xray | پینگ واقعی تانل: $lastMeasuredDelayMs ms"
                    }
                } catch (_: Exception) {}
            }.start()

        } catch (e: Exception) {
            isRunning = false
            activeServerStatus = "❌ خطا در راه‌اندازی هسته Xray: ${e.message}"
        }
    }

    private fun stopXrayAndVpn() {
        try {
            if (v2rayPoint?.isRunning == true) {
                v2rayPoint?.stopLoop()
            }
        } catch (_: Exception) {}
        v2rayPoint = null

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

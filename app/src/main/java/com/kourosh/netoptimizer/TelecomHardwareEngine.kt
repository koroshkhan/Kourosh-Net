package com.kourosh.netoptimizer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.pow

data class CellTowerNode(
    val generation: String,
    val isServing: Boolean,
    val pci: Int,
    val cid: String,
    val bandChannel: Int,
    val rsrpDbm: Int,
    val sinrDb: Int,
    val estimatedDistanceMeters: Int,
    val qualityPercent: Int
)

data class FullNetworkTelemetry(
    val deviceModel: String,
    val socInfo: String,
    val operatorName: String,
    val isVpnActiveOnSystem: Boolean,
    val activeProfileMode: String,
    val recommendedMtu: Int,
    val primaryTower: CellTowerNode?,
    val neighborTowers: List<CellTowerNode>,
    val directionHint: String
)

class TelecomHardwareEngine(private val context: Context) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var modemWakeLock: PowerManager.WakeLock? = null
    private var bestRecordedRsrp = -130

    @SuppressLint("MissingPermission")
    fun scanAllTowersAndVpnState(): FullNetworkTelemetry {
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} (${Runtime.getRuntime().availableProcessors()} Cores)"
        } else {
            "${Build.HARDWARE} (${Runtime.getRuntime().availableProcessors()} Cores)"
        }
        val operator = telephonyManager.networkOperatorName.ifEmpty { "نامشخص" }
        val vpnActive = isSystemVpnConnected() || KouroshVpnService.isRunning

        val towers = mutableListOf<CellTowerNode>()

        try {
            val allCells = telephonyManager.allCellInfo
            if (!allCells.isNullOrEmpty()) {
                for (cell in allCells) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                        val id = cell.cellIdentity as CellIdentityNr
                        val sig = cell.cellSignalStrength as CellSignalStrengthNr
                        val rsrp = if (sig.ssRsrp != CellInfo.UNAVAILABLE && sig.ssRsrp in -140..-40) sig.ssRsrp else -110
                        val sinr = if (sig.ssSinr != CellInfo.UNAVAILABLE && sig.ssSinr in -20..40) sig.ssSinr else 5
                        val pci = if (id.pci != CellInfo.UNAVAILABLE) id.pci else 0
                        val arfcn = if (id.nrarfcn != CellInfo.UNAVAILABLE) id.nrarfcn else 0
                        val dist = estimateTowerDistanceMeters(rsrp, null)
                        val qual = ((rsrp + 120) * 100 / 55).coerceIn(5, 100)

                        towers.add(
                            CellTowerNode(
                                generation = "5G (NR)",
                                isServing = cell.isRegistered,
                                pci = pci,
                                cid = if (id.nci != CellInfo.UNAVAILABLE.toLong()) id.nci.toString() else "NR-Tower",
                                bandChannel = arfcn,
                                rsrpDbm = rsrp,
                                sinrDb = sinr,
                                estimatedDistanceMeters = dist,
                                qualityPercent = qual
                            )
                        )
                    } else if (cell is CellInfoLte) {
                        val id = cell.cellIdentity
                        val sig = cell.cellSignalStrength
                        val rsrp = if (sig.rsrp != CellInfo.UNAVAILABLE && sig.rsrp in -140..-40) sig.rsrp else -110
                        val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sig.rssnr != CellInfo.UNAVAILABLE && sig.rssnr in -20..40) sig.rssnr else 6
                        val pci = if (id.pci != CellInfo.UNAVAILABLE) id.pci else 0
                        val earfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && id.earfcn != CellInfo.UNAVAILABLE) id.earfcn else 0
                        val ta = if (sig.timingAdvance != CellInfo.UNAVAILABLE && sig.timingAdvance in 1..1282) sig.timingAdvance else null
                        val dist = estimateTowerDistanceMeters(rsrp, ta)
                        val qual = ((rsrp + 120) * 100 / 55).coerceIn(5, 100)

                        towers.add(
                            CellTowerNode(
                                generation = "4G (LTE)",
                                isServing = cell.isRegistered,
                                pci = pci,
                                cid = if (id.ci != CellInfo.UNAVAILABLE) id.ci.toString() else "LTE-Neighbor",
                                bandChannel = earfcn,
                                rsrpDbm = rsrp,
                                sinrDb = sinr,
                                estimatedDistanceMeters = dist,
                                qualityPercent = qual
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        val primary = towers.firstOrNull { it.isServing } ?: towers.maxByOrNull { it.rsrpDbm }
        val neighbors = towers.filter { it != primary }.sortedByDescending { it.rsrpDbm }

        val hint = if (primary != null) {
            if (primary.rsrpDbm > bestRecordedRsrp) {
                bestRecordedRsrp = primary.rsrpDbm
                vibratePulse(40)
            }
            val betterNeighbor = neighbors.firstOrNull { it.rsrpDbm > primary.rsrpDbm + 4 }
            when {
                betterNeighbor != null ->
                    "⚡ دکل قوی‌تر (PCI: ${betterNeighbor.pci} در فاصله ~${betterNeighbor.estimatedDistanceMeters} متری) شناسایی شد! سوییچ شبکه را بزنید."
                primary.rsrpDbm >= -85 ->
                    "🎯 نقطه طلایی آنتن! فاصله تا دکل حدود ${primary.estimatedDistanceMeters} متر است (حداکثر سرعت دانلود)."
                primary.rsrpDbm >= -98 ->
                    "📡 فاصله تا دکل حدود ${primary.estimatedDistanceMeters} متر است. با حرکت به سمت پنجره، RSRP را به بالای -85 برسانید."
                else ->
                    "⚠️ دکل در فاصله دور (~${primary.estimatedDistanceMeters} متر) قرار دارد. قفل 4G خالص را بزنید."
            }
        } else {
            "مجوز مکان (GPS) را روشن کنید تا رادار فاصله و جهت دکل‌های اطراف را محاسبه کند."
        }

        return FullNetworkTelemetry(
            deviceModel = device,
            socInfo = soc,
            operatorName = operator,
            isVpnActiveOnSystem = vpnActive,
            activeProfileMode = if (vpnActive) "حالت فیلترشکن روشن (Anti-DPI + MTU 1280)" else "حالت مستقیم بدون فیلترشکن (Max Speed + MTU 1500)",
            recommendedMtu = if (vpnActive) 1280 else 1500,
            primaryTower = primary,
            neighborTowers = neighbors.take(5),
            directionHint = hint
        )
    }

    private fun estimateTowerDistanceMeters(rsrpDbm: Int, timingAdvance: Int?): Int {
        if (timingAdvance != null && timingAdvance > 0) {
            return (timingAdvance * 78).coerceIn(50, 15000)
        }
        val exponent = (-48.0 - rsrpDbm) / 28.0
        return (10.0.pow(exponent) * 18.0).toInt().coerceIn(60, 12000)
    }

    fun isSystemVpnConnected(): Boolean {
        return try {
            val activeNet = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNet) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    fun launchExternalVpnClient(packageName: String): String {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "✅ برنامه $packageName باز شد."
            } else {
                val vpnSettings = Intent(Settings.ACTION_VPN_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(vpnSettings)
                "⚙️ پنل مدیریت VPN اندروید باز شد."
            }
        } catch (e: Exception) {
            "خطا در باز کردن کلاینت VPN"
        }
    }

    suspend fun runParallelTurboDownload(
        fileUrl: String,
        channels: Int = 8,
        onProgress: (speedMbps: Double, downloadedMb: Double, progressPercent: Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            var totalBytesRead = 0L

            if (fileUrl.isBlank()) {
                val testUrls = listOf(
                    "https://speed.cloudflare.com/__down?bytes=10000000",
                    "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js"
                )
                coroutineScope {
                    val jobs = (0 until channels).map { idx ->
                        async(Dispatchers.IO) {
                            val u = URL("${testUrls[idx % testUrls.size]}&r=${System.nanoTime()}")
                            val conn = (u.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000
                                readTimeout = 7000
                                setRequestProperty("Accept-Encoding", "identity")
                            }
                            conn.inputStream.use { inp ->
                                val buf = ByteArray(16384)
                                while (System.currentTimeMillis() - startTime < 6500) {
                                    val n = inp.read(buf)
                                    if (n <= 0) break
                                    synchronized(this@TelecomHardwareEngine) {
                                        totalBytesRead += n
                                    }
                                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.2)
                                    val mbps = (totalBytesRead * 8.0 / elapsedSec) / 1_000_000.0
                                    val mb = totalBytesRead / 1_048_576.0
                                    val pct = ((elapsedSec / 6.5) * 100).toInt().coerceIn(1, 100)
                                    onProgress(mbps, mb, pct)
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                val totalSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.5)
                val finalMbps = (totalBytesRead * 8.0 / totalSec) / 1_000_000.0
                return@withContext "✅ تست اشباع $channels کاناله کامل شد! سرعت واقعی: ${"%.2f".format(finalMbps)} Mbps (${"%.2f".format(finalMbps / 8)} MB/s)"
            }

            val urlObj = URL(fileUrl)
            val headConn = (urlObj.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 6000
            }
            val contentLength = headConn.contentLengthLong
            val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "Kourosh_Turbo_${System.currentTimeMillis()}.bin" }
            val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val outFile = File(outDir, fileName)

            if (contentLength > 1024 * 1024) {
                RandomAccessFile(outFile, "rw").use { it.setLength(contentLength) }
                val partSize = contentLength / channels
                coroutineScope {
                    val jobs = (0 until channels).map { part ->
                        async(Dispatchers.IO) {
                            val startByte = part * partSize
                            val endByte = if (part == channels - 1) contentLength - 1 else (startByte + partSize - 1)
                            val c = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                                setRequestProperty("Range", "bytes=$startByte-$endByte")
                                connectTimeout = 8000
                                readTimeout = 10000
                            }
                            c.inputStream.use { input ->
                                RandomAccessFile(outFile, "rw").use { raf ->
                                    raf.seek(startByte)
                                    val buf = ByteArray(16384)
                                    while (true) {
                                        val read = input.read(buf)
                                        if (read == -1) break
                                        raf.write(buf, 0, read)
                                        synchronized(this@TelecomHardwareEngine) {
                                            totalBytesRead += read
                                        }
                                        val sec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.2)
                                        val mbps = (totalBytesRead * 8.0 / sec) / 1_000_000.0
                                        val pct = ((totalBytesRead * 100) / contentLength).toInt().coerceIn(1, 100)
                                        onProgress(mbps, totalBytesRead / 1_048_576.0, pct)
                                    }
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } else {
                urlObj.openStream().use { inp ->
                    outFile.outputStream().use { out ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val r = inp.read(buf)
                            if (r == -1) break
                            out.write(buf, 0, r)
                            totalBytesRead += r
                            val sec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.2)
                            onProgress((totalBytesRead * 8.0 / sec) / 1_000_000.0, totalBytesRead / 1_048_576.0, 95)
                        }
                    }
                }
            }
            onProgress(0.0, totalBytesRead / 1_048_576.0, 100)
            "✅ فایل در پوشه Downloads ذخیره شد: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "❌ خطا در دانلود: ${e.message}"
        }
    }

    fun setHardwareWakeLock(enable: Boolean): String {
        return try {
            if (enable) {
                if (modemWakeLock == null) {
                    modemWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kourosh::TurboModemLock")
                }
                if (modemWakeLock?.isHeld == false) modemWakeLock?.acquire(2 * 60 * 60 * 1000L)
                "🔥 قفل توان حداکثری پردازنده و ضدخواب مودم فعال شد."
            } else {
                if (modemWakeLock?.isHeld == true) modemWakeLock?.release()
                "قفل بیدارباش مودم غیرفعال شد."
            }
        } catch (e: Exception) {
            "خطا: ${e.message}"
        }
    }

    fun applyNetworkBandLock(lockTo5G: Boolean): String {
        val code = if (lockTo5G) 26 else 11
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings put global preferred_network_mode $code"))
            if (proc.waitFor() == 0) {
                "✅ باند شبکه در سیستم‌عامل قفل شد!"
            } else {
                openRadioMenu()
                "📡 منوی رادیویی باز شد؛ شبکه را روی ${if (lockTo5G) "NR/LTE" else "LTE Only"} بگذارید."
            }
        } catch (_: Exception) {
            openRadioMenu()
            "📡 منوی قفل باند باز شد."
        }
    }

    private fun openRadioMenu() {
        val list = listOf(
            ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
            ComponentName("com.android.settings", "com.android.settings.TestingSettings")
        )
        for (c in list) {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).apply {
                    component = c
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return
            } catch (_: Exception) {}
        }
    }

    fun applyAndOpenBestApnSettings(): String {
        val op = telephonyManager.simOperator ?: ""
        val apnValue = when {
            op.startsWith("43235") -> "mtnirancell"
            op.startsWith("43211") -> "mcinet"
            op.startsWith("43220") -> "RighTel"
            else -> "internet"
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("APN", apnValue))
        try {
            val apnIntent = Intent(Settings.ACTION_APN_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(apnIntent)
        } catch (_: Exception) {}
        return "✅ عبارت '$apnValue' کپی شد و صفحه APN گوشی باز شد! پروتکل را روی IPv4 و Bearer را روی LTE/NR بگذارید."
    }

    suspend fun fetchAndSelectBestVlessConfig(
        onStatusUpdate: (String) -> Unit
    ): Pair<String?, String> = withContext(Dispatchers.IO) {

        val mirrors = listOf(
            "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/Sub1.txt",
            "https://cdn.jsdelivr.net/gh/mahdibland/V2RayAggregator@master/sub/splitted/vless.txt",
            "https://cdn.jsdelivr.net/gh/yebekhe/TVC@main/subscriptions/xray/normal/mix",
            "https://ghproxy.net/https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt"
        )

        val cleanIps = listOf("162.159.135.42", "104.16.132.229", "172.67.73.163")
        val rawCandidates = mutableListOf<String>()

        onStatusUpdate("⏳ مرحله ۱/۲: در حال دریافت لیست سرورهای روز از CDN...")

        for (urlStr in mirrors) {
            try {
                val conn = (URL("$urlStr?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    val decoded = decodeIfBase64(body)
                    val vlessLines = decoded.lineSequence()
                        .map { it.trim() }
                        .filter {
                            it.startsWith("vless://") &&
                            (it.contains("security=tls") || it.contains("security=reality")) &&
                            (it.contains("type=ws") || it.contains("type=tcp") || it.contains("type=grpc")) &&
                            !it.contains("@127.0.0.1")
                        }
                        .toList()

                    if (vlessLines.size >= 5) {
                        rawCandidates.addAll(vlessLines.shuffled().take(25))
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        if (rawCandidates.isEmpty()) {
            return@withContext Pair(
                null,
                "❌ خطا در دریافت لیست از CDN. اتصال اینترنت سیم‌کارت یا وای‌فای را بررسی کنید."
            )
        }

        onStatusUpdate("🔍 مرحله ۲/۲: ${rawCandidates.size} سرور دریافت شد؛ در حال تست پینگ واقعی...")

        var bestUri: String? = null
        var bestPing = 9999L

        coroutineScope {
            val deferredList = rawCandidates.take(16).mapIndexed { index, uriStr ->
                async(Dispatchers.IO) {
                    try {
                        val parsed = android.net.Uri.parse(uriStr)
                        val host = parsed.getQueryParameter("sni")
                            ?: parsed.getQueryParameter("host")
                            ?: parsed.host
                            ?: return@async null
                        val port = if (parsed.port != -1) parsed.port else 443
                        val netType = parsed.getQueryParameter("type") ?: "tcp"

                        val targetAddress = if (netType == "ws") {
                            cleanIps[index % cleanIps.size]
                        } else {
                            parsed.host ?: host
                        }

                        val t0 = System.currentTimeMillis()
                        Socket().use { sock ->
                            sock.tcpNoDelay = true
                            sock.connect(InetSocketAddress(targetAddress, port), 2200)
                        }
                        val rtt = System.currentTimeMillis() - t0
                        val cleanRemarkUri = uriStr.substringBefore("#") + "#Kourosh_Auto_${rtt}ms"
                        Pair(cleanRemarkUri, rtt)
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            val results = deferredList.awaitAll().filterNotNull().sortedBy { it.second }
            if (results.isNotEmpty()) {
                bestUri = results.first().first
                bestPing = results.first().second
            }
        }

        return@withContext if (bestUri != null) {
            withContext(Dispatchers.Main) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Auto_VLESS", bestUri))
            }
            Pair(
                bestUri,
                "✅ سرور سالم با پینگ $bestPing ms پیدا شد و داخل کادر قرار گرفت! اکنون دکمه سبز اتصال را بزنید."
            )
        } else {
            val fallbackWs = rawCandidates.firstOrNull { it.contains("type=ws") } ?: rawCandidates.first()
            withContext(Dispatchers.Main) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Auto_VLESS", fallbackWs))
            }
            Pair(
                fallbackWs,
                "⚡ کانفیگ روز دریافت و در کادر جایگذاری شد (برای عبور با موتور Fragment دکمه اتصال را بزنید)."
            )
        }
    }

    private fun decodeIfBase64(input: String): String {
        if (input.contains("vless://") || input.contains("vmess://")) return input
        return try {
            val clean = input.replace("\\s".toRegex(), "")
            String(android.util.Base64.decode(clean, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            input
        }
    }

    private fun vibratePulse(ms: Long) {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }
}

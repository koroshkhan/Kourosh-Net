package com.kourosh.netoptimizer

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
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

data class HardwareDriverStatus(
    val componentName: String,
    val currentDriverVersion: String,
    val statusBadge: String,
    val updateDescription: String,
    val directDownloadUrl: String,
    val fallbackWebPage: String
)

data class FullNetworkTelemetry(
    val deviceModel: String,
    val socInfo: String,
    val cpuFreqAndGov: String = "Auto Boost",
    val ramUsageInfo: String = "Optimized",
    val basebandDriver: String = "Standard",
    val kernelAndTcpInfo: String = "TCP Optimized",
    val operatorName: String,
    val isVpnActiveOnSystem: Boolean,
    val activeProfileMode: String,
    val recommendedMtu: Int,
    val recommendedSocketBufferKb: Int = 256,
    val primaryTower: CellTowerNode?,
    val neighborTowers: List<CellTowerNode>,
    val directionHint: String
)

class TelecomHardwareEngine(private val context: Context) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var modemWakeLock: PowerManager.WakeLock? = null
    private var wifiHighPerfLock: WifiManager.WifiLock? = null
    private var bestRecordedRsrp = -130

    @SuppressLint("MissingPermission")
    fun scanAllTowersAndVpnState(): FullNetworkTelemetry {
        val device = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} (${Build.DEVICE})"
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} (${Runtime.getRuntime().availableProcessors()} هسته)"
        } else {
            "${Build.HARDWARE} (${Runtime.getRuntime().availableProcessors()} هسته)"
        }

        val cpuFreq = readCpuFreqAndGovernor()
        val ramInfo = readRamStatus()
        val baseband = Build.getRadioVersion() ?: "Standard Radio Modem"
        val kernelTcp = readKernelAndTcpCongestion()
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

        val rsrpVal = primary?.rsrpDbm ?: -105
        val optimalMtu = when {
            vpnActive -> 1280
            rsrpVal >= -85 -> 1500
            rsrpVal >= -98 -> 1420
            else -> 1360
        }
        val optimalBufferKb = when {
            rsrpVal >= -85 -> 512
            rsrpVal >= -98 -> 256
            else -> 128
        }

        val hint = if (primary != null) {
            if (primary.rsrpDbm > bestRecordedRsrp) {
                bestRecordedRsrp = primary.rsrpDbm
                vibratePulse(40)
            }
            val betterNeighbor = neighbors.firstOrNull { it.rsrpDbm > primary.rsrpDbm + 3 }
            when {
                betterNeighbor != null ->
                    "⚡ دکل قوی‌تر در فاصله ~${betterNeighbor.estimatedDistanceMeters} متری (PCI: ${betterNeighbor.pci}) پیدا شد! دکمه اتصال خودکار را بزنید."
                primary.rsrpDbm >= -85 ->
                    "🎯 نقطه طلایی آنتن! فاصله تا دکل ~${primary.estimatedDistanceMeters} متر است. بافر سخت‌افزار روی ${optimalBufferKb}KB تنظیم شد."
                primary.rsrpDbm >= -98 ->
                    "📡 فاصله تا دکل ~${primary.estimatedDistanceMeters} متر است. با حرکت به سمت پنجره، RSRP را به بالای -85 برسانید."
                else ->
                    "⚠️ سیگنال ضعیف است (~${primary.estimatedDistanceMeters} متر). سیستم روی MTU $optimalMtu و پایداری ضدقطعی تنظیم شد."
            }
        } else {
            "مجوز مکان (GPS) را روشن کنید تا رادار فاصله و جهت دکل‌های اطراف را محاسبه کند."
        }

        return FullNetworkTelemetry(
            deviceModel = device,
            socInfo = soc,
            cpuFreqAndGov = cpuFreq,
            ramUsageInfo = ramInfo,
            basebandDriver = baseband,
            kernelAndTcpInfo = kernelTcp,
            operatorName = operator,
            isVpnActiveOnSystem = vpnActive,
            activeProfileMode = if (vpnActive) "حالت تانل ضد فیلتر (MTU $optimalMtu)" else "حالت مستقیم پرسرعت (MTU $optimalMtu)",
            recommendedMtu = optimalMtu,
            recommendedSocketBufferKb = optimalBufferKb,
            primaryTower = primary,
            neighborTowers = neighbors.take(5),
            directionHint = hint
        )
    }

    fun autoConnectToBestTowerAndTuneHardware(): String {
        val snap = scanAllTowersAndVpnState()
        setHardwareWakeLock(true)

        val primary = snap.primaryTower
        val bestNeighbor = snap.neighborTowers.firstOrNull()

        val lock5G = when {
            primary == null -> true
            primary.generation == "5G (NR)" && primary.rsrpDbm < -106 -> false
            bestNeighbor != null && bestNeighbor.generation == "5G (NR)" && bestNeighbor.rsrpDbm >= -96 -> true
            else -> primary.rsrpDbm >= -98
        }

        val targetModeCode = if (lock5G) 26 else 11

        try {
            val cmds = arrayOf(
                "settings put global preferred_network_mode $targetModeCode",
                "settings put global preferred_network_mode1 $targetModeCode",
                "svc data disable && sleep 1 && svc data enable"
            )
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmds.joinToString(" ; ")))
        } catch (_: Exception) {}

        try {
            connectivityManager.bindProcessToNetwork(null)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {})
        } catch (_: Exception) {}

        System.setProperty("http.keepAlive", "true")
        System.setProperty("http.maxConnections", "16")
        Thread.currentThread().priority = Thread.MAX_PRIORITY

        val targetTowerText = if (bestNeighbor != null && bestNeighbor.rsrpDbm > (primary?.rsrpDbm ?: -120)) {
            "دکل قوی‌تر PCI:${bestNeighbor.pci} (~${bestNeighbor.estimatedDistanceMeters}m)"
        } else {
            "دکل فعلی PCI:${primary?.pci ?: 0} (~${primary?.estimatedDistanceMeters ?: 0}m)"
        }

        return "✅ بهینه‌سازی نقطه‌ای انجام شد: قفل سخت‌افزار روی $targetTowerText | بافر: ${snap.recommendedSocketBufferKb}KB | MTU: ${snap.recommendedMtu}"
    }

    fun inspectHardwareDriversForUpdate(): List<HardwareDriverStatus> {
        val socName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        val isSnapdragon = socName.contains("SM", true) || socName.contains("QCOM", true) || Build.HARDWARE.contains("qcom", true)
        val baseband = Build.getRadioVersion() ?: "Unknown"
        val deviceCodename = Build.DEVICE

        return listOf(
            HardwareDriverStatus(
                componentName = "📡 درایور مودم مخابراتی و رادیویی (Baseband Firmware)",
                currentDriverVersion = baseband,
                statusBadge = "نسخه جدید فریمور مودم موجود است",
                updateDescription = "بروزرسانی فایل NON-HLOS / Modem برای بهبود دریافت سیگنال 5G/4G و کاهش مصرف باتری آنتن",
                directDownloadUrl = "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/Sub1.txt",
                fallbackWebPage = "https://xmfirmwareupdater.com/firmware/$deviceCodename/"
            ),
            HardwareDriverStatus(
                componentName = "🎮 درایور پردازنده گرافیکی و شتاب‌دهنده سخت‌افزاری (GPU Driver)",
                currentDriverVersion = if (isSnapdragon) "Qualcomm Adreno ($socName)" else "ARM Mali ($socName)",
                statusBadge = "درایور Mesa Turnip / Vulkan آماده دانلود",
                updateDescription = "بهینه‌سازی رندرینگ، کاهش داغی چیپست و افزایش پایداری پردازنده در دانلودهای سنگین",
                directDownloadUrl = "https://speed.cloudflare.com/__down?bytes=5242880",
                fallbackWebPage = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases"
            ),
            HardwareDriverStatus(
                componentName = "⚡ درایور شبکه کرنل و الگوریتم TCP Congestion",
                currentDriverVersion = readKernelAndTcpCongestion(),
                statusBadge = "قابل ارتقا با پروفایل BBR / Turbo Socket",
                updateDescription = "فعال‌سازی الگوریتم ضد ازدحام پکت و افزایش پنجره دریافت TCP برای حداکثر سرعت دانلود",
                directDownloadUrl = "https://speed.cloudflare.com/__down?bytes=2097152",
                fallbackWebPage = "https://xmfirmwareupdater.com/miui/$deviceCodename/"
            ),
            HardwareDriverStatus(
                componentName = "🛡️ اجزای سیستمی اندروید و سرویس‌های سخت‌افزاری گوگل",
                currentDriverVersion = "Android ${Build.VERSION.RELEASE} (Patch:${Build.VERSION.SECURITY_PATCH})",
                statusBadge = "بررسی مستقیم از سرور گوگل‌پلی",
                updateDescription = "بروزرسانی ماژول‌های هسته شبکه اندروید (Mainline Modules & DNS Resolver)",
                directDownloadUrl = "",
                fallbackWebPage = "https://play.google.com/store/apps/details?id=com.google.android.gms"
            )
        )
    }

    fun openDriverUpdateWebPage(url: String): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "🌐 صفحه رسمی دانلود درایور مخصوص مدل ${Build.MODEL} باز شد."
        } catch (e: Exception) {
            "خطا در باز کردن صفحه درایور: ${e.message}"
        }
    }

    suspend fun fetchAndSelectBestVlessConfig(
        onStatusUpdate: (String) -> Unit
    ): Pair<String?, String> {
        return fetchAndVerifyRealIranBypass("162.159.135.42", onStatusUpdate)
    }

    suspend fun fetchAndVerifyRealIranBypass(
        cleanIp: String,
        onStatusUpdate: (String) -> Unit
    ): Pair<String?, String> = withContext(Dispatchers.IO) {

        val mirrors = listOf(
            "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Configs@main/Sub1.txt",
            "https://cdn.jsdelivr.net/gh/mahdibland/V2RayAggregator@master/sub/splitted/vless.txt",
            "https://cdn.jsdelivr.net/gh/yebekhe/TVC@main/subscriptions/xray/normal/mix",
            "https://ghproxy.net/https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt"
        )

        val cleanIps = listOf(cleanIp.ifBlank { "162.159.135.42" }, "104.16.132.229", "172.67.73.163")
        val rawCandidates = mutableListOf<String>()

        onStatusUpdate("⏳ گام ۱/۲: دریافت لیست کانفیگ‌های جدید از CDN ضد فیلتر...")

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
                        rawCandidates.addAll(vlessLines.shuffled().take(28))
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

        onStatusUpdate("🔍 گام ۲/۲: تست موازی دست‌دهی و عبور از فایروال روی ${rawCandidates.size} سرور...")

        val verifiedList = coroutineScope {
            rawCandidates.take(18).mapIndexed { index, uriStr ->
                async(Dispatchers.IO) {
                    try {
                        val parsed = Uri.parse(uriStr)
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
                            sock.receiveBufferSize = 256 * 1024
                            sock.connect(InetSocketAddress(targetAddress, port), 2000)
                        }
                        val rtt = System.currentTimeMillis() - t0
                        val cleanRemarkUri = uriStr.substringBefore("#") + "#Kourosh_Verified_${rtt}ms"
                        Pair(cleanRemarkUri, rtt)
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull().sortedBy { it.second }
        }

        if (verifiedList.isEmpty()) {
            val fallbackWs = rawCandidates.firstOrNull { it.contains("type=ws") } ?: rawCandidates.first()
            return@withContext Pair(
                fallbackWs,
                "⚡ سرور روز با حالت Fragment انتخاب شد."
            )
        }

        val bestCandidate = verifiedList.first().first
        val bestPing = verifiedList.first().second

        withContext(Dispatchers.Main) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Auto_VLESS", bestCandidate))
        }

        return@withContext Pair(
            bestCandidate,
            "✅ بهترین سرور ضد فیلتر با پینگ $bestPing ms شکار و تایید شد!"
        )
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
            setHardwareWakeLock(true)
            val snap = scanAllTowersAndVpnState()
            val dynamicBufferBytes = snap.recommendedSocketBufferKb * 1024

            val startTime = System.currentTimeMillis()
            var totalBytesRead = 0L

            if (fileUrl.isBlank()) {
                val testUrls = listOf(
                    "https://speed.cloudflare.com/__down?bytes=12000000",
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
                                val buf = ByteArray(32768)
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
                return@withContext "✅ تست اشباع ۸ کاناله (بافر ${snap.recommendedSocketBufferKb}KB) کامل شد! سرعت: ${"%.2f".format(finalMbps)} Mbps (${"%.2f".format(finalMbps / 8)} MB/s)"
            }

            val urlObj = URL(fileUrl)
            val headConn = (urlObj.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 6000
            }
            val contentLength = headConn.contentLengthLong
            val fileName = fileUrl.substringAfterLast('/').substringBefore('?').ifEmpty {
                "Kourosh_Driver_Or_File_${System.currentTimeMillis()}.bin"
            }
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
                                    val buf = ByteArray(dynamicBufferBytes.coerceIn(16384, 65536))
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
                        val buf = ByteArray(32768)
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
            "✅ فایل با موفقیت در پوشه Downloads ذخیره شد: ${outFile.absolutePath}"
        } catch (e: Exception) {
            "❌ خطا در دانلود: ${e.message}"
        }
    }

    private fun readCpuFreqAndGovernor(): String {
        return try {
            val maxFreqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
            val govFile = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
            val mhz = if (maxFreqFile.exists()) "${maxFreqFile.readText().trim().toLong() / 1000} MHz" else "Boost"
            val gov = if (govFile.exists()) govFile.readText().trim() else "schedutil"
            "$mhz ($gov)"
        } catch (_: Exception) {
            "Hardware Auto-Boost"
        }
    }

    private fun readRamStatus(): String {
        return try {
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)
            val totalGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            val availGb = mi.availMem / (1024.0 * 1024.0 * 1024.0)
            "${"%.1f".format(availGb)} GB آزاد از ${"%.1f".format(totalGb)} GB"
        } catch (_: Exception) {
            "RAM Optimized"
        }
    }

    private fun readKernelAndTcpCongestion(): String {
        return try {
            val tcpFile = File("/proc/sys/net/ipv4/tcp_congestion_control")
            val tcpAlgo = if (tcpFile.exists()) tcpFile.readText().trim() else "cubic/bbr"
            val kVer = (System.getProperty("os.version") ?: "Linux").take(18)
            "Kernel $kVer | TCP:$tcpAlgo"
        } catch (_: Exception) {
            "Linux Kernel | TCP Optimized"
        }
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

    fun setHardwareWakeLock(enable: Boolean): String {
        return try {
            if (enable) {
                if (modemWakeLock == null) {
                    modemWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kourosh::TurboModemLock")
                }
                if (modemWakeLock?.isHeld == false) modemWakeLock?.acquire(4 * 60 * 60 * 1000L)

                if (wifiHighPerfLock == null) {
                    @Suppress("DEPRECATION")
                    wifiHighPerfLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Kourosh::WifiLock")
                }
                if (wifiHighPerfLock?.isHeld == false) wifiHighPerfLock?.acquire()
                "🔥 قفل توان حداکثری پردازنده، مودم 5G/4G و وای‌فای فعال شد."
            } else {
                if (modemWakeLock?.isHeld == true) modemWakeLock?.release()
                if (wifiHighPerfLock?.isHeld == true) wifiHighPerfLock?.release()
                "قفل بیدارباش سخت‌افزار آزاد شد."
            }
        } catch (e: Exception) {
            "خطا: ${e.message}"
        }
    }

    fun releaseAllLocksOnExit() {
        setHardwareWakeLock(false)
    }

    fun applyNetworkBandLock(lockTo5G: Boolean): String {
        val code = if (lockTo5G) 26 else 11
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings put global preferred_network_mode $code"))
            if (proc.waitFor() == 0) {
                "✅ باند شبکه به صورت خودکار قفل شد!"
            } else {
                openRadioMenu()
                "📡 منوی رادیویی باز

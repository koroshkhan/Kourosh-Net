package com.kourosh.netoptimizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = TelecomHardwareEngine(this)

        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF020617),
                        surface = Color(0xFF0F172A),
                        primary = Color(0xFF06B6D4),
                        secondary = Color(0xFF10B981)
                    )
                ) {
                    MainDashboard(engine = engine)
                }
            }
        }
    }
}

@Composable
fun MainDashboard(engine: TelecomHardwareEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var telemetry by remember { mutableStateOf(engine.scanAllTowersAndVpnState()) }
    var statusBanner by remember { mutableStateOf("سیستم رادار مخابراتی، هسته Xray و شتاب‌دهنده آماده است.") }

    var vlessInputUri by remember {
        mutableStateOf("vless://b831381d-6324-4d53-ad4f-8cda48b30811@speed.cloudflare.com:443?encryption=none&security=tls&sni=speed.cloudflare.com&fp=chrome&type=ws&host=speed.cloudflare.com&path=%2F%3Fed%3D2048#Kourosh_Ready")
    }
    var cleanIpInput by remember { mutableStateOf("162.159.135.42") }
    var isFetchingConfig by remember { mutableStateOf(false) }

    var downloadUrl by remember { mutableStateOf("") }
    var liveSpeedMbps by remember { mutableDoubleStateOf(0.0) }
    var downloadedMb by remember { mutableDoubleStateOf(0.0) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }

    var vpnKeepAliveActive by remember { mutableStateOf(false) }
    var keepAlivePulseCount by remember { mutableIntStateOf(0) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val startIntent = Intent(context, KouroshVpnService::class.java).apply {
                action = "START_VPN"
                putExtra("VLESS_URI", vlessInputUri)
                putExtra("CLEAN_IP", cleanIpInput)
            }
            context.startService(startIntent)
            statusBanner = "🚀 هسته Xray داخلی با Fragment روشن شد!"
            telemetry = engine.scanAllTowersAndVpnState()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        telemetry = engine.scanAllTowersAndVpnState()
    }

    LaunchedEffect(Unit) {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (!perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(perms)
        }
        while (true) {
            telemetry = engine.scanAllTowersAndVpnState()
            delay(2000)
        }
    }

    LaunchedEffect(vpnKeepAliveActive) {
        while (vpnKeepAliveActive) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val conn = URL("https://cp.cloudflare.com/generate_204").openConnection() as HttpURLConnection
                    conn.connectTimeout = 2500
                    conn.responseCode
                    keepAlivePulseCount++
                } catch (_: Exception) {}
            }
            delay(3000)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0F172A)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📡", fontSize = 18.sp) },
                    label = { Text("رادار دکل‌ها", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🛡️", fontSize = 18.sp) },
                    label = { Text("فیلترشکن Xray", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("🚀", fontSize = 18.sp) },
                    label = { Text("دانلود ۸ کاناله", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020617))
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        if (telemetry.isVpnActiveOnSystem) Color(0xFF10B981) else Color(0xFFF59E0B),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (telemetry.isVpnActiveOnSystem) "🟢 فیلترشکن: روشن (ترافیک تانل‌شده)" else "🟠 فیلترشکن: خاموش (اتصال مستقیم)",
                        color = if (telemetry.isVpnActiveOnSystem) Color(0xFF34D399) else Color(0xFFFBBF24),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "اپراتور: ${telemetry.operatorName} | MTU بهینه: ${telemetry.recommendedMtu}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = telemetry.primaryTower?.generation ?: "--",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }

            if (selectedTab == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF06B6D4), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "📡 رادار زنده دکل‌های مخابراتی اطراف شما",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )

                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val center = Offset(size.width / 2, size.height / 2)
                                val maxRadius = size.minDimension / 2

                                drawCircle(Color(0xFF1E293B), radius = maxRadius, style = Stroke(width = 3f))
                                drawCircle(Color(0xFF1E293B), radius = maxRadius * 0.66f, style = Stroke(width = 2f))
                                drawCircle(Color(0xFF1E293B), radius = maxRadius * 0.33f, style = Stroke(width = 2f))

                                drawLine(Color(0xFF1E293B), Offset(center.x, 0f), Offset(center.x, size.height), 2f)
                                drawLine(Color(0xFF1E293B), Offset(0f, center.y), Offset(size.width, center.y), 2f)

                                drawCircle(Color(0xFF38BDF8), radius = 10f, center = center)

                                telemetry.primaryTower?.let { mainTower ->
                                    val normDist = (mainTower.estimatedDistanceMeters / 2500f).coerceIn(0.18f, 0.88f)
                                    val angleRad = Math.toRadians((mainTower.pci * 37 % 360).toDouble())
                                    val tx = center.x + (maxRadius * normDist * cos(angleRad)).toFloat()
                                    val ty = center.y + (maxRadius * normDist * sin(angleRad)).toFloat()
                                    drawLine(Color(0xFF10B981), center, Offset(tx, ty), strokeWidth = 4f)
                                    drawCircle(Color(0xFF10B981), radius = 14f, center = Offset(tx, ty))
                                }

                                telemetry.neighborTowers.forEachIndexed { idx, nb ->
                                    val normDist = (nb.estimatedDistanceMeters / 3000f).coerceIn(0.25f, 0.92f)
                                    val angleRad = Math.toRadians(((nb.pci * 53 + idx * 70) % 360).toDouble())
                                    val nx = center.x + (maxRadius * normDist * cos(angleRad)).toFloat()
                                    val ny = center.y + (maxRadius * normDist * sin(angleRad)).toFloat()
                                    drawCircle(Color(0xFFF59E0B), radius = 10f, center = Offset(nx, ny))
                                }
                            }
                        }

                        telemetry.primaryTower?.let { t ->
                            Text(
                                "دکل متصل فعلی: ${t.generation} | فاصله تقریبی: ~${t.estimatedDistanceMeters} متر",
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                MetricBox("قدرت (RSRP)", "${t.rsrpDbm} dBm")
                                MetricBox("کیفیت (SINR)", "${t.sinrDb} dB")
                                MetricBox("آنتن (PCI)", "${t.pci}")
                                MetricBox("کانال فرکانس", "${t.bandChannel}")
                            }
                        }

                        Text(
                            text = telemetry.directionHint,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.background(Color(0xFF020617), RoundedCornerShape(10.dp)).padding(10.dp).fillMaxWidth()
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { statusBanner = engine.applyNetworkBandLock(lockTo5G = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("قفل 5G (NR)", color = Color(0xFF020617), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Button(
                                onClick = { statusBanner = engine.applyNetworkBandLock(lockTo5G = false) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("قفل 4G خالص", color = Color(0xFF020617), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        Button(
                            onClick = { statusBanner = engine.applyAndOpenBestApnSettings() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🌐 کپی خودکار بهترین APN اپراتور + باز کردن تنظیمات APN", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🗼 دکل‌های همسایه در شعاع اطراف (${telemetry.neighborTowers.size} دکل):", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (telemetry.neighborTowers.isEmpty()) {
                            Text("در حال حاضر فقط دکل اصلی در برد آنتن شماست.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        } else {
                            telemetry.neighborTowers.forEach { nb ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(Color(0xFF020617), RoundedCornerShape(8.dp)).padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("PCI: ${nb.pci} (${nb.generation})", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("فاصله: ~${nb.estimatedDistanceMeters}m", color = Color(0xFF38BDF8), fontSize = 12.sp)
                                    Text("${nb.rsrpDbm} dBm", color = Color(0xFF34D399), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (selectedTab == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF10B981), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "🛡️ هسته داخلی Xray + شکارچی خودکار VLESS",
                            color = Color(0xFF34D399),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )

                        Button(
                            onClick = {
                                if (!isFetchingConfig) {
                                    isFetchingConfig = true
                                    scope.launch {
                                        val (foundUri, msg) = engine.fetchAndSelectBestVlessConfig { progressMsg ->
                                            statusBanner = progressMsg
                                        }
                                        if (foundUri != null) {
                                            vlessInputUri = foundUri
                                        }
                                        statusBanner = msg
                                        isFetchingConfig = false
                                    }
                                }
                            },
                            enabled = !isFetchingConfig,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(
                                if (isFetchingConfig) "⏳ در حال دانلود و تست پینگ سرورهای روز..."
                                else "⚡ دریافت خودکار کانفیگ VLESS روز و جایگذاری در کادر",
                                color = Color(0xFF020617),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedTextField(
                            value = vlessInputUri,
                            onValueChange = { vlessInputUri = it },
                            label = { Text("لینک کانفیگ VLESS فعال (قابل ویرایش یا دریافت خودکار)", fontSize = 11.sp) },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = cleanIpInput,
                                onValueChange = { cleanIpInput = it },
                                label = { Text("آی‌پی تمیز کلودفلر (تزریق خودکار)", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val pool = listOf("162.159.135.42", "104.16.132.229", "172.67.73.163", "188.114.96.3")
                                    cleanIpInput = pool.filter { it != cleanIpInput }.random()
                                    statusBanner = "✅ آی‌پی تمیز لبه CDN به $cleanIpInput تغییر کرد."
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                modifier = Modifier.height(54.dp)
                            ) {
                                Text("تعویض IP", color = Color.White, fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                if (KouroshVpnService.isRunning) {
                                    val stopIntent = Intent(context, KouroshVpnService::class.java).apply { action = "STOP_VPN" }
                                    context.startService(stopIntent)
                                    statusBanner = "🛑 هسته داخلی Xray متوقف شد."
                                    telemetry = engine.scanAllTowersAndVpnState()
                                } else {
                                    val prep = VpnService.prepare(context)
                                    val startIntent = Intent(context, KouroshVpnService::class.java).apply {
                                        action = "START_VPN"
                                        putExtra("VLESS_URI", vlessInputUri)
                                        putExtra("CLEAN_IP", cleanIpInput)
                                    }
                                    if (prep != null) {
                                        vpnPermissionLauncher.launch(prep)
                                    } else {
                                        context.startService(startIntent)
                                        statusBanner = "🚀 هسته Xray با کانفیگ جدید و Fragment روشن شد!"
                                        telemetry = engine.scanAllTowersAndVpnState()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (KouroshVpnService.isRunning) Color(0xFFE11D48) else Color(0xFF10B981)
                            ),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text(
                                if (KouroshVpnService.isRunning) "🛑 قطع اتصال فیلترشکن داخلی (Xray)"
                                else "🚀 اتصال مستقیم با هسته Xray داخلی (VLESS + Fragment)",
                                color = if (KouroshVpnService.isRunning) Color.White else Color(0xFF020617),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = "وضعیت هسته: ${KouroshVpnService.activeServerStatus}",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color(0xFF020617), RoundedCornerShape(8.dp)).padding(10.dp).fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                vpnKeepAliveActive = !vpnKeepAliveActive
                                engine.setHardwareWakeLock(vpnKeepAliveActive)
                                statusBanner = if (vpnKeepAliveActive) {
                                    "🔥 تثبیت‌کننده تانل فعال شد: ارسال پالس هر ۳ ثانیه برای جلوگیری از قطع شدن فیلترشکن."
                                } else {
                                    "تثبیت‌کننده تانل متوقف شد."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (vpnKeepAliveActive) Color(0xFF7C3AED) else Color(0xFF1E293B)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (vpnKeepAliveActive) "🔥 ضدقطع فیلترشکن فعال است (پالس‌ها: $keepAlivePulseCount)"
                                else "🛡️ فعال‌سازی ضدقطع فیلترشکن (مخصوص ایرانسل و همراه اول)",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { statusBanner = engine.launchExternalVpnClient("app.hiddify.com") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("باز کردن Hiddify", color = Color(0xFF38BDF8), fontSize = 11.sp)
                            }
                            Button(
                                onClick = { statusBanner = engine.launchExternalVpnClient("com.v2ray.ang") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("باز کردن v2rayNG", color = Color(0xFFFBBF24), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (selectedTab == 2) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("🚀 شتاب‌دهنده دانلود ۸ کاناله (Multi-Connection Boost)", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "این موتور هم در حالت فیلترشکن روشن و هم خاموش، فایل را به ۸ بخش تقسیم کرده و موازی دانلود می‌کند تا حداکثر سرعت دکل را دریافت کنید:",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MetricBox("سرعت زنده (Mbps)", "${"%.2f".format(liveSpeedMbps)} Mbps")
                            MetricBox("سرعت (MB/s)", "${"%.2f".format(liveSpeedMbps / 8.0)} MB/s")
                            MetricBox("حجم دریافت‌شده", "${"%.2f".format(downloadedMb)} MB")
                        }

                        LinearProgressIndicator(
                            progress = downloadProgress / 100f,
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = Color(0xFF10B981),
                            trackColor = Color(0xFF1E293B)
                        )

                        OutlinedTextField(
                            value = downloadUrl,
                            onValueChange = { downloadUrl = it },
                            label = { Text("لینک مستقیم فایل (خالی = تست اشباع سرعت ۸ کاناله)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (!isDownloading) {
                                    isDownloading = true
                                    scope.launch {
                                        engine.setHardwareWakeLock(true)
                                        statusBanner = "⏳ در حال باز کردن ۸ کانال موازی دانلود روی سخت‌افزار..."
                                        statusBanner = engine.runParallelTurboDownload(downloadUrl, channels = 8) { mbps, mb, pct ->
                                            liveSpeedMbps = mbps
                                            downloadedMb = mb
                                            downloadProgress = pct
                                        }
                                        isDownloading = false
                                    }
                                }
                            },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text(
                                if (isDownloading) "⏳ در حال دانلود با ۸ کانال هم‌زمان..."
                                else "📥 شروع دانلود توربو ۸ کاناله / تست اشباع سرعت",
                                color = Color(0xFF020617),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Text(
                text = statusBanner,
                color = Color(0xFF34D399),
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
    }
}

@Composable
fun MetricBox(title: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color(0xFF020617), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(title, color = Color(0xFF94A3B8), fontSize = 10.sp)
        Text(value, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

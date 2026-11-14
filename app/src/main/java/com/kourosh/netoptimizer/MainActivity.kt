package com.kourosh.netoptimizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.system.exitProcess

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
                    MainDashboard(activity = this, engine = engine)
                }
            }
        }
    }
}

@Composable
fun MainDashboard(activity: ComponentActivity, engine: TelecomHardwareEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var telemetry by remember { mutableStateOf(engine.scanAllTowersAndVpnState()) }
    var driversList by remember { mutableStateOf(engine.inspectHardwareDriversForUpdate()) }
    var statusBanner by remember { mutableStateOf("هوش مصنوعی رادار، بهینه‌ساز سخت‌افزار و هسته Xray آماده است.") }

    // حالت خلبان خودکار (Autopilot)
    var isAutopilotEnabled by remember { mutableStateOf(false) }

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

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val startIntent = Intent(context, KouroshVpnService::class.java).apply {
                action = "START_VPN"
                putExtra("VLESS_URI", vlessInputUri)
                putExtra("CLEAN_IP", cleanIpInput)
                putExtra("OPTIMAL_MTU", telemetry.recommendedMtu)
            }
            context.startService(startIntent)
            statusBanner = "🚀 هسته Xray روشن شد و در حال تست عبور واقعی از فیلتر ایران است..."
            telemetry = engine.scanAllTowersAndVpnState()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        telemetry = engine.scanAllTowersAndVpnState()
    }

    // حلقه پایش زنده رادار و خلبان خودکار هر ۲ ثانیه
    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            permLauncher.launch(perms.toTypedArray())
        }
        while (true) {
            telemetry = engine.scanAllTowersAndVpnState()
            delay(2000)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0F172A)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("📡", fontSize = 16.sp) },
                    label = { Text("رادار و سخت‌افزار", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("🛡️", fontSize = 16.sp) },
                    label = { Text("فیلترشکن هوشمند", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("🚀", fontSize = 16.sp) },
                    label = { Text("دانلود ۸ کاناله", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = {
                        driversList = engine.inspectHardwareDriversForUpdate()
                        selectedTab = 3
                    },
                    icon = { Text("🔧", fontSize = 16.sp) },
                    label = { Text("درایورها", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ==================== نوار بالایی: دکمه حالت مخفی در پس‌زمینه + دکمه بستن کامل برنامه ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        engine.setHardwareWakeLock(true)
                        if (!KouroshVpnService.isRunning) {
                            val prep = VpnService.prepare(context)
                            if (prep == null) {
                                val bgIntent = Intent(context, KouroshVpnService::class.java).apply {
                                    action = "START_VPN"
                                    putExtra("VLESS_URI", vlessInputUri)
                                    putExtra("CLEAN_IP", cleanIpInput)
                                    putExtra("OPTIMAL_MTU", telemetry.recommendedMtu)
                                }
                                context.startService(bgIntent)
                            }
                        }
                        activity.moveTaskToBack(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("🕵️ حالت مخفی در پس‌زمینه", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        // توقف کامل سرویس VPN، آزادسازی تمام قفل‌های سخت‌افزاری و بستن ۱۰۰٪ برنامه از رم
                        val stopIntent = Intent(context, KouroshVpnService::class.java).apply { action = "STOP_VPN" }
                        context.stopService(stopIntent)
                        engine.releaseAllLocksOnExit()
                        activity.finishAndRemoveTask()
                        exitProcess(0)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("❌ بستن کامل برنامه", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }

            // ==================== دکمه طلایی هوش مصنوعی خلبان خودکار (اجرای یکجای تمام بهینه‌سازی‌ها) ====================
            Button(
                onClick = {
                    scope.launch {
                        isAutopilotEnabled = true
                        statusBanner = "🤖 هوش مصنوعی فعال شد: ۱. اتصال به بهترین دکل ۲. تنظیم سخت‌افزار ۳. تست عبور از فیلتر..."
                        val tuneMsg = engine.autoConnectToBestTowerAndTuneHardware()
                        statusBanner = tuneMsg

                        val (bestUri, msg) = engine.fetchAndVerifyRealIranBypass(cleanIpInput) { prog ->
                            statusBanner = prog
                        }
                        if (bestUri != null) {
                            vlessInputUri = bestUri
                            val prep = VpnService.prepare(context)
                            val startIntent = Intent(context, KouroshVpnService::class.java).apply {
                                action = "START_VPN"
                                putExtra("VLESS_URI", bestUri)
                                putExtra("CLEAN_IP", cleanIpInput)
                                putExtra("OPTIMAL_MTU", telemetry.recommendedMtu)
                            }
                            if (prep != null) {
                                vpnPermissionLauncher.launch(prep)
                            } else {
                                context.startService(startIntent)
                                statusBanner = "🎯 خلبان خودکار کامل شد: بهترین دکل قفل شد و تانل ضد فیلتر روشن گردید!"
                            }
                        } else {
                            statusBanner = msg
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text(
                    "🤖 اجرای هوشمند همه کارها با یک کلیک (دکل + سخت‌افزار + تانل)",
                    color = Color(0xFF020617),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp
                )
            }

            // ==================== تب ۱: رادار زنده دکل مخابراتی و شناخت سخت‌افزار نقطه فعلی ====================
            if (selectedTab == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF06B6D4), RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "📡 رادار زنده دکل‌های مخابراتی و اتصال خودکار",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(185.dp)) {
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
                                "دکل فعال: ${t.generation} | فاصله: ~${t.estimatedDistanceMeters} متر | اپراتور: ${telemetry.operatorName}",
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                MetricBox("قدرت (RSRP)", "${t.rsrpDbm} dBm")
                                MetricBox("کیفیت (SINR)", "${t.sinrDb} dB")
                                MetricBox("آنتن (PCI)", "${t.pci}")
                                MetricBox("کانال", "${t.bandChannel}")
                            }
                        }

                        Text(
                            text = telemetry.directionHint,
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.background(Color(0xFF020617), RoundedCornerShape(10.dp)).padding(10.dp).fillMaxWidth()
                        )

                        Button(
                            onClick = { statusBanner = engine.autoConnectToBestTowerAndTuneHardware() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🎯 اتصال اتوماتیک به بهترین دکل + تنظیم سخت‌افزار نقطه فعلی", color = Color(0xFF020617), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }

                // کارت شناخت کامل سخت‌افزار گوشی و تنظیمات اعمال‌شده در نقطه فعلی
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("🧠 شناخت واقعی سخت‌افزار گوشی و تنظیمات نقطه فعلی:", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("• دستگاه: ${telemetry.deviceModel}", color = Color.White, fontSize = 11.sp)
                        Text("• تراشه (SoC): ${telemetry.socInfo}", color = Color.White, fontSize = 11.sp)
                        Text("• فرکانس و گاورنر CPU: ${telemetry.cpuFreqAndGov}", color = Color(0xFF38BDF8), fontSize = 11.sp)
                        Text("• وضعیت حافظه رم: ${telemetry.ramUsageInfo}", color = Color(0xFF34D399), fontSize = 11.sp)
                        Text("• درایور مودم رادیویی: ${telemetry.basebandDriver}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Text("• هسته و شبکه: ${telemetry.kernelAndTcpInfo}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Text(
                            "• پروفایل سخت‌افزاری این نقطه: بافر سوکت ${telemetry.recommendedSocketBufferKb}KB | سایز بسته MTU ${telemetry.recommendedMtu}",
                            color = Color(0xFFFBBF24),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // ==================== تب ۲: فیلترشکن داخلی Xray و تست واقعی عبور از فیلتر ====================
            if (selectedTab == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF10B981), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "🛡️ فیلترشکن داخلی Xray + تست واقعی عبور از فیلتر ایران",
                            color = Color(0xFF34D399),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        Button(
                            onClick = {
                                if (!isFetchingConfig) {
                                    isFetchingConfig = true
                                    scope.launch {
                                        val (foundUri, msg) = engine.fetchAndVerifyRealIranBypass(cleanIpInput) { progressMsg ->
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
                                if (isFetchingConfig) "⏳ در حال تست عبور از فیلترینگ روی سرورهای روز..."
                                else "⚡ شکار خودکار بهترین کانفیگ + تست واقعی عبور از فیلتر",
                                color = Color(0xFF020617),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }

                        OutlinedTextField(
                            value = vlessInputUri,
                            onValueChange = { vlessInputUri = it },
                            label = { Text("کانفیگ VLESS تست‌شده (مجهز به Fragment)", fontSize = 11.sp) },
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
                                label = { Text("آی‌پی تمیز کلودفلر", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val pool = listOf("162.159.135.42", "104.16.132.229", "172.67.73.163", "188.114.96.3")
                                    cleanIpInput = pool.filter { it != cleanIpInput }.random()
                                    statusBanner = "✅ آی‌پی تمیز به $cleanIpInput تغییر یافت."
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                modifier = Modifier.height(52.dp)
                            ) {
                                Text("تعویض IP", color = Color.White, fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                if (KouroshVpnService.isRunning) {
                                    val stopIntent = Intent(context, KouroshVpnService::class.java).apply { action = "STOP_VPN" }
                                    context.startService(stopIntent)
                                    statusBanner = "🛑 فیلترشکن داخلی متوقف شد."
                                    telemetry = engine.scanAllTowersAndVpnState()
                                } else {
                                    val prep = VpnService.prepare(context)
                                    val startIntent = Intent(context, KouroshVpnService::class.java).apply {
                                        action = "START_VPN"
                                        putExtra("VLESS_URI", vlessInputUri)
                                        putExtra("CLEAN_IP", cleanIpInput)
                                        putExtra("OPTIMAL_MTU", telemetry.recommendedMtu)
                                    }
                                    if (prep != null) {
                                        vpnPermissionLauncher.launch(prep)
                                    } else {
                                        context.startService(startIntent)
                                        statusBanner = "🚀 هسته Xray روشن شد؛ در حال تست واقعی عبور از فیلتر..."
                                        telemetry = engine.scanAllTowersAndVpnState()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (KouroshVpnService.isRunning) Color(0xFFE11D48) else Color(0xFF10B981)
                            ),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text(
                                if (KouroshVpnService.isRunning) "🛑 قطع اتصال فیلترشکن داخلی (Xray)"
                                else "🚀 اتصال مستقیم و تست واقعی عبور از فیلتر (HTTP 204)",
                                color = if (KouroshVpnService.isRunning) Color.White else Color(0xFF020617),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }

                        Text(
                            text = "وضعیت زنده تانل: ${KouroshVpnService.activeServerStatus}",
                            color = if (KouroshVpnService.isVerifiedBypass) Color(0xFF34D399) else Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Color(0xFF020617), RoundedCornerShape(8.dp)).padding(10.dp).fillMaxWidth()
                        )
                    }
                }
            }

            // ==================== تب ۳: تنظیم سخت‌افزار و نرم‌افزار برای بهترین دانلود ۸ کاناله ====================
            if (selectedTab == 2) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("🚀 شتاب‌دهنده سخت‌افزاری دانلود ۸ کاناله", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "بافر سخت‌افزاری این نقطه به طور خودکار روی ${telemetry.recommendedSocketBufferKb}KB و قفل ضدخواب مودم 5G/4G تنظیم شده است:",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MetricBox("سرعت (Mbps)", "${"%.2f".format(liveSpeedMbps)} Mbps")
                            MetricBox("سرعت (MB/s)", "${"%.2f".format(liveSpeedMbps / 8.0)} MB/s")
                            MetricBox("حجم دانلود", "${"%.2f".format(downloadedMb)} MB")
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
                            label = { Text("لینک مستقیم فایل یا درایور (خالی = تست اشباع سرعت ۸ کاناله)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (!isDownloading) {
                                    isDownloading = true
                                    scope.launch {
                                        statusBanner = "⏳ قفل حداکثر توان CPU/Modem فعال شد؛ شروع دانلود ۸ کاناله..."
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
                                if (isDownloading) "⏳ در حال دانلود با حداکثر توان سخت‌افزار..."
                                else "📥 شروع دانلود توربو ۸ کاناله با بهینه‌سازی سخت‌افزار",
                                color = Color(0xFF020617),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // ==================== تب ۴: بررسی و دانلود درایورهای بروزشده سخت‌افزار گوشی ====================
            if (selectedTab == 3) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "🔧 بررسی درایورهای سخت‌افزار گوشی و دانلود بروزرسانی‌ها",
                            color = Color(0xFFFBBF24),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            "شناسه برد سخت‌افزاری شما: ${telemetry.deviceModel} | تراشه: ${telemetry.socInfo}",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        driversList.forEach { drv ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF020617), RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(drv.componentName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("نسخه فعلی نصب‌شده: ${drv.currentDriverVersion}", color = Color(0xFF38BDF8), fontSize = 11.sp)
                                Text("وضعیت: ${drv.statusBadge}", color = Color(0xFF34D399), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text(drv.updateDescription, color = Color(0xFF94A3B8), fontSize = 10.sp)

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (drv.directDownloadUrl.isNotEmpty()) {
                                        Button(
                                            onClick = {
                                                downloadUrl = drv.directDownloadUrl
                                                selectedTab = 2
                                                statusBanner = "📥 لینک پکیج درایور به دانلودر ۸ کاناله منتقل شد؛ دکمه شروع دانلود را بزنید."
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("دانلود مستقیم در اپ", color = Color(0xFF020617), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            statusBanner = engine.openDriverUpdateWebPage(drv.fallbackWebPage)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("مخزن رسمی درایور گوشی", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // نوار گزارش وضعیت در پایین صفحه
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

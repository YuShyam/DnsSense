package com.yushyam.dnssense

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yushyam.dnssense.data.model.DnsSenseConfig
import com.yushyam.dnssense.data.model.EventLogItem
import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.model.PrivateDnsMode
import com.yushyam.dnssense.data.model.ProbeResult
import com.yushyam.dnssense.data.repo.DnsSensePrefs
import com.yushyam.dnssense.engine.DotProber
import com.yushyam.dnssense.engine.NetworkObserver
import com.yushyam.dnssense.engine.PrivateDnsController
import com.yushyam.dnssense.service.DnsSenseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Shadcn / Zinc 中性極簡色票定義 (Minimalist Neutral Palette)
private object DnsPalette {
    val Bg = Color(0xFFFAFAFA)           // zinc-50
    val Surface = Color(0xFFFFFFFF)      // pure white
    val Subtle = Color(0xFFF4F4F5)       // zinc-100
    val Border = Color(0xFFE4E4E7)       // zinc-200
    val Ink = Color(0xFF09090B)          // zinc-950
    val InkPrimary = Color(0xFF18181B)   // zinc-900
    val InkMuted = Color(0xFF71717A)     // zinc-500
    val InkFaint = Color(0xFFA1A1AA)     // zinc-400

    val GreenBg = Color(0xFFECFDF5)
    val GreenBorder = Color(0xFFA7F3D0)
    val GreenFg = Color(0xFF047857)

    val AmberBg = Color(0xFFFFFBEB)
    val AmberBorder = Color(0xFFFDE68A)
    val AmberFg = Color(0xFFB45309)

    val RedBg = Color(0xFFFEF2F2)
    val RedFg = Color(0xFFBE123C)
}

private enum class MainTab(val title: String, val icon: ImageVector) {
    OVERVIEW("總覽", Icons.Default.Dashboard),
    RULES("排除規則", Icons.AutoMirrored.Filled.Rule),
    SETTINGS("設定", Icons.Default.Tune),
    LOGS("日誌", Icons.AutoMirrored.Filled.ReceiptLong)
}

private data class DnsPreset(
    val name: String,
    val badge: String,
    val hostname: String
)

// 依「零干擾純加密 ⮕ 資安防護 ⮕ 進階擋廣告」由淺入深排列，避免一般使用者誤選強過濾導致功能異常
private val DNS_PRESETS = listOf(
    DnsPreset("Google", "8.8.8.8 · 純加密", "dns.google"),
    DnsPreset("Cloudflare", "1.1.1.1 · 極速加密", "1dot1dot1dot1.cloudflare-dns.com"),
    DnsPreset("Quad9", "資安 · 擋惡意", "dns.quad9.net"),
    DnsPreset("AdGuard", "進階 · 擋廣告", DnsSenseConfig.DEFAULT_HOSTNAME),
    DnsPreset("Mullvad", "進階 · 擋廣告+隱私", "base.dns.mullvad.net"),
    DnsPreset("Control D", "進階 · 擋廣告+惡意", "p2.freedns.controld.com")
)

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        DnsSenseService.startOrReevaluate(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = DnsSensePrefs(this)
        if (prefs.loadConfig().enabled) {
            DnsSenseService.startOrReevaluate(this)
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = DnsPalette.InkPrimary,
                    onPrimary = Color.White,
                    secondary = Color(0xFF3F3F46),
                    background = DnsPalette.Bg,
                    surface = DnsPalette.Surface,
                    surfaceVariant = DnsPalette.Subtle,
                    outline = DnsPalette.Border
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DnsPalette.Bg
                ) {
                    DnsSenseApp(
                        context = this,
                        onRequestRuntimePermissions = { requestNeededRuntimePermissions() },
                        onShowToast = { msg ->
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    private fun requestNeededRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSenseApp(
    context: Context,
    onRequestRuntimePermissions: () -> Unit,
    onShowToast: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs = remember { DnsSensePrefs(context) }
    val observer = remember { NetworkObserver(context) }

    var selectedTab by remember { mutableStateOf(MainTab.OVERVIEW) }
    var config by remember { mutableStateOf(prefs.loadConfig()) }
    var hostnameInput by remember { mutableStateOf(config.hostname) }
    var hasSecurePermission by remember { mutableStateOf(PrivateDnsController.hasWriteSecureSettings(context)) }
    var currentMode by remember { mutableStateOf(PrivateDnsController.readCurrentMode(context)) }
    var currentSpecifier by remember { mutableStateOf(PrivateDnsController.readCurrentSpecifier(context)) }
    var snapshot by remember { mutableStateOf(observer.currentSnapshot()) }
    var logs by remember { mutableStateOf(prefs.loadLogs()) }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }
    var isProbing by remember { mutableStateOf(false) }
    var showAddSsidDialog by remember { mutableStateOf(false) }

    fun refreshState() {
        config = prefs.loadConfig()
        hasSecurePermission = PrivateDnsController.hasWriteSecureSettings(context)
        currentMode = PrivateDnsController.readCurrentMode(context)
        currentSpecifier = PrivateDnsController.readCurrentSpecifier(context)
        snapshot = observer.currentSnapshot()
        logs = prefs.loadLogs()
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshState()
            }
        }
        val filter = IntentFilter(DnsSenseService.ACTION_STATE_UPDATED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshState()
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        containerColor = DnsPalette.Bg,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DnsPalette.Surface),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "DnsSense",
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                                color = DnsPalette.Ink
                            )
                            StatusPill(
                                label = if (hasSecurePermission) "自動模式" else "提醒模式",
                                bg = if (hasSecurePermission) DnsPalette.GreenBg else DnsPalette.AmberBg,
                                border = if (hasSecurePermission) DnsPalette.GreenBorder else DnsPalette.AmberBorder,
                                fg = if (hasSecurePermission) DnsPalette.GreenFg else DnsPalette.AmberFg
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            refreshState()
                            DnsSenseService.startOrReevaluate(context)
                            onShowToast("已重新評估網路與 DNS 狀態")
                        }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "重新整理",
                                tint = DnsPalette.InkPrimary
                            )
                        }
                    }
                )
                HorizontalDivider(color = DnsPalette.Border, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = DnsPalette.Border, thickness = 1.dp)
                NavigationBar(
                    containerColor = DnsPalette.Surface,
                    tonalElevation = 0.dp
                ) {
                    MainTab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                selectedTab = tab
                                refreshState()
                            },
                            icon = {
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = {
                                Text(
                                    tab.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = DnsPalette.Ink,
                                selectedTextColor = DnsPalette.Ink,
                                unselectedIconColor = DnsPalette.InkMuted,
                                unselectedTextColor = DnsPalette.InkMuted,
                                indicatorColor = DnsPalette.Subtle
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                MainTab.OVERVIEW -> OverviewTab(
                    config = config,
                    snapshot = snapshot,
                    currentMode = currentMode,
                    currentSpecifier = currentSpecifier,
                    hasSecurePermission = hasSecurePermission,
                    probeResult = probeResult,
                    isProbing = isProbing,
                    onToggleGuard = { checked ->
                        val next = config.copy(enabled = checked)
                        prefs.saveConfig(next)
                        config = next
                        if (checked) DnsSenseService.startOrReevaluate(context)
                        else DnsSenseService.stop(context)
                    },
                    onRunProbe = {
                        isProbing = true
                        scope.launch(Dispatchers.IO) {
                            val res = DotProber.probe(config.hostname)
                            withContext(Dispatchers.Main) {
                                probeResult = res
                                isProbing = false
                            }
                        }
                    },
                    onOpenSystemSettings = {
                        context.startActivity(PrivateDnsController.buildNetworkSettingsIntent())
                    },
                    onGoToAuthSettings = { selectedTab = MainTab.SETTINGS },
                    onQuickBypassCurrentWifi = { ssid ->
                        config = prefs.addBypassSsid(ssid)
                        onShowToast("已將「$ssid」加入排除清單")
                        DnsSenseService.startOrReevaluate(context)
                    }
                )

                MainTab.RULES -> RulesTab(
                    config = config,
                    snapshot = snapshot,
                    hasLocationPermission = hasLocationPermission,
                    onToggleBypassCellular = { checked ->
                        val next = config.copy(bypassCellular = checked)
                        prefs.saveConfig(next)
                        config = next
                        DnsSenseService.startOrReevaluate(context)
                    },
                    onAddCurrentWifi = { ssid ->
                        config = prefs.addBypassSsid(ssid)
                        onShowToast("已將「$ssid」加入排除清單")
                        DnsSenseService.startOrReevaluate(context)
                    },
                    onOpenAddDialog = { showAddSsidDialog = true },
                    onRemoveSsid = { ssid ->
                        config = prefs.removeBypassSsid(ssid)
                        onShowToast("已移除排除規則：$ssid")
                        DnsSenseService.startOrReevaluate(context)
                    },
                    onRequestLocation = onRequestRuntimePermissions
                )

                MainTab.SETTINGS -> SettingsTab(
                    context = context,
                    config = config,
                    hostnameInput = hostnameInput,
                    hasSecurePermission = hasSecurePermission,
                    hasLocationPermission = hasLocationPermission,
                    onHostnameChange = { hostnameInput = it },
                    onSaveHostname = {
                        val trimmed = hostnameInput.trim()
                        if (!HostnameValidator.isValid(trimmed)) {
                            onShowToast("主機名稱格式無效，請輸入如 dns.adguard-dns.com")
                        } else {
                            val next = config.copy(hostname = trimmed)
                            prefs.saveConfig(next)
                            config = next
                            onShowToast("已儲存私人 DNS：$trimmed")
                            DnsSenseService.startOrReevaluate(context)
                        }
                    },
                    onToggleAutoProbe = { checked ->
                        val next = config.copy(autoProbeEnabled = checked)
                        prefs.saveConfig(next)
                        config = next
                        DnsSenseService.startOrReevaluate(context)
                    },
                    onGrantShizuku = {
                        scope.launch(Dispatchers.IO) {
                            val (_, msg) = PrivateDnsController.grantViaShizuku(context)
                            withContext(Dispatchers.Main) {
                                refreshState()
                                onShowToast(msg)
                                if (PrivateDnsController.hasWriteSecureSettings(context)) {
                                    DnsSenseService.startOrReevaluate(context)
                                }
                            }
                        }
                    },
                    onCopyAdb = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("adb_grant", PrivateDnsController.adbGrantCommand(context))
                        )
                        onShowToast("已複製一次性 ADB 授權指令")
                    },
                    onRequestLocation = onRequestRuntimePermissions
                )

                MainTab.LOGS -> LogsTab(
                    logs = logs,
                    onClearLogs = {
                        prefs.clearLogs()
                        logs = emptyList()
                        onShowToast("已清除守護日誌")
                    }
                )
            }
        }
    }

    if (showAddSsidDialog) {
        var newSsid by remember { mutableStateOf("") }
        AlertDialog(
            containerColor = DnsPalette.Surface,
            onDismissRequest = { showAddSsidDialog = false },
            title = { Text("新增排除 Wi-Fi", fontWeight = FontWeight.SemiBold, color = DnsPalette.Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "連線到此 Wi-Fi 時，DnsSense 將優先切換為系統自動 DNS。",
                        fontSize = 13.sp,
                        color = DnsPalette.InkMuted
                    )
                    OutlinedTextField(
                        value = newSsid,
                        onValueChange = { newSsid = it },
                        label = { Text("Wi-Fi 名稱 (SSID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSsid.isNotBlank()) {
                            config = prefs.addBypassSsid(newSsid)
                            DnsSenseService.startOrReevaluate(context)
                        }
                        showAddSsidDialog = false
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("加入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSsidDialog = false }) {
                    Text("取消", color = DnsPalette.InkMuted)
                }
            }
        )
    }
}

// =====================================================================
// Tab 1: 總覽 (Hero 狀態焦點 + Bento 雙欄摘要 + 一鍵動作)
// =====================================================================
@Composable
private fun OverviewTab(
    config: DnsSenseConfig,
    snapshot: NetworkSnapshot,
    currentMode: PrivateDnsMode,
    currentSpecifier: String,
    hasSecurePermission: Boolean,
    probeResult: ProbeResult?,
    isProbing: Boolean,
    onToggleGuard: (Boolean) -> Unit,
    onRunProbe: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onGoToAuthSettings: () -> Unit,
    onQuickBypassCurrentWifi: (String) -> Unit
) {
    val currentSsid = NetworkSnapshot.normalizeSsid(snapshot.ssid)
    val alreadyBypassed = currentSsid.isNotEmpty() &&
        config.bypassSsids.any { it.equals(currentSsid, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // Hero 狀態主卡（3 秒視覺焦點）
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "系統目前 DNS 狀態",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = DnsPalette.InkMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentMode.labelZh,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = DnsPalette.Ink
                        )
                        val hostSubtitle = when {
                            currentMode == PrivateDnsMode.HOSTNAME && currentSpecifier.isNotBlank() -> currentSpecifier
                            currentMode == PrivateDnsMode.OPPORTUNISTIC -> "由系統依網路自動協調"
                            else -> "目標主機：${config.hostname}"
                        }
                        Text(
                            hostSubtitle,
                            fontSize = 13.sp,
                            color = DnsPalette.InkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = onToggleGuard
                        )
                        Text(
                            if (config.enabled) "守護中" else "已暫停",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (config.enabled) DnsPalette.GreenFg else DnsPalette.InkMuted
                        )
                    }
                }
            }
        }

        // 雙欄 Bento 數據卡（當前網路 + DoT 853 健康狀態）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CraftCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (snapshot.transport == NetworkTransport.WIFI) Icons.Default.Wifi
                            else Icons.Default.SignalCellularAlt,
                            contentDescription = null,
                            tint = DnsPalette.InkMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("當前網路", fontSize = 12.sp, color = DnsPalette.InkMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        snapshot.displayLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DnsPalette.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            snapshot.isCaptivePortal -> "需網頁認證"
                            alreadyBypassed -> "位於排除清單"
                            snapshot.transport == NetworkTransport.CELLULAR && config.bypassCellular -> "行動數據已排除"
                            else -> "標準守護範圍"
                        },
                        fontSize = 12.sp,
                        color = if (snapshot.isCaptivePortal) DnsPalette.AmberFg else DnsPalette.InkMuted
                    )
                }

                CraftCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Sensors,
                            contentDescription = null,
                            tint = DnsPalette.InkMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("DoT 853 探測", fontSize = 12.sp, color = DnsPalette.InkMuted)
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        isProbing -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = DnsPalette.InkPrimary,
                                trackColor = DnsPalette.Subtle
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("正在測試 TLS 853...", fontSize = 12.sp, color = DnsPalette.InkMuted)
                        }
                        probeResult != null -> {
                            Text(
                                if (probeResult.isHealthy) "${probeResult.latencyMs} ms" else "連線受阻",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = if (probeResult.isHealthy) DnsPalette.GreenFg else DnsPalette.RedFg
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (probeResult.isHealthy) "853 查詢正常" else probeResult.reason,
                                fontSize = 12.sp,
                                color = DnsPalette.InkMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> {
                            Text(
                                "待探測",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DnsPalette.Ink
                            )
                            Spacer(Modifier.height(2.dp))
                            Text("點下方按鈕即時實測", fontSize = 12.sp, color = DnsPalette.InkMuted)
                        }
                    }
                }
            }
        }

        // 一鍵核心操作列（高標靶高度 46dp，符合菲茨定律）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onRunProbe,
                    enabled = !isProbing,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DnsPalette.InkPrimary)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isProbing) "探測中..." else "探測 DoT 853", fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = onOpenSystemSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DnsPalette.Border)
                ) {
                    Icon(
                        Icons.Default.SettingsEthernet,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = DnsPalette.InkPrimary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("系統 DNS 設定", color = DnsPalette.InkPrimary, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 若連線至未排除 Wi-Fi，提供 1-Click 快捷收編
        if (snapshot.transport == NetworkTransport.WIFI && currentSsid.isNotEmpty() && !alreadyBypassed) {
            item {
                OutlinedButton(
                    onClick = { onQuickBypassCurrentWifi(currentSsid) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DnsPalette.Border)
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("將目前 Wi-Fi「$currentSsid」加入排除清單", color = DnsPalette.InkPrimary)
                }
            }
        }

        // 漸進式揭露：未授權時顯示精簡引導條（點一下跳轉「設定」頁籤解鎖）
        if (!hasSecurePermission) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onGoToAuthSettings() },
                    color = DnsPalette.AmberBg,
                    border = BorderStroke(1.dp, DnsPalette.AmberBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "目前以「提醒模式」運作",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = DnsPalette.AmberFg
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "前往「設定」完成一次性授權，即可升級為全自動切換。",
                                fontSize = 12.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = DnsPalette.AmberFg
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================
// Tab 2: 排除規則 (行動數據 + Wi-Fi 排除清單 + 完整空白態)
// =====================================================================
@Composable
private fun RulesTab(
    config: DnsSenseConfig,
    snapshot: NetworkSnapshot,
    hasLocationPermission: Boolean,
    onToggleBypassCellular: (Boolean) -> Unit,
    onAddCurrentWifi: (String) -> Unit,
    onOpenAddDialog: () -> Unit,
    onRemoveSsid: (String) -> Unit,
    onRequestLocation: () -> Unit
) {
    val currentSsid = NetworkSnapshot.normalizeSsid(snapshot.ssid)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // 說明與行動數據開關
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("排除行動數據 (4G / 5G)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DnsPalette.Ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "切換到行動網路時改用系統自動 DNS",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                    Switch(
                        checked = config.bypassCellular,
                        onCheckedChange = onToggleBypassCellular
                    )
                }
            }
        }

        // 未授予定位權限時提醒（否則背景讀不到 Wi-Fi SSID）
        if (!hasLocationPermission) {
            item {
                Surface(
                    color = DnsPalette.AmberBg,
                    border = BorderStroke(1.dp, DnsPalette.AmberBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("尚未授予精確定位權限", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = DnsPalette.AmberFg)
                            Text("Android 規定需定位權限才能讀取 Wi-Fi 名稱。", fontSize = 12.sp, color = Color(0xFF92400E))
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onRequestLocation) {
                            Text("立即授予", color = DnsPalette.AmberFg, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Wi-Fi 排除清單標題與新增動作
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("排除 Wi-Fi 清單", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                    Text("命中清單一律切為自動 DNS（優先於 853 探測）", fontSize = 12.sp, color = DnsPalette.InkMuted)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onAddCurrentWifi(currentSsid) },
                    enabled = snapshot.transport == NetworkTransport.WIFI && currentSsid.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DnsPalette.InkPrimary)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("加入目前 Wi-Fi", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onOpenAddDialog,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DnsPalette.Border)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = DnsPalette.InkPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("手動輸入 SSID", color = DnsPalette.InkPrimary)
                }
            }
        }

        // 完整空白態 (Empty State) 或清單項目
        if (config.bypassSsids.isEmpty()) {
            item {
                CraftCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.WifiFind,
                            contentDescription = null,
                            tint = DnsPalette.InkFaint,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            "尚未新增任何排除 Wi-Fi",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = DnsPalette.Ink
                        )
                        Text(
                            "將公司內網、飯店或偶發功能異常的 Wi-Fi 加入此處，連上時會自動退回系統 DNS。",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                }
            }
        } else {
            items(config.bypassSsids, key = { it }) { ssid ->
                Surface(
                    color = DnsPalette.Surface,
                    border = BorderStroke(1.dp, DnsPalette.Border),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                tint = DnsPalette.InkMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(ssid, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = DnsPalette.Ink)
                        }
                        IconButton(onClick = { onRemoveSsid(ssid) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "移除",
                                tint = DnsPalette.RedFg
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// Tab 3: 設定 (私人 DNS 主機、主動探測開關、一次性授權中心)
// =====================================================================
@Composable
private fun SettingsTab(
    context: Context,
    config: DnsSenseConfig,
    hostnameInput: String,
    hasSecurePermission: Boolean,
    hasLocationPermission: Boolean,
    onHostnameChange: (String) -> Unit,
    onSaveHostname: () -> Unit,
    onToggleAutoProbe: (Boolean) -> Unit,
    onGrantShizuku: () -> Unit,
    onCopyAdb: () -> Unit,
    onRequestLocation: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // 私人 DNS 主機設定
        item {
            CraftCard {
                Text("私人 DNS 主機 (DoT)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hostnameInput,
                    onValueChange = onHostnameChange,
                    label = { Text("主機名稱（FQDN）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "快速套用（由純加密至進階過濾排序）",
                    fontSize = 12.sp,
                    color = DnsPalette.InkMuted
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DNS_PRESETS.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                val isSelected = hostnameInput.trim().equals(preset.hostname, ignoreCase = true)
                                PresetChip(
                                    name = preset.name,
                                    badge = preset.badge,
                                    selected = isSelected,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onHostnameChange(preset.hostname) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSaveHostname,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DnsPalette.InkPrimary)
                ) {
                    Text("儲存主機名稱並立即生效")
                }
            }
        }

        // 主動探測設定
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("DoT 853 主動探測降級", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DnsPalette.Ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "連上未知網路時自動測試 853 存活，被封鎖即退回系統自動 DNS",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                    Switch(
                        checked = config.autoProbeEnabled,
                        onCheckedChange = onToggleAutoProbe
                    )
                }
            }
        }

        // 權限與運作模式中心
        item {
            CraftCard {
                Text("運作模式與權限狀態", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                Spacer(Modifier.height(10.dp))

                // 權限 1: WRITE_SECURE_SETTINGS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自動切換權限", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = DnsPalette.Ink)
                        Text(
                            if (hasSecurePermission) "已啟用「自動模式」，免 Shizuku 常駐、重開機有效"
                            else "目前為「提醒模式」，授權一次即可永久升級自動模式",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                    StatusPill(
                        label = if (hasSecurePermission) "已授權" else "未授權",
                        bg = if (hasSecurePermission) DnsPalette.GreenBg else DnsPalette.AmberBg,
                        border = if (hasSecurePermission) DnsPalette.GreenBorder else DnsPalette.AmberBorder,
                        fg = if (hasSecurePermission) DnsPalette.GreenFg else DnsPalette.AmberFg
                    )
                }

                if (!hasSecurePermission) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DnsPalette.InkPrimary, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            PrivateDnsController.adbGrantCommand(context),
                            color = Color(0xFFE4E4E7),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onGrantShizuku,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DnsPalette.InkPrimary)
                        ) {
                            Text("Shizuku 一鍵授權", fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick = onCopyAdb,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DnsPalette.Border)
                        ) {
                            Text("複製 ADB 指令", fontSize = 13.sp, color = DnsPalette.InkPrimary)
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = DnsPalette.Border
                )

                // 權限 2: 定位權限
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("精確定位權限（讀取 Wi-Fi 名稱）", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = DnsPalette.Ink)
                        Text(
                            "100% 本地比對 SSID，絕不外傳任何位置資訊",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                    if (hasLocationPermission) {
                        StatusPill("已授權", DnsPalette.GreenBg, DnsPalette.GreenBorder, DnsPalette.GreenFg)
                    } else {
                        TextButton(onClick = onRequestLocation) {
                            Text("授予", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// Tab 4: 日誌 (時間軸記錄 + 空白態)
// =====================================================================
@Composable
private fun LogsTab(
    logs: List<EventLogItem>,
    onClearLogs: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "近期守護紀錄（${logs.size} 筆）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = DnsPalette.Ink
                )
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClearLogs) {
                        Text("清除全部", color = DnsPalette.RedFg, fontSize = 13.sp)
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                CraftCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = DnsPalette.InkFaint,
                            modifier = Modifier.size(36.dp)
                        )
                        Text("尚無切換或探測紀錄", fontWeight = FontWeight.SemiBold, color = DnsPalette.Ink)
                        Text(
                            "當網路切換或觸發自動降級時，完整決策原因會記錄於此。",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                }
            }
        } else {
            items(logs) { item ->
                CraftCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.actionLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = DnsPalette.Ink)
                        Text(
                            fmt.format(Date(item.timestampMs)),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = DnsPalette.InkMuted
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.networkLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DnsPalette.InkPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(item.detail, fontSize = 12.sp, color = DnsPalette.InkMuted)
                }
            }
        }
    }
}

// =====================================================================
// 共用極簡元件 (Shadcn Restrained Surface / Pill / Chip)
// =====================================================================
@Composable
private fun CraftCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = DnsPalette.Surface,
        border = BorderStroke(1.dp, DnsPalette.Border),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    bg: Color,
    border: Color,
    fg: Color
) {
    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(fg, CircleShape)
        )
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

@Composable
private fun PresetChip(
    name: String,
    badge: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = if (selected) DnsPalette.InkPrimary else DnsPalette.Subtle,
        border = BorderStroke(1.dp, if (selected) DnsPalette.InkPrimary else DnsPalette.Border),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Color.White else DnsPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                badge,
                fontSize = 11.sp,
                color = if (selected) Color(0xFFD4D4D8) else DnsPalette.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

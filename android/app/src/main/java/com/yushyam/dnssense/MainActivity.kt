package com.yushyam.dnssense

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yushyam.dnssense.data.model.DnsSenseConfig
import com.yushyam.dnssense.data.model.EventLogItem
import com.yushyam.dnssense.data.model.HostnameValidator
import com.yushyam.dnssense.data.model.NetworkSnapshot
import com.yushyam.dnssense.data.model.NetworkTransport
import com.yushyam.dnssense.data.model.PrivateDnsMode
import com.yushyam.dnssense.data.model.ProbeResult
import com.yushyam.dnssense.data.model.ThemeMode
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

// Shadcn / Zinc 動態深淺色票定義 (Dynamic Light & Dark Zinc Palette)
private object DnsPalette {
    var isDark by mutableStateOf(false)

    val Bg: Color get() = if (isDark) Color(0xFF09090B) else Color(0xFFFAFAFA)
    val Surface: Color get() = if (isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val Subtle: Color get() = if (isDark) Color(0xFF27272A) else Color(0xFFF4F4F5)
    val Border: Color get() = if (isDark) Color(0xFF27272A) else Color(0xFFE4E4E7)
    val Ink: Color get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)
    val InkPrimary: Color get() = if (isDark) Color(0xFFFAFAFA) else Color(0xFF18181B)
    val OnPrimary: Color get() = if (isDark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val InkMuted: Color get() = if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    val InkFaint: Color get() = if (isDark) Color(0xFF71717A) else Color(0xFFA1A1AA)

    val GreenBg: Color get() = if (isDark) Color(0xFF052E16) else Color(0xFFECFDF5)
    val GreenBorder: Color get() = if (isDark) Color(0xFF14532D) else Color(0xFFA7F3D0)
    val GreenFg: Color get() = if (isDark) Color(0xFF34D399) else Color(0xFF047857)

    val AmberBg: Color get() = if (isDark) Color(0xFF271705) else Color(0xFFFFFBEB)
    val AmberBorder: Color get() = if (isDark) Color(0xFF78350F) else Color(0xFFFDE68A)
    val AmberFg: Color get() = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
    val AmberSubText: Color get() = if (isDark) Color(0xFFFDE68A) else Color(0xFF92400E)

    val RedBg: Color get() = if (isDark) Color(0xFF2A0A12) else Color(0xFFFEF2F2)
    val RedFg: Color get() = if (isDark) Color(0xFFFB7185) else Color(0xFFBE123C)
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

private val DNS_PRESETS = listOf(
    DnsPreset("Google", "8.8.8.8 · 純加密", "dns.google"),
    DnsPreset("Cloudflare", "1.1.1.1 · 純加密", "1dot1dot1dot1.cloudflare-dns.com"),
    DnsPreset("Quad9", "阻擋惡意網站", "dns.quad9.net"),
    DnsPreset("AdGuard", "阻擋廣告與追蹤", DnsSenseConfig.DEFAULT_HOSTNAME),
    DnsPreset("Mullvad", "阻擋廣告與隱私保護", "base.dns.mullvad.net"),
    DnsPreset("Control D", "阻擋廣告與惡意網站", "p2.freedns.controld.com")
)

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        DnsSenseService.startOrReevaluate(this)
        sendBroadcast(Intent(DnsSenseService.ACTION_STATE_UPDATED).setPackage(packageName))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = DnsSensePrefs(this)
        if (prefs.loadConfig().enabled) {
            DnsSenseService.startOrReevaluate(this)
        }

        setContent {
            DnsSenseApp(
                context = this,
                onRequestRuntimePermissions = { requestNeededRuntimePermissions() },
                onShowToast = { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent(DnsSenseService.ACTION_STATE_UPDATED).setPackage(packageName))
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

private fun checkLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
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
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableStateOf(MainTab.OVERVIEW) }
    var config by remember { mutableStateOf(prefs.loadConfig()) }
    var hostnameInput by remember { mutableStateOf(config.hostname) }
    var hasSecurePermission by remember { mutableStateOf(PrivateDnsController.hasWriteSecureSettings(context)) }
    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission(context)) }
    var currentMode by remember { mutableStateOf(PrivateDnsController.readCurrentMode(context)) }
    var currentSpecifier by remember { mutableStateOf(PrivateDnsController.readCurrentSpecifier(context)) }
    var snapshot by remember { mutableStateOf(observer.currentSnapshot()) }
    var logs by remember { mutableStateOf(prefs.loadLogs()) }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }
    var isProbing by remember { mutableStateOf(false) }
    var showAddSsidDialog by remember { mutableStateOf(false) }

    val systemDark = isSystemInDarkTheme()
    val currentThemeMode = ThemeMode.fromSetting(config.themeMode)
    val useDark = when (currentThemeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    DnsPalette.isDark = useDark

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = DnsPalette.Surface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = DnsPalette.Surface.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    val colorScheme = if (useDark) {
        darkColorScheme(
            primary = DnsPalette.InkPrimary,
            onPrimary = DnsPalette.OnPrimary,
            secondary = Color(0xFFD4D4D8),
            background = DnsPalette.Bg,
            surface = DnsPalette.Surface,
            onSurface = DnsPalette.Ink,
            surfaceVariant = DnsPalette.Subtle,
            onSurfaceVariant = DnsPalette.InkMuted,
            outline = DnsPalette.Border
        )
    } else {
        lightColorScheme(
            primary = DnsPalette.InkPrimary,
            onPrimary = DnsPalette.OnPrimary,
            secondary = Color(0xFF3F3F46),
            background = DnsPalette.Bg,
            surface = DnsPalette.Surface,
            onSurface = DnsPalette.Ink,
            surfaceVariant = DnsPalette.Subtle,
            onSurfaceVariant = DnsPalette.InkMuted,
            outline = DnsPalette.Border
        )
    }

    fun refreshState() {
        config = prefs.loadConfig()
        hasSecurePermission = PrivateDnsController.hasWriteSecureSettings(context)
        hasLocationPermission = checkLocationPermission(context)
        currentMode = PrivateDnsController.readCurrentMode(context)
        currentSpecifier = PrivateDnsController.readCurrentSpecifier(context)
        snapshot = observer.currentSnapshot()
        logs = prefs.loadLogs()
    }

    fun setThemeMode(mode: ThemeMode) {
        val next = config.copy(themeMode = mode.settingValue)
        prefs.saveConfig(next)
        config = next
    }

    DisposableEffect(lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
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

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = DnsPalette.Bg
        ) {
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
                                    val nextMode = currentThemeMode.next()
                                    setThemeMode(nextMode)
                                    onShowToast("外觀：${nextMode.labelZh}")
                                }) {
                                    Icon(
                                        when (currentThemeMode) {
                                            ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                            ThemeMode.LIGHT -> Icons.Default.LightMode
                                            ThemeMode.DARK -> Icons.Default.DarkMode
                                        },
                                        contentDescription = "切換深淺色",
                                        tint = DnsPalette.InkPrimary
                                    )
                                }
                                IconButton(onClick = {
                                    refreshState()
                                    DnsSenseService.startOrReevaluate(context)
                                    onShowToast("狀態已更新")
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
                                onShowToast("已移除：$ssid")
                                DnsSenseService.startOrReevaluate(context)
                            },
                            onRequestLocation = onRequestRuntimePermissions
                        )

                        MainTab.SETTINGS -> SettingsTab(
                            context = context,
                            config = config,
                            currentThemeMode = currentThemeMode,
                            hostnameInput = hostnameInput,
                            hasSecurePermission = hasSecurePermission,
                            hasLocationPermission = hasLocationPermission,
                            onSelectThemeMode = { mode -> setThemeMode(mode) },
                            onHostnameChange = { hostnameInput = it },
                            onSaveHostname = {
                                val trimmed = hostnameInput.trim()
                                if (!HostnameValidator.isValid(trimmed)) {
                                    onShowToast("位址格式不正確，請輸入如 dns.adguard-dns.com")
                                } else {
                                    val next = config.copy(hostname = trimmed)
                                    prefs.saveConfig(next)
                                    config = next
                                    onShowToast("已儲存：$trimmed")
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
                                onShowToast("已複製 ADB 指令")
                            },
                            onRequestLocation = onRequestRuntimePermissions
                        )

                        MainTab.LOGS -> LogsTab(
                            logs = logs,
                            onClearLogs = {
                                prefs.clearLogs()
                                logs = emptyList()
                                onShowToast("已清除紀錄")
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
                                "連上此 Wi-Fi 時，將自動切回系統預設 DNS。",
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
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DnsPalette.InkPrimary,
                                contentColor = DnsPalette.OnPrimary
                            )
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
    }
}

// =====================================================================
// Tab 1: 總覽
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

    val networkHeader = when (snapshot.transport) {
        NetworkTransport.WIFI -> "目前網路 · Wi-Fi"
        NetworkTransport.CELLULAR -> "目前網路 · 行動網路"
        NetworkTransport.OTHER -> "目前網路 · 其他"
        NetworkTransport.NONE -> "目前網路"
    }

    val networkPrimaryTitle = when (snapshot.transport) {
        NetworkTransport.WIFI -> currentSsid.ifEmpty { "已連線 Wi-Fi" }
        NetworkTransport.CELLULAR -> "4G / 5G 網路"
        NetworkTransport.OTHER -> "外部網路"
        NetworkTransport.NONE -> "未連線"
    }

    val networkSubtitle = when {
        snapshot.transport == NetworkTransport.NONE -> "無可用網路連線"
        snapshot.isCaptivePortal -> "需網頁登入認證"
        alreadyBypassed -> "已列入排除清單"
        snapshot.transport == NetworkTransport.CELLULAR && config.bypassCellular -> "行動網路已排除"
        else -> "使用私人 DNS"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (config.enabled) "目前 DNS 狀態 · 自動監控中" else "目前 DNS 狀態 · 已暫停",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = DnsPalette.InkMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            currentMode.labelZh,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = DnsPalette.Ink
                        )
                        val hostSubtitle = when {
                            currentMode == PrivateDnsMode.HOSTNAME && currentSpecifier.isNotBlank() -> currentSpecifier
                            currentMode == PrivateDnsMode.OPPORTUNISTIC -> "系統預設自動切換"
                            else -> "設定位址：${config.hostname}"
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            hostSubtitle,
                            fontSize = 13.sp,
                            color = DnsPalette.InkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = onToggleGuard
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    color = DnsPalette.Surface,
                    border = BorderStroke(1.dp, DnsPalette.Border),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (snapshot.transport == NetworkTransport.WIFI) Icons.Default.Wifi
                                else Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = DnsPalette.InkMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                networkHeader,
                                fontSize = 12.sp,
                                color = DnsPalette.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Text(
                            networkPrimaryTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = DnsPalette.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            networkSubtitle,
                            fontSize = 12.sp,
                            color = if (snapshot.isCaptivePortal) DnsPalette.AmberFg else DnsPalette.InkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !isProbing) { onRunProbe() },
                    color = DnsPalette.Surface,
                    border = BorderStroke(1.dp, DnsPalette.Border),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Sensors,
                                contentDescription = null,
                                tint = DnsPalette.InkMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "DoT 853 連線",
                                fontSize = 12.sp,
                                color = DnsPalette.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        when {
                            isProbing -> {
                                Text(
                                    "測試中...",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DnsPalette.Ink,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "正在連線 853 埠",
                                    fontSize = 12.sp,
                                    color = DnsPalette.InkMuted,
                                    maxLines = 1
                                )
                            }
                            probeResult != null -> {
                                Text(
                                    if (probeResult.isHealthy) "${probeResult.latencyMs} ms" else "連線失敗",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (probeResult.isHealthy) DnsPalette.GreenFg else DnsPalette.RedFg,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (probeResult.isHealthy) "853 埠連線正常" else probeResult.reason,
                                    fontSize = 12.sp,
                                    color = DnsPalette.InkMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            else -> {
                                Text(
                                    "未測試",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DnsPalette.Ink,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "點擊測試連線",
                                    fontSize = 12.sp,
                                    color = DnsPalette.InkMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenSystemSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DnsPalette.Border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DnsPalette.Surface,
                        contentColor = DnsPalette.InkPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.SettingsEthernet,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = DnsPalette.InkPrimary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "系統 DNS 設定",
                        color = DnsPalette.InkPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onRunProbe,
                    enabled = !isProbing,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DnsPalette.InkPrimary,
                        contentColor = DnsPalette.OnPrimary
                    )
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isProbing) "測試中..." else "測試 DNS 連線",
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        if (snapshot.transport == NetworkTransport.WIFI && currentSsid.isNotEmpty() && !alreadyBypassed) {
            item {
                OutlinedButton(
                    onClick = { onQuickBypassCurrentWifi(currentSsid) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, DnsPalette.Border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DnsPalette.Surface,
                        contentColor = DnsPalette.InkPrimary
                    )
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "將「$currentSsid」加入排除清單",
                        color = DnsPalette.InkPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

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
                                "目前為「提醒模式」",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = DnsPalette.AmberFg
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "點擊前往「設定」開啟自動切換權限。",
                                fontSize = 12.sp,
                                color = DnsPalette.AmberSubText
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
// Tab 2: 排除規則
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
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("排除行動網路 (4G / 5G)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DnsPalette.Ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "使用行動網路時自動切回系統預設 DNS",
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
                            Text("需開啟位置權限以讀取 Wi-Fi 名稱", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = DnsPalette.AmberFg)
                            Text("Android 系統規定讀取 Wi-Fi 名稱需具備位置權限。", fontSize = 12.sp, color = DnsPalette.AmberSubText)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onRequestLocation) {
                            Text("開啟權限", color = DnsPalette.AmberFg, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item {
            Column {
                Text("排除 Wi-Fi 清單", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                Text("連上清單內的 Wi-Fi 時，自動切回系統預設 DNS", fontSize = 12.sp, color = DnsPalette.InkMuted)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onAddCurrentWifi(currentSsid) },
                    enabled = snapshot.transport == NetworkTransport.WIFI && currentSsid.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DnsPalette.InkPrimary,
                        contentColor = DnsPalette.OnPrimary
                    )
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
                    border = BorderStroke(1.dp, DnsPalette.Border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = DnsPalette.Surface,
                        contentColor = DnsPalette.InkPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = DnsPalette.InkPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("手動輸入名稱", color = DnsPalette.InkPrimary, maxLines = 1)
                }
            }
        }

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
                            "尚未加入任何 Wi-Fi",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = DnsPalette.Ink
                        )
                        Text(
                            "若特定 Wi-Fi（如公司內網或公用網路）開啟私人 DNS 後無法連線，可加入此處自動切回預設 DNS。",
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
// Tab 3: 設定
// =====================================================================
@Composable
private fun SettingsTab(
    context: Context,
    config: DnsSenseConfig,
    currentThemeMode: ThemeMode,
    hostnameInput: String,
    hasSecurePermission: Boolean,
    hasLocationPermission: Boolean,
    onSelectThemeMode: (ThemeMode) -> Unit,
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
        // 外觀深淺色主題切換卡 (跟隨系統 / 淺色 / 深色)
        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("外觀主題", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                    Text("支援自動跟隨系統或手動切換", fontSize = 12.sp, color = DnsPalette.InkMuted)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DnsPalette.Subtle, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = currentThemeMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { onSelectThemeMode(mode) },
                            color = if (selected) DnsPalette.Surface else Color.Transparent,
                            border = if (selected) BorderStroke(1.dp, DnsPalette.Border) else null,
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    mode.labelZh,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) DnsPalette.Ink else DnsPalette.InkMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            CraftCard {
                Text("私人 DNS 伺服器", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hostnameInput,
                    onValueChange = onHostnameChange,
                    label = { Text("伺服器位址（例如 dns.adguard-dns.com）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "常用伺服器",
                    fontSize = 12.sp,
                    color = DnsPalette.InkMuted
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DNS_PRESETS.chunked(2).forEach { rowPresets ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { preset ->
                                val isSelected = hostnameInput.trim().equals(preset.hostname, ignoreCase = true)
                                PresetChip(
                                    name = preset.name,
                                    badge = preset.badge,
                                    selected = isSelected,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DnsPalette.InkPrimary,
                        contentColor = DnsPalette.OnPrimary
                    )
                ) {
                    Text("儲存設定", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            CraftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自動測試連線", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = DnsPalette.Ink)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "連上新網路時自動測試 853 埠，若被阻擋則暫時切回自動 DNS",
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

        item {
            CraftCard {
                Text("權限與運作模式", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = DnsPalette.Ink)
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自動切換權限", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = DnsPalette.Ink)
                        Text(
                            if (hasSecurePermission) "已啟用自動切換，重開機仍有效"
                            else "授權一次即可依網路自動切換 DNS",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                    StatusPill(
                        label = if (hasSecurePermission) "已開啟" else "未開啟",
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
                            .background(DnsPalette.Subtle, RoundedCornerShape(8.dp))
                            .border(1.dp, DnsPalette.Border, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            PrivateDnsController.adbGrantCommand(context),
                            color = DnsPalette.Ink,
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DnsPalette.InkPrimary,
                                contentColor = DnsPalette.OnPrimary
                            )
                        ) {
                            Text("Shizuku 授權", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onCopyAdb,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, DnsPalette.Border)
                        ) {
                            Text("複製 ADB 指令", fontSize = 13.sp, color = DnsPalette.InkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = DnsPalette.Border
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("位置權限（讀取 Wi-Fi 名稱）", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = DnsPalette.Ink)
                        Text(
                            "僅用於本機判斷目前連線的 Wi-Fi 名稱",
                            fontSize = 12.sp,
                            color = DnsPalette.InkMuted
                        )
                    }
                    if (hasLocationPermission) {
                        StatusPill("已開啟", DnsPalette.GreenBg, DnsPalette.GreenBorder, DnsPalette.GreenFg)
                    } else {
                        TextButton(onClick = onRequestLocation) {
                            Text("開啟權限", fontWeight = FontWeight.SemiBold, color = DnsPalette.InkPrimary)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// Tab 4: 日誌
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
                    "近期切換紀錄（${logs.size} 筆）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = DnsPalette.Ink
                )
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClearLogs) {
                        Text("清除", color = DnsPalette.RedFg, fontSize = 13.sp)
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
                        Text("尚無切換紀錄", fontWeight = FontWeight.SemiBold, color = DnsPalette.Ink)
                        Text(
                            "網路切換與自動調整 DNS 的紀錄會顯示於此。",
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
// 共用元件
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
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) DnsPalette.OnPrimary else DnsPalette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                badge,
                fontSize = 11.sp,
                color = if (selected) DnsPalette.OnPrimary.copy(alpha = 0.75f) else DnsPalette.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

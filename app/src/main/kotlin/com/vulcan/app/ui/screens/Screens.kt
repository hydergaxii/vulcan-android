package com.vulcan.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.vulcan.app.data.model.*
import com.vulcan.app.ui.components.*
import com.vulcan.app.ui.theme.*
import com.vulcan.app.ui.viewmodel.MainViewModel

// ─────────────────────────────────────────────────────────────────────────────
// NAVIGATION ROUTES
// ─────────────────────────────────────────────────────────────────────────────

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard",  "Dashboard",  Icons.Default.Dashboard)
    object Store     : Screen("store",      "Store",      Icons.Default.Store)
    object Network   : Screen("network",    "Network",    Icons.Default.Wifi)
    object Metrics   : Screen("metrics",    "Metrics",    Icons.Default.BarChart)
    object Settings  : Screen("settings",   "Settings",   Icons.Default.Settings)

    companion object {
        val bottomNavItems = listOf(Dashboard, Store, Network, Metrics, Settings)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DASHBOARD SCREEN — The forge floor
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToStore: () -> Unit,
    onOpenLogs: (String) -> Unit
) {
    val appsWithStatus by viewModel.appsWithStatus.collectAsState()
    val deviceMetrics  by viewModel.deviceMetrics.collectAsState()
    val installState   by viewModel.installProgress.collectAsState()

    // Install progress overlay
    if (installState !is MainViewModel.InstallState.Idle) {
        InstallProgressDialog(state = installState, onDismiss = viewModel::dismissInstallProgress)
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(VulcanDimens.paddingM),
        verticalArrangement = Arrangement.spacedBy(VulcanDimens.paddingM)
    ) {
        // Device stats
        item {
            deviceMetrics?.let { dm ->
                ForgeCard(Modifier.fillMaxWidth()) {
                    Text("Device", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    MetricsBar("RAM", dm.availableRamMB.toFloat() / dm.totalRamMB,
                        "${dm.availableRamMB}MB free / ${dm.totalRamMB}MB")
                    Spacer(Modifier.height(8.dp))
                    MetricsBar("CPU", dm.cpuPercent / 100f, "${dm.cpuPercent.toInt()}%",
                        color = if (dm.cpuPercent > 80) VulcanColors.HotMetal else VulcanColors.ForgeOrange)
                    Spacer(Modifier.height(8.dp))
                    MetricsBar("Storage", dm.storageUsedMB.toFloat() / dm.storageTotalMB,
                        "${dm.storageUsedMB / 1024}GB / ${dm.storageTotalMB / 1024}GB",
                        color = VulcanColors.CoolingForge)
                }
            }
        }

        // Apps header
        item {
            SectionHeader(
                title  = "Apps (${appsWithStatus.size})",
                action = if (appsWithStatus.isEmpty()) null else "Store" to onNavigateToStore
            )
        }

        // Empty state
        if (appsWithStatus.isEmpty()) {
            item {
                EmptyState(
                    icon     = "⚒️",
                    title    = "Forge is ready",
                    subtitle = "Install your first app from the Vulcan Store",
                    action   = "Browse Store" to onNavigateToStore
                )
            }
        }

        // App cards
        items(appsWithStatus, key = { it.app.id }) { (app, status) ->
            AppCard(
                app         = app,
                status      = status,
                onStart     = { viewModel.startApp(app.id) },
                onStop      = { viewModel.stopApp(app.id) },
                onOpen      = { /* launch WebView */ },
                onLongPress = { onOpenLogs(app.id) }
            )
        }

        item { Spacer(Modifier.height(80.dp)) }  // Bottom nav padding
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STORE SCREEN — Browse and install apps
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StoreScreen(viewModel: MainViewModel) {
    val registry    by viewModel.registry.collectAsState()
    val isLoading   by viewModel.storeLoading.collectAsState()
    val error       by viewModel.storeError.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf<String?>(null) }

    val displayedApps = remember(registry, searchQuery, selectedCat) {
        registry?.apps?.let { apps ->
            var result = apps
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                result = result.filter {
                    it.label.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.id.lowercase().contains(q)
                }
            }
            if (selectedCat != null) result = result.filter { it.category == selectedCat }
            result
        } ?: emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        // Search bar
        OutlinedTextField(
            value          = searchQuery,
            onValueChange  = { searchQuery = it },
            modifier       = Modifier.fillMaxWidth().padding(VulcanDimens.paddingM),
            placeholder    = { Text("Search 60+ apps...") },
            leadingIcon    = { Icon(Icons.Default.Search, null) },
            trailingIcon   = if (searchQuery.isNotBlank()) { { IconButton({ searchQuery = "" }) {
                Icon(Icons.Default.Clear, "Clear") } } } else null,
            shape          = RoundedCornerShape(VulcanDimens.radiusL),
            singleLine     = true,
            colors         = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor   = VulcanColors.ForgeOrange
            )
        )

        // Category chips
        registry?.categories?.let { cats ->
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding        = PaddingValues(horizontal = VulcanDimens.paddingM)
            ) {
                item {
                    FilterChip(
                        selected   = selectedCat == null,
                        onClick    = { selectedCat = null },
                        label      = { Text("All") },
                        colors     = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VulcanColors.ForgeOrange,
                            selectedLabelColor     = Color.White
                        )
                    )
                }
                items(cats) { cat ->
                    FilterChip(
                        selected   = selectedCat == cat.id,
                        onClick    = { selectedCat = if (selectedCat == cat.id) null else cat.id },
                        label      = { Text(cat.label) },
                        colors     = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VulcanColors.ForgeOrange,
                            selectedLabelColor     = Color.White
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            isLoading && registry == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = VulcanColors.ForgeOrange)
            }
            error != null && registry == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️ Failed to load store", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    VulcanButton("Retry", viewModel::refreshStore)
                }
            }
            else -> LazyVerticalGrid(
                columns         = GridCells.Adaptive(160.dp),
                contentPadding  = PaddingValues(VulcanDimens.paddingM),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement   = Arrangement.spacedBy(12.dp)
            ) {
                items(displayedApps, key = { it.id }) { app ->
                    StoreAppCard(app = app, onInstall = { viewModel.installApp(app) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun StoreAppCard(app: AppManifest, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(VulcanDimens.radiusL),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(VulcanDimens.paddingM)) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VulcanColors.ForgeOrange.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(1), style = MaterialTheme.typography.titleLarge,
                    color = VulcanColors.ForgeOrange)
            }
            Spacer(Modifier.height(8.dp))
            Text(app.label, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("[${app.runtime.engine}]",
                    style = MaterialTheme.typography.labelSmall,
                    color = VulcanColors.Ash)
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick  = onInstall,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors   = ButtonDefaults.filledTonalButtonColors(
                        containerColor = VulcanColors.ForgeOrange.copy(alpha = 0.2f),
                        contentColor   = VulcanColors.ForgeOrange
                    )
                ) { Text("Install", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LOG VIEWER SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun LogScreen(appId: String, onBack: () -> Unit) {
    val lines = remember(appId) {
        com.vulcan.app.util.VulcanLogger.getRecentLines(appId, 200)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title          = { Text("Logs — $appId", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            colors         = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
        LogViewer(
            lines    = lines,
            modifier = Modifier.fillMaxSize().padding(VulcanDimens.paddingM)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// NETWORK SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun NetworkScreen(viewModel: MainViewModel) {
    val tunnelUrl by viewModel.tunnelUrl.collectAsState()
    var cfToken by remember { mutableStateOf("") }
    var showCfDialog by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding      = PaddingValues(VulcanDimens.paddingM),
        verticalArrangement = Arrangement.spacedBy(VulcanDimens.paddingM)
    ) {
        item { Text("Network", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        // LAN Access
        item {
            ForgeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, null, tint = VulcanColors.CoolingForge)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("LAN Access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Devices on your WiFi can reach Vulcan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusBadge(AppStatus.RUNNING)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoChip("Dashboard", "vulcan.local:7777")
                    InfoChip("Proxy",     "vulcan.local:8080")
                }
            }
        }

        // Cloudflare Tunnel
        item {
            ForgeCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cloud, null, tint = if (tunnelUrl != null) VulcanColors.CoolingForge else VulcanColors.Ash)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cloudflare Tunnel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text  = tunnelUrl ?: "Access your apps from anywhere",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tunnelUrl != null) VulcanColors.CoolingForge
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (tunnelUrl != null) {
                    VulcanButton("Stop Tunnel", viewModel::stopCloudflareTunnel, danger = true)
                } else {
                    VulcanButton("Start Tunnel", { showCfDialog = true }, icon = Icons.Default.Add)
                }
            }
        }

        // Routing table
        item {
            SectionHeader("Active Routes")
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    if (showCfDialog) {
        AlertDialog(
            onDismissRequest = { showCfDialog = false },
            title = { Text("Cloudflare Tunnel Token") },
            text  = {
                Column {
                    Text("Enter your Cloudflare tunnel token:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = cfToken,
                        onValueChange = { cfToken = it },
                        placeholder   = { Text("eyJhbGciOi...") },
                        singleLine    = true
                    )
                }
            },
            confirmButton = {
                VulcanButton("Start", {
                    viewModel.startCloudflareTunnel(cfToken)
                    showCfDialog = false
                }, enabled = cfToken.isNotBlank())
            },
            dismissButton = { TextButton({ showCfDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// METRICS SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MetricsScreen(viewModel: MainViewModel) {
    val appMetrics    by viewModel.appMetrics.collectAsState()
    val deviceMetrics by viewModel.deviceMetrics.collectAsState()

    LazyColumn(
        contentPadding      = PaddingValues(VulcanDimens.paddingM),
        verticalArrangement = Arrangement.spacedBy(VulcanDimens.paddingM)
    ) {
        item { Text("Metrics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        deviceMetrics?.let { dm ->
            item {
                ForgeCard(Modifier.fillMaxWidth()) {
                    Text("Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    MetricsBar("RAM", dm.availableRamMB.toFloat() / dm.totalRamMB,
                        "${dm.availableRamMB}MB / ${dm.totalRamMB}MB")
                    Spacer(Modifier.height(12.dp))
                    MetricsBar("CPU", dm.cpuPercent / 100f, "${dm.cpuPercent.toInt()}%",
                        color = if (dm.cpuPercent > 80) VulcanColors.HotMetal else VulcanColors.ForgeOrange)
                    Spacer(Modifier.height(12.dp))
                    MetricsBar("Storage", dm.storageUsedMB.toFloat() / dm.storageTotalMB,
                        "${dm.storageUsedMB / 1024}GB / ${dm.storageTotalMB / 1024}GB",
                        color = VulcanColors.CoolingForge)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Text("Battery", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${dm.batteryPercent}% ${if (dm.isCharging) "⚡" else ""}",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item { SectionHeader("Per-App Metrics") }

        items(appMetrics.entries.toList(), key = { it.key }) { (appId, m) ->
            ForgeCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(appId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("↑ ${m.uptimeMs / 60000}m", style = MaterialTheme.typography.labelSmall, color = VulcanColors.Ash)
                }
                Spacer(Modifier.height(12.dp))
                MetricsBar("RAM", m.ramMB / 512f, "${m.ramMB.toInt()}MB")
                Spacer(Modifier.height(8.dp))
                MetricsBar("CPU", m.cpuPercent / 100f, "${m.cpuPercent.toInt()}%",
                    color = VulcanColors.ForgeOrange)
            }
        }

        if (appMetrics.isEmpty()) {
            item { EmptyState("📊", "No metrics yet", "Start an app to see real-time metrics") }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val tier = viewModel.permissionTier
    val mode = viewModel.launchMode

    LazyColumn(
        contentPadding      = PaddingValues(VulcanDimens.paddingM),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Spacer(Modifier.height(8.dp)) }

        item { SettingsGroupHeader("Launcher") }
        item {
            SettingsRow(
                icon    = Icons.Default.Apps,
                title   = "Launch Mode",
                value   = mode.name,
                onClick = { viewModel.setLaunchMode(if (mode == LaunchMode.SLOT) LaunchMode.SHORTCUT else LaunchMode.SLOT) }
            )
        }

        item { SettingsGroupHeader("Permissions") }
        item {
            SettingsRow(
                icon  = Icons.Default.Security,
                title = "Permission Tier",
                value = tier.name,
                tint  = when (tier) {
                    PermissionTier.NORMAL -> VulcanColors.Ash
                    PermissionTier.ADB    -> VulcanColors.Starting
                    PermissionTier.ROOT   -> VulcanColors.CoolingForge
                }
            )
        }

        item { SettingsGroupHeader("Storage") }
        item {
            SettingsRow(
                icon  = Icons.Default.Folder,
                title = "Storage Root",
                value = "/sdcard/Vulcan"
            )
        }

        item { SettingsGroupHeader("Appearance") }
        item { SettingsRow(icon = Icons.Default.Palette, title = "Theme", value = "AMOLED") }

        item { SettingsGroupHeader("Developer") }
        item { SettingsRow(icon = Icons.Default.Code, title = "Developer Mode", value = "Off") }
        item { SettingsRow(icon = Icons.Default.BugReport, title = "View System Logs") }

        item { SettingsGroupHeader("About") }
        item { SettingsRow(icon = Icons.Default.Info, title = "Vulcan Version", value = "2.0.0") }
        item { SettingsRow(icon = Icons.Default.Code, title = "Open Source Licenses") }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
        style    = MaterialTheme.typography.labelMedium,
        color    = VulcanColors.ForgeOrange,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector, title: String, value: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null
) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier            = modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment   = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

// ─────────────────────────────────────────────────────────────────────────────
// SETUP WIZARD — 7 screens, first-run experience
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SetupWizardScreen(viewModel: MainViewModel, onComplete: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }

    AnimatedContent(targetState = step, label = "wizard_step",
        transitionSpec = { slideInHorizontally { it } togetherWith slideOutHorizontally { -it } }
    ) { currentStep ->
        when (currentStep) {
            0 -> WizardWelcome       { step++ }
            1 -> WizardLaunchMode(viewModel)   { step++ }
            2 -> WizardStorage       { step++ }
            3 -> WizardPermissions(viewModel)  { step++ }
            4 -> WizardADBSetup(viewModel)     { step++ }
            5 -> WizardFirstApp(viewModel)     { step++ }
            6 -> WizardComplete { viewModel.completeSetup(); onComplete() }
        }
    }
}

@Composable
private fun WizardWelcome(onNext: () -> Unit) {
    WizardPage(
        emoji    = "🔥",
        title    = "Welcome to Vulcan",
        subtitle = "Your Android. Your Rules. Your Stack.\n\nRun self-hosted apps on your phone — no PC, no root, no subscriptions.",
        onNext   = onNext,
        nextText = "Let's Begin"
    )
}

@Composable
private fun WizardLaunchMode(viewModel: MainViewModel, onNext: () -> Unit) {
    var selected by remember { mutableStateOf(LaunchMode.SLOT) }
    Column(
        Modifier.fillMaxSize().padding(VulcanDimens.paddingL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Choose Launch Mode", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("How should apps appear on your home screen?",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        LaunchModeOption("Slot Mode", "Real app icons (max 10). Requires ADB or Root for silent install.", LaunchMode.SLOT, selected) { selected = it }
        Spacer(Modifier.height(16.dp))
        LaunchModeOption("Shortcut Mode", "Pinned shortcuts. Unlimited. Works on all launchers.", LaunchMode.SHORTCUT, selected) { selected = it }

        Spacer(Modifier.height(32.dp))
        VulcanButton("Continue", { viewModel.setLaunchMode(selected); onNext() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun LaunchModeOption(title: String, desc: String, mode: LaunchMode, selected: LaunchMode, onSelect: (LaunchMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(mode) },
        shape    = RoundedCornerShape(VulcanDimens.radiusL),
        border   = BorderStroke(2.dp, if (selected == mode) VulcanColors.ForgeOrange else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(VulcanDimens.paddingM), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == mode, onClick = { onSelect(mode) },
                colors = RadioButtonDefaults.colors(selectedColor = VulcanColors.ForgeOrange))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WizardStorage(onNext: () -> Unit) {
    WizardPage(
        emoji    = "📁",
        title    = "Grant Storage Access",
        subtitle = "Vulcan stores all app data in /sdcard/Vulcan/\n\nYou can open it in any file manager, edit configs, view logs — total transparency.",
        onNext   = onNext,
        nextText = "Grant Access"
    )
}

@Composable
private fun WizardPermissions(viewModel: MainViewModel, onNext: () -> Unit) {
    WizardPage(
        emoji    = "⚡",
        title    = "Permission Level",
        subtitle = "Normal → Works great. ADB → 10× more powerful (one-time setup). Root → Full forge power.\n\nYou can change this later in Settings.",
        onNext   = { viewModel.setPermissionTier(PermissionTier.NORMAL); onNext() },
        nextText = "Start with Normal"
    )
}

@Composable
private fun WizardADBSetup(viewModel: MainViewModel, onNext: () -> Unit) {
    WizardPage(emoji = "🔌", title = "ADB Setup (Optional)",
        subtitle = "Pair with ADB once to unlock slot mode, OOM protection, and silent home screen icons.\n\nSkip for now — upgrade anytime in Settings.",
        onNext = onNext, nextText = "Skip for Now"
    )
}

@Composable
private fun WizardFirstApp(viewModel: MainViewModel, onNext: () -> Unit) {
    WizardPage(emoji = "⚒️", title = "Install Your First App",
        subtitle = "Try LibreChat — a beautiful AI chat interface that runs entirely on your phone.\nFree. Private. Yours.",
        onNext = onNext, nextText = "I'll browse the Store"
    )
}

@Composable
private fun WizardComplete(onComplete: () -> Unit) {
    WizardPage(emoji = "🔥", title = "The Forge is Ready",
        subtitle = "Vulcan is set up and ready to run.\n\nYour Android. Your Rules. Your Stack.",
        onNext = onComplete, nextText = "Enter the Forge"
    )
}

@Composable
private fun WizardPage(emoji: String, title: String, subtitle: String, onNext: () -> Unit, nextText: String) {
    Column(
        Modifier.fillMaxSize().padding(VulcanDimens.paddingL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 64.sp)
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp)
        Spacer(Modifier.height(48.dp))
        VulcanButton(nextText, onNext, modifier = Modifier.fillMaxWidth())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INSTALL PROGRESS DIALOG
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun InstallProgressDialog(state: MainViewModel.InstallState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (state is MainViewModel.InstallState.Success || state is MainViewModel.InstallState.Error) onDismiss() },
        title = {
            Text(when (state) {
                is MainViewModel.InstallState.Installing -> "Installing ${state.appId}"
                is MainViewModel.InstallState.Success    -> "Installed! ✓"
                is MainViewModel.InstallState.Error      -> "Install Failed"
                else -> ""
            })
        },
        text = {
            Column {
                when (state) {
                    is MainViewModel.InstallState.Installing -> {
                        Text(state.step, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress       = { state.progress },
                            modifier       = Modifier.fillMaxWidth(),
                            color          = VulcanColors.ForgeOrange,
                            trackColor     = VulcanColors.ForgeOrange.copy(alpha = 0.2f)
                        )
                    }
                    is MainViewModel.InstallState.Success ->
                        Text("${state.appId} is ready to run!", style = MaterialTheme.typography.bodyMedium)
                    is MainViewModel.InstallState.Error ->
                        Text(state.message, style = MaterialTheme.typography.bodyMedium,
                            color = VulcanColors.HotMetal)
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (state is MainViewModel.InstallState.Success || state is MainViewModel.InstallState.Error) {
                VulcanButton("OK", onDismiss)
            }
        }
    )
}

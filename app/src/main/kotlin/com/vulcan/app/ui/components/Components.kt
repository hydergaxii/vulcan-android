package com.vulcan.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.*
import com.vulcan.app.data.model.AppStatus
import com.vulcan.app.data.model.InstalledApp
import com.vulcan.app.ui.theme.*

// ─── STATUS BADGE ─────────────────────────────────────────────────────────────

@Composable
fun StatusBadge(status: AppStatus, modifier: Modifier = Modifier) {
    val (color, text) = when (status) {
        AppStatus.RUNNING    -> VulcanColors.Running  to "Running"
        AppStatus.STARTING   -> VulcanColors.Starting to "Starting"
        AppStatus.STOPPING   -> VulcanColors.Ash      to "Stopping"
        AppStatus.STOPPED    -> VulcanColors.Stopped  to "Stopped"
        AppStatus.ERROR      -> VulcanColors.Error    to "Error"
        AppStatus.INSTALLING -> VulcanColors.ForgeOrange to "Installing"
        AppStatus.UPDATING   -> VulcanColors.ForgeOrange to "Updating"
        AppStatus.BACKING_UP -> VulcanColors.Ash      to "Backing up"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        // Pulse dot for running state
        if (status == AppStatus.RUNNING) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 0.3f, label = "alpha",
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

// ─── APP CARD ─────────────────────────────────────────────────────────────────

@Composable
fun AppCard(
    app: InstalledApp,
    status: AppStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRunning = status == AppStatus.RUNNING
    val isBusy    = status in listOf(AppStatus.STARTING, AppStatus.STOPPING, AppStatus.INSTALLING)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (isRunning) onOpen() }, onLongClick = onLongPress),
        shape     = RoundedCornerShape(VulcanDimens.radiusL),
        colors    = CardDefaults.cardColors(
            containerColor = if (isRunning)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        border    = if (isRunning) BorderStroke(1.dp, VulcanColors.Running.copy(alpha = 0.4f)) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(VulcanDimens.paddingM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon placeholder (real icon loaded via Coil)
            Box(
                modifier = Modifier
                    .size(VulcanDimens.appIconSize)
                    .clip(RoundedCornerShape(VulcanDimens.radiusM))
                    .background(Color(app.brandColor).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(app.brandColor)
                )
            }

            Spacer(Modifier.width(VulcanDimens.paddingM))

            Column(Modifier.weight(1f)) {
                Text(
                    text     = app.label,
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusBadge(status)
                    Text(
                        text  = "port ${app.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Action button
            when {
                isBusy -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color    = VulcanColors.ForgeOrange,
                    strokeWidth = 3.dp
                )
                isRunning -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallIconButton(Icons.Default.OpenInBrowser, "Open", onOpen)
                    SmallIconButton(Icons.Default.Stop, "Stop", onStop, tint = VulcanColors.HotMetal)
                }
                else -> SmallIconButton(Icons.Default.PlayArrow, "Start", onStart, tint = VulcanColors.CoolingForge)
            }
        }
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, tint: Color = VulcanColors.ForgeOrange) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ─── VULCAN BUTTON ────────────────────────────────────────────────────────────

@Composable
fun VulcanButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    danger: Boolean = false
) {
    Button(
        onClick  = onClick,
        enabled  = enabled && !loading,
        modifier = modifier,
        colors   = ButtonDefaults.buttonColors(
            containerColor = if (danger) VulcanColors.HotMetal else VulcanColors.ForgeOrange
        ),
        shape = RoundedCornerShape(VulcanDimens.radiusM)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier    = Modifier.size(18.dp),
                color       = Color.White,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

// ─── METRICS BAR ─────────────────────────────────────────────────────────────

@Composable
fun MetricsBar(
    label: String,
    value: Float,          // 0.0 to 1.0
    valueText: String,
    color: Color = VulcanColors.ForgeOrange,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress     = { value.coerceIn(0f, 1f) },
            modifier     = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color        = color,
            trackColor   = color.copy(alpha = 0.15f)
        )
    }
}

// ─── LOG VIEWER ───────────────────────────────────────────────────────────────

@Composable
fun LogViewer(
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) { scrollState.animateScrollTo(scrollState.maxValue) }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(VulcanDimens.radiusM))
            .padding(VulcanDimens.paddingM)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            lines.forEach { line ->
                val color = when {
                    line.contains("ERROR") || line.contains("error") -> VulcanColors.HotMetal
                    line.contains("WARN")  || line.contains("warn")  -> VulcanColors.Starting
                    line.contains("INFO")  || line.contains("info")  -> Color(0xFF64B5F6)
                    else -> Color(0xFFB0BEC5)
                }
                Text(
                    text  = line,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = color
                )
            }
        }
    }
}

// ─── SECTION HEADER ──────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String, action: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick) {
                Text(label, color = VulcanColors.ForgeOrange, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ─── EMPTY STATE ─────────────────────────────────────────────────────────────

@Composable
fun EmptyState(icon: String, title: String, subtitle: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(VulcanDimens.paddingXL),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(24.dp))
            VulcanButton(label, onClick)
        }
    }
}

// ─── FORGE CARD (stats card) ─────────────────────────────────────────────────

@Composable
fun ForgeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier  = modifier,
        shape     = RoundedCornerShape(VulcanDimens.radiusL),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        content   = { Column(Modifier.padding(VulcanDimens.paddingM), content = content) }
    )
}

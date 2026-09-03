package com.copix.androidtaktracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copix.androidtaktracker.core.status.OperatorStatus

/**
 * Battery level plus the one Android setting that decides whether a long-running tracker
 * survives: the battery-optimization exemption. Persistent but calm while missing; silent
 * once granted.
 */
@Composable
fun BatteryStatusCard(
    status: OperatorStatus,
    charging: Boolean,
    onFixBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = if (status.batteryWarning) {
        CardDefaults.cardColors(containerColor = scheme.surfaceVariant, contentColor = scheme.onSurfaceVariant)
    } else {
        CardDefaults.cardColors()
    }
    val icon = when {
        status.batteryWarning -> Icons.Outlined.BatteryAlert
        charging -> Icons.Outlined.BatteryChargingFull
        else -> Icons.Outlined.BatteryStd
    }
    Card(modifier = modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = if (status.batteryWarning) scheme.tertiary else scheme.primary)
                Text("Battery", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { LabelValue("Battery", status.batteryLabel) }
                Column(Modifier.weight(1f)) { LabelValue("Battery optimization", status.batteryOptimizationLabel) }
            }
            if (status.batteryWarning) {
                Text(
                    "Background tracking may be restricted by Android battery optimization.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onFixBatterySettings) { Text("Fix battery settings") }
            }
        }
    }
}

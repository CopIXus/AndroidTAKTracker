package com.copix.androidtaktracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copix.androidtaktracker.core.status.OperatorStatus
import com.copix.androidtaktracker.core.status.TrackingState

/**
 * The two-second answer: are we tracking, and if not, why. Colour comes from the Material
 * container roles so the same state reads the same in light and dark themes; the icon
 * carries the meaning for operators who cannot rely on colour.
 */
@Composable
fun TrackingStatusCard(
    status: OperatorStatus,
    paused: Boolean,
    onTogglePause: () -> Unit,
    pauseEnabled: Boolean = true,
    pauseHint: String? = null,
    managedByMdm: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (status.state) {
        TrackingState.TRACKING -> scheme.primaryContainer to scheme.onPrimaryContainer
        TrackingState.PAUSED, TrackingState.ATAK_DEFERRED -> scheme.secondaryContainer to scheme.onSecondaryContainer
        TrackingState.WAITING_FOR_LOCATION -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        TrackingState.TAK_DISCONNECTED,
        TrackingState.LOCATION_PERMISSION_REQUIRED,
        TrackingState.NO_SERVER_CONFIGURED,
        -> scheme.errorContainer to scheme.onErrorContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    imageVector = stateIcon(status.state),
                    contentDescription = status.headline,
                    modifier = Modifier.size(44.dp),
                    tint = onContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        status.headline,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                    )
                    Text(
                        status.callsign,
                        style = MaterialTheme.typography.titleMedium,
                        color = onContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(status.summary, style = MaterialTheme.typography.bodyMedium, color = onContainer.copy(alpha = 0.9f))
            Spacer(Modifier.height(12.dp))
            if (paused) {
                Button(onClick = onTogglePause, enabled = pauseEnabled) { Text("Resume tracking") }
            } else {
                OutlinedButton(onClick = onTogglePause, enabled = pauseEnabled) { Text("Pause tracking") }
            }
            if (managedByMdm || !pauseHint.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (managedByMdm) ManagedByMdmPill()
                    if (!pauseHint.isNullOrBlank()) {
                        Text(pauseHint, style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

private fun stateIcon(state: TrackingState): ImageVector = when (state) {
    TrackingState.TRACKING -> Icons.Outlined.GpsFixed
    TrackingState.PAUSED -> Icons.Outlined.PauseCircle
    TrackingState.ATAK_DEFERRED -> Icons.Outlined.Layers
    TrackingState.WAITING_FOR_LOCATION -> Icons.Outlined.LocationSearching
    TrackingState.TAK_DISCONNECTED -> Icons.Outlined.CloudOff
    TrackingState.LOCATION_PERMISSION_REQUIRED -> Icons.Outlined.LocationOff
    TrackingState.NO_SERVER_CONFIGURED -> Icons.Outlined.Dns
}

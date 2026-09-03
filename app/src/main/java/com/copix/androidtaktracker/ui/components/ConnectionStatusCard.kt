package com.copix.androidtaktracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copix.androidtaktracker.core.status.ConnectionState
import com.copix.androidtaktracker.core.status.OperatorStatus

/**
 * TAK link in operator terms. Never a bare red "Disconnected" when there is a better
 * explanation (no network, reconnecting, fail2ban guard tripped, waiting for first PLI).
 */
@Composable
fun ConnectionStatusCard(
    status: OperatorStatus,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (status.connection) {
        ConnectionState.CONNECTED, ConnectionState.CONNECTED_WAITING_FIRST_PLI -> scheme.primary
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.NO_NETWORK -> scheme.tertiary
        ConnectionState.CIRCUIT_OPEN, ConnectionState.ERROR, ConnectionState.DISCONNECTED, ConnectionState.NO_SERVER -> scheme.error
    }
    val showServersAction = status.connection in setOf(
        ConnectionState.CIRCUIT_OPEN,
        ConnectionState.ERROR,
        ConnectionState.NO_SERVER,
        ConnectionState.DISCONNECTED,
    )

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(connectionIcon(status.connection), contentDescription = null, tint = tint)
                Text(status.connectionTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            status.connectionDetail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.connection == ConnectionState.CONNECTED) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
            if (showServersAction) {
                TextButton(onClick = onOpenServers) { Text("Open Servers") }
            }
        }
    }
}

private fun connectionIcon(c: ConnectionState): ImageVector = when (c) {
    ConnectionState.CONNECTED -> Icons.Outlined.CloudDone
    ConnectionState.CONNECTED_WAITING_FIRST_PLI -> Icons.Outlined.Cloud
    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> Icons.Outlined.CloudSync
    ConnectionState.NO_NETWORK -> Icons.Outlined.WifiOff
    ConnectionState.CIRCUIT_OPEN -> Icons.Outlined.PauseCircle
    ConnectionState.ERROR -> Icons.Outlined.ErrorOutline
    ConnectionState.DISCONNECTED -> Icons.Outlined.CloudOff
    ConnectionState.NO_SERVER -> Icons.Outlined.Dns
}

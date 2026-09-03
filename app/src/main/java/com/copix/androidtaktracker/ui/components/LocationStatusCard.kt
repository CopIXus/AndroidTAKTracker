package com.copix.androidtaktracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Public
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
import com.copix.androidtaktracker.core.status.LocationQuality
import com.copix.androidtaktracker.core.status.OperatorStatus

/** Location quality and age — never raw coordinates (those live under Diagnostics). */
@Composable
fun LocationStatusCard(
    status: OperatorStatus,
    onFixPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (status.location) {
        LocationQuality.GPS, LocationQuality.FUSED -> scheme.primary
        LocationQuality.HELD, LocationQuality.NETWORK_IP, LocationQuality.WAITING -> scheme.tertiary
        LocationQuality.PERMISSION_REQUIRED -> scheme.error
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(locationIcon(status.location), contentDescription = null, tint = tint)
                Text("Location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(status.locationLine, style = MaterialTheme.typography.bodyLarge)
            if (status.location == LocationQuality.PERMISSION_REQUIRED) {
                TextButton(onClick = onFixPermission) { Text("Grant location permission") }
            }
        }
    }
}

private fun locationIcon(q: LocationQuality): ImageVector = when (q) {
    LocationQuality.GPS -> Icons.Outlined.GpsFixed
    LocationQuality.FUSED -> Icons.Outlined.MyLocation
    LocationQuality.HELD -> Icons.Outlined.GpsNotFixed
    LocationQuality.NETWORK_IP -> Icons.Outlined.Public
    LocationQuality.WAITING -> Icons.Outlined.LocationSearching
    LocationQuality.PERMISSION_REQUIRED -> Icons.Outlined.LocationOff
}

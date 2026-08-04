package com.copix.androidtaktracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.ui.graphics.vector.ImageVector

enum class SettingsSection(val title: String, val icon: ImageVector) {
    Status("Status", Icons.Outlined.Dashboard),
    Servers("Servers", Icons.Outlined.Dns),
    Identity("Identity", Icons.Outlined.Person),
    Gps("GPS", Icons.Outlined.MyLocation),
    Reporting("Reporting", Icons.Outlined.Speed),
    MeshSa("Mesh SA", Icons.Outlined.CellTower),
    Companions("Companion apps", Icons.Outlined.Apps),
    Startup("Startup", Icons.Outlined.PowerSettingsNew),
    Diagnostics("Diagnostics", Icons.Outlined.BugReport),
    Updates("Updates", Icons.Outlined.SystemUpdate),
    About("About", Icons.Outlined.Info),
}

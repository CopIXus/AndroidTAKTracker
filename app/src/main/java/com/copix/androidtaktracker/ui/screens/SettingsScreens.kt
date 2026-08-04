package com.copix.androidtaktracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copix.androidtaktracker.BuildConfig
import com.copix.androidtaktracker.core.identity.IdentityResolver
import com.copix.androidtaktracker.core.tak.TakConnectionState
import com.copix.androidtaktracker.host.TrackingHost
import com.copix.androidtaktracker.ui.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teams = listOf(
    "Cyan", "Blue", "Green", "Yellow", "Orange", "Red", "Purple", "Magenta", "Maroon", "Teal", "White",
)
private val Roles = listOf(
    "Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9",
)

@Composable
fun SectionContent(section: SettingsSection, host: TrackingHost, onOpenQr: () -> Unit) {
    when (section) {
        SettingsSection.Status -> StatusScreen(host)
        SettingsSection.Servers -> ServersScreen(host, onOpenQr)
        SettingsSection.Identity -> IdentityScreen(host)
        SettingsSection.Gps -> GpsScreen(host)
        SettingsSection.Reporting -> ReportingScreen(host)
        SettingsSection.MeshSa -> MeshScreen(host)
        SettingsSection.Companions -> CompanionsScreen()
        SettingsSection.Startup -> StartupScreen(host)
        SettingsSection.Diagnostics -> DiagnosticsScreen(host)
        SettingsSection.Updates -> UpdatesScreen(host)
        SettingsSection.About -> AboutScreen()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val fix by host.gps.fix.collectAsState()
    val paused by host.paused.collectAsState()
    val states by host.serverStates.collectAsState()
    val identity = IdentityResolver.resolve(config)
    val defer = host.atak.shouldDefer(config.atak.deferToAtak)
    val connected = states.values.count { it == TakConnectionState.CONNECTED }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("Tracking-only TAK PLI client. Map clients show your position from CoT.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Mode", if (paused) "Paused" else if (defer) "Defer ATAK" else "Tracking")
            Chip("Callsign", "${identity.callsign} (${identity.source})")
            Chip("GPS", fix?.source?.name ?: "No fix")
            Chip("Servers", "$connected connected")
            Chip("Last PLI", formatTime(host.lastPliEpochMs))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { host.setPaused(!paused) }) {
                Text(if (paused) "Resume tracking" else "Pause tracking")
            }
        }
        if (fix != null) {
            Text("Position: ${"%.5f".format(fix!!.latitude)}, ${"%.5f".format(fix!!.longitude)}")
            Text(
                "Accuracy: ${fix!!.accuracyMeters?.let { "%.0f m".format(it) } ?: "—"} · " +
                    "Speed: ${fix!!.speedMetersPerSecond?.let { "%.1f m/s".format(it) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ServersScreen(host: TrackingHost, onOpenQr: () -> Unit) {
    val config by host.config.collectAsState()
    val states by host.serverStates.collectAsState()
    val unlocked by host.settingsUnlocked.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var enrollText by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8089") }
    var message by remember { mutableStateOf<String?>(null) }
    val managed = host.mdm.managedKeys.collectAsState().value
    val editable = unlocked || !host.isSettingsLocked

    val softCertPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            message = if (bytes == null) "Could not read SoftCert ZIP."
            else host.importSoftCertZip(bytes).message
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("Add TAK servers via QR, enrollment URL, SoftCert ZIP, or manual host. Fake hosts only in samples.")
        if ("enrollUrl" in managed || "serverHost" in managed) ManagedBadge()
        if (!editable) Blurb("Settings are locked — unlock under Diagnostics to edit.")
        config.servers.forEach { server ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = server.enabled,
                            enabled = editable,
                            onCheckedChange = { en ->
                                host.saveConfig { c -> c.servers.find { it.id == server.id }?.enabled = en }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(server.displayName, fontWeight = FontWeight.SemiBold)
                            Text("${server.host}:${server.port} (${server.protocol})", style = MaterialTheme.typography.bodySmall)
                        }
                        Chip("Status", states[server.id]?.name ?: "—")
                    }
                    TextButton(enabled = editable, onClick = {
                        host.saveConfig { c -> c.servers.removeAll { it.id == server.id } }
                    }) { Text("Remove") }
                }
            }
        }
        OutlinedTextField(
            value = enrollText,
            onValueChange = { enrollText = it },
            label = { Text("Enrollment URL or iTAK CSV") },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable && "enrollUrl" !in managed,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = editable && "enrollUrl" !in managed, onClick = {
                scope.launch {
                    val r = host.enroll(enrollText)
                    message = r.message
                    if (r.success) enrollText = ""
                }
            }) { Text("Apply") }
            OutlinedButton(enabled = editable, onClick = onOpenQr) { Text("Scan QR") }
            OutlinedButton(enabled = editable, onClick = { softCertPicker.launch("application/zip") }) { Text("Import SoftCert") }
        }
        OutlinedTextField(
            value = manualHost,
            onValueChange = { manualHost = it },
            label = { Text("Manual host (tak.example.com)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable && "serverHost" !in managed,
        )
        OutlinedTextField(
            value = manualPort,
            onValueChange = { manualPort = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable && "serverHost" !in managed,
        )
        Button(
            enabled = editable && "serverHost" !in managed && manualHost.isNotBlank(),
            onClick = {
                host.saveConfig { c ->
                    c.servers.add(
                        com.copix.androidtaktracker.core.config.ServerProfile(
                            id = java.util.UUID.randomUUID().toString().replace("-", ""),
                            displayName = manualHost.trim(),
                            host = manualHost.trim(),
                            port = manualPort.toIntOrNull() ?: 8089,
                            protocol = "ssl",
                        ),
                    )
                }
                manualHost = ""
                message = "Server added."
            },
        ) { Text("Add manual server") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
@Composable
private fun IdentityScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val managed = host.mdm.managedKeys.collectAsState().value
    var callsign by remember(config.userIdentity.callsign) { mutableStateOf(config.userIdentity.callsign) }
    var team by remember(config.userIdentity.team) {
        mutableStateOf(config.userIdentity.team.ifBlank { config.deviceIdentity.team })
    }
    var role by remember(config.userIdentity.role) {
        mutableStateOf(config.userIdentity.role.ifBlank { config.deviceIdentity.role })
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("My callsign is used for CoT. Portal pushes append .att automatically.")
        if ("callsign" in managed) ManagedBadge()
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it },
            label = { Text("My callsign") },
            modifier = Modifier.fillMaxWidth(),
            enabled = "callsign" !in managed,
        )
        EnumDropdown("Team", team, Teams, "team" !in managed) { team = it }
        EnumDropdown("Role", role, Roles, "role" !in managed) { role = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.applyRemoteIdentityFromPortal,
                onCheckedChange = { v -> host.saveConfig { it.applyRemoteIdentityFromPortal = v } },
            )
            Text("Apply callsign/team from Portal / device-profile sync")
        }
        OutlinedTextField(
            value = config.deviceIdentity.callsign,
            onValueChange = { v -> host.saveConfig { it.deviceIdentity.callsign = v } },
            label = { Text("Device callsign (fallback)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            host.saveConfig {
                it.userIdentity.callsign = callsign.trim()
                it.userIdentity.team = team
                it.userIdentity.role = role
            }
        }) { Text("Save identity") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpsScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val fix by host.gps.fix.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("Fused location (GNSS/Wi‑Fi). Optional IP geolocation fallback via ipwho.is.")
        EnumDropdown(
            "Source priority",
            config.gps.sourcePriority,
            listOf("FusedOnly", "FusedThenNetwork", "NetworkOnly"),
            true,
        ) { v -> host.saveConfig { it.gps.sourcePriority = v } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.gps.enableNetworkFallback,
                onCheckedChange = { v -> host.saveConfig { it.gps.enableNetworkFallback = v } },
            )
            Text("Network / IP fallback")
        }
        OutlinedTextField(
            value = config.gps.lastFixHoldSeconds.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { n -> host.saveConfig { it.gps.lastFixHoldSeconds = n } }
            },
            label = { Text("Last-fix hold (s)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Current: ${fix?.let { "${it.latitude}, ${it.longitude} (${it.source})" } ?: "none"}")
        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }) { Text("Open location settings") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportingScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            "Strategy",
            config.reporting.strategy,
            listOf("Dynamic", "Constant"),
            true,
        ) { v -> host.saveConfig { it.reporting.strategy = v } }
        OutlinedTextField(
            value = config.reporting.constantIntervalSeconds.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { n -> host.saveConfig { it.reporting.constantIntervalSeconds = n.coerceAtLeast(5) } }
            },
            label = { Text("Constant interval (s)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.reporting.reliableStationarySeconds.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { n -> host.saveConfig { it.reporting.reliableStationarySeconds = n } }
            },
            label = { Text("Reliable stationary (s)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.reporting.includeDeviceNameInRemarks,
                onCheckedChange = { v -> host.saveConfig { it.reporting.includeDeviceNameInRemarks = v } },
            )
            Text("Include device name in CoT remarks")
        }
        EnumDropdown(
            "Defer to ATAK",
            config.atak.deferToAtak,
            listOf("Off", "WhenRunning", "WhenHeardOnMesh"),
            true,
        ) { v -> host.saveConfig { it.atak.deferToAtak = v } }
        Blurb("When ATAK is active, AndroidTAKTracker suppresses its own PLI to avoid duplicate markers.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    var testMsg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.meshSa.enabled,
                onCheckedChange = { v -> host.saveConfig { it.meshSa.enabled = v } },
            )
            Text("Broadcast Mesh SA", modifier = Modifier.padding(start = 8.dp))
        }
        EnumDropdown(
            "Mode",
            config.meshSa.mode,
            listOf("Always", "OnlyWhenDisconnected"),
            true,
        ) { v -> host.saveConfig { it.meshSa.mode = v } }
        Text("Multicast ${config.meshSa.multicastAddress}:${config.meshSa.multicastPort}")
        Blurb("Many Wi‑Fi APs block multicast — test with a map client on the same LAN.")
        OutlinedButton(onClick = {
            testMsg = if (host.sendTestMeshSa()) "Test Mesh SA sent."
            else "Send failed (enable Mesh SA / check Wi‑Fi multicast)."
        }) { Text("Send test Mesh SA now") }
        host.mesh.lastInterfaceDescription?.let { Blurb("Interface: $it") }
        testMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
@Composable
private fun CompanionsScreen() {
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("AndroidTAKTracker is tracking-only. Use a map client for the COP. Video is not built in.")
        LinkButton("ATAK-CIV (Play Store)", "https://play.google.com/store/apps/details?id=com.atakmap.app.civ")
        LinkButton(
            "ICU VideoStreamer TAK plugin",
            "https://github.com/jpat-12/TAK-PluginSuite-ICU_VideoStreamer/releases/tag/2.4.0",
        )
        LinkButton("TAK.gov", "https://tak.gov")
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tak.gov")))
        }) { Text("Open TAK.gov") }
    }
}

@Composable
private fun StartupScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.startup.startOnBoot,
                onCheckedChange = { v -> host.saveConfig { it.startup.startOnBoot = v } },
            )
            Text("Start when phone boots", modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.startup.preventSleepWhileTracking,
                onCheckedChange = { v -> host.saveConfig { it.startup.preventSleepWhileTracking = v } },
            )
            Text("Prevent sleep while tracking", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${ctx.packageName}")
            }
            runCatching { ctx.startActivity(i) }
        }) { Text("Battery optimization exemption") }
        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }) { Text("Usage access (ATAK detection)") }
        Blurb("On Headwind MDM fleets, prefer device-owner keep-alive / kiosk policies.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val unlocked by host.settingsUnlocked.collectAsState()
    val ctx = LocalContext.current
    var lockPassword by remember { mutableStateOf("") }
    var lockMsg by remember { mutableStateOf<String?>(null) }
    val canEdit = unlocked || !host.isSettingsLocked

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EnumDropdown(
            "Log level",
            config.diagnostics.logLevel,
            listOf("Debug", "Information", "Warning", "Error"),
            canEdit,
        ) { v -> host.saveConfig { it.diagnostics.logLevel = v } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.diagnostics.allowInsecureTlsSoftAccept,
                enabled = canEdit,
                onCheckedChange = { v -> host.saveConfig { it.diagnostics.allowInsecureTlsSoftAccept = v } },
            )
            Text("Allow insecure TLS soft-accept (lab only)")
        }
        Blurb("Device UID: ${config.deviceUid}")
        Blurb("ATAK installed: ${host.atak.installed.value} · running: ${host.atak.running.value}")
        OutlinedButton(onClick = {
            val json = host.exportStatusJson()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "AndroidTAKTracker status")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            ctx.startActivity(Intent.createChooser(send, "Export status"))
        }) { Text("Export redacted status") }

        Text("Settings lock", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = lockPassword,
            onValueChange = { lockPassword = it },
            label = { Text(if (host.isSettingsLocked && !unlocked) "Unlock password" else "New lock password") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (host.isSettingsLocked && !unlocked) {
                Button(onClick = {
                    lockMsg = if (host.unlockSettings(lockPassword)) {
                        lockPassword = ""
                        "Unlocked."
                    } else "Incorrect password."
                }) { Text("Unlock") }
            } else {
                Button(onClick = {
                    host.setSettingsLock(lockPassword.takeIf { it.isNotBlank() })
                    lockPassword = ""
                    lockMsg = if (host.isSettingsLocked) "Settings locked." else "Settings lock cleared."
                }) { Text(if (lockPassword.isBlank()) "Clear lock" else "Set lock") }
            }
        }
        lockMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}
@Composable
private fun UpdatesScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val last by host.lastUpdate.collectAsState()
    val mdm by host.mdm.mdmPresent.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var installMsg by remember { mutableStateOf<String?>(null) }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Package", "AndroidTAKTracker.apk")
        Chip("Current", BuildConfig.VERSION_NAME)
        Chip("Latest", last?.latestVersion ?: "—")
        Chip(
            "Status",
            when {
                last == null -> "Not checked yet"
                !last!!.success -> last!!.error ?: "Check failed"
                last!!.updateAvailable -> "Update available (${last!!.latestVersion})"
                else -> "Up to date"
            },
        )
        if (busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Working…", color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try { host.checkUpdates() } finally { busy = false }
                    }
                },
            ) { Text("Check for updates") }
            Button(
                enabled = !busy && !mdm && last?.updateAvailable == true,
                onClick = {
                    busy = true
                    scope.launch {
                        try { installMsg = host.downloadAndInstallUpdate() }
                        finally { busy = false }
                    }
                },
            ) { Text("Download & install") }
        }
        installMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.updates.automaticallyDownloadAndInstall && !mdm,
                onCheckedChange = { v ->
                    host.saveConfig { it.updates.automaticallyDownloadAndInstall = v && !mdm }
                },
                enabled = !mdm,
            )
            Text(
                if (mdm) "Auto-update disabled (MDM-managed install)"
                else "Automatically download and install updates",
            )
        }
        val notes = last?.changelogNotes ?: last?.releaseNotes
        if (!notes.isNullOrBlank() && last?.updateAvailable == true) {
            Text(
                if (!last!!.changelogNotes.isNullOrBlank()) "What's new in ${last!!.latestVersion}"
                else "Release notes",
                fontWeight = FontWeight.SemiBold,
            )
            Card(Modifier.fillMaxWidth()) {
                Text(
                    notes,
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
@Composable
private fun AboutScreen() {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AndroidTAKTracker", style = MaterialTheme.typography.headlineSmall)
        Text("Version ${BuildConfig.VERSION_NAME}")
        Text("CopIX LLC")
        Text("AndroidTAKTracker Free Application License 1.0")
        Blurb("Sibling of WinTAKTracker. Tracking-only — no COP, no built-in video.")
        LinkButton("GitHub", "https://github.com/CopIXus/AndroidTAKTracker")
    }
}

@Composable
private fun Blurb(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(label: String, value: String) {
    FilterChip(selected = false, onClick = {}, label = { Text("$label: $value") })
}

@Composable
private fun ManagedBadge() {
    Text(
        "Managed by MDM",
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun LinkButton(label: String, url: String) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0) return "never"
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))
}

package com.copix.androidtaktracker.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copix.androidtaktracker.BuildConfig
import com.copix.androidtaktracker.R
import com.copix.androidtaktracker.core.mdm.MdmSettingsApply
import com.copix.androidtaktracker.core.reporting.GpsDuty
import com.copix.androidtaktracker.core.tak.TakConnectionState
import com.copix.androidtaktracker.ui.components.ManagedByMdmPill
import com.copix.androidtaktracker.host.TrackingHost
import com.copix.androidtaktracker.ui.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Teams = listOf(
    "Cyan", "Blue", "Dark Blue", "Brown", "Green", "Dark Green",
    "Yellow", "Orange", "Red", "Purple", "Magenta", "Maroon", "Teal", "White",
)
private val Roles = listOf(
    "Team Member", "Team Lead", "HQ", "Sniper", "Medic", "Forward Observer", "RTO", "K9",
)

@Composable
fun SectionContent(
    section: SettingsSection,
    host: TrackingHost,
    onOpenQr: () -> Unit,
    onNavigate: (SettingsSection) -> Unit = {},
) {
    when (section) {
        SettingsSection.Status -> StatusScreen(host, onNavigate)
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

@Composable
private fun ServersScreen(host: TrackingHost, onOpenQr: () -> Unit) {
    val config by host.config.collectAsState()
    val statuses by host.serverStatuses.collectAsState()
    val enrollFeedback by host.lastEnrollFeedback.collectAsState()
    val gate = rememberEditGate(host)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var enrollText by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("8089") }
    var manualUser by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }
    var manualEnrollPort by remember { mutableStateOf("8446") }
    var manualBusy by remember { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var localOk by remember { mutableStateOf(true) }
    val serversOn = gate.serversEnabled

    val softCertPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes == null) {
                localMessage = "Could not read SoftCert ZIP."
                localOk = false
            } else {
                val r = host.importSoftCertZip(bytes)
                localMessage = r.message
                localOk = r.success
            }
        }
    }

    val banner = localMessage ?: enrollFeedback?.message
    val bannerOk = if (localMessage != null) localOk else enrollFeedback?.success != false

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("Add TAK servers via QR, enrollment URL, SoftCert ZIP, or manual host. Fake hosts only in samples.")
        LockBanner(gate)
        banner?.let {
            Text(
                it,
                color = if (bannerOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (enrollFeedback != null && localMessage == null) {
                TextButton(onClick = { host.clearEnrollFeedback() }) { Text("Dismiss") }
            }
        }
        config.servers.forEach { server ->
            val status = statuses[server.id]
            val showName = server.displayName.isNotBlank() &&
                !server.displayName.equals(server.host, ignoreCase = true) &&
                !server.displayName.equals("Server", ignoreCase = true)
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!gate.serversSetByMdm) {
                        Checkbox(
                            checked = server.enabled,
                            enabled = serversOn,
                            onCheckedChange = { en ->
                                host.saveConfig { c -> c.servers.find { it.id == server.id }?.enabled = en }
                            },
                        )
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(top = 10.dp, end = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (showName) {
                            Text(server.displayName, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "${server.host}:${server.port} (${server.protocol})",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ConnectionPill(status?.state)
                            if (gate.serversSetByMdm) ManagedByMdmPill()
                        }
                        val err = status?.lastErrorCode?.takeIf { it.isNotBlank() }
                        if (err != null && status.state != TakConnectionState.CONNECTED) {
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (!gate.serversSetByMdm) {
                        IconButton(
                            enabled = serversOn,
                            onClick = {
                                host.saveConfig { c -> c.servers.removeAll { it.id == server.id } }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "Remove",
                                tint = if (serversOn) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (gate.serversSetByMdm) ManagedByMdmPill()
        OutlinedTextField(
            value = enrollText,
            onValueChange = { enrollText = it },
            label = { Text("Enrollment URL or iTAK CSV") },
            modifier = Modifier.fillMaxWidth(),
            enabled = serversOn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = serversOn, onClick = {
                scope.launch {
                    val r = host.enroll(enrollText)
                    localMessage = r.message
                    localOk = r.success
                    if (r.success) enrollText = ""
                }
            }) { Text("Apply") }
            OutlinedButton(enabled = serversOn, onClick = onOpenQr) { Text("Scan QR") }
            OutlinedButton(enabled = serversOn, onClick = { softCertPicker.launch("application/zip") }) { Text("Import SoftCert") }
        }
        Blurb(
            "Manual server: enter host and port. With a username and password, WinTAKTracker-style " +
                "certificate enrollment runs against the Marti API (like ATAK Quick Connect). " +
                "Left blank, a bare profile is added — SSL servers will still need enrollment or a cert import.",
        )
        OutlinedTextField(
            value = manualHost,
            onValueChange = { manualHost = it },
            label = { Text("Manual host (tak.example.com)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = serversOn,
        )
        OutlinedTextField(
            value = manualPort,
            onValueChange = { manualPort = it },
            label = { Text("Streaming (CoT) port") },
            modifier = Modifier.fillMaxWidth(),
            enabled = serversOn,
        )
        OutlinedTextField(
            value = manualUser,
            onValueChange = { manualUser = it },
            label = { Text("Username (optional — enables cert enrollment)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = serversOn,
        )
        OutlinedTextField(
            value = manualPassword,
            onValueChange = { manualPassword = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            enabled = serversOn,
        )
        if (manualUser.isNotBlank()) {
            OutlinedTextField(
                value = manualEnrollPort,
                onValueChange = { manualEnrollPort = it },
                label = { Text("Enrollment port") },
                modifier = Modifier.fillMaxWidth(),
                enabled = serversOn,
            )
        }
        if (manualBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Button(
            enabled = serversOn && manualHost.isNotBlank() && !manualBusy,
            onClick = {
                if (manualUser.isNotBlank() && manualPassword.isNotBlank()) {
                    manualBusy = true
                    scope.launch {
                        try {
                            val r = host.enrollManual(
                                host = manualHost,
                                username = manualUser,
                                password = manualPassword,
                                streamPort = manualPort.toIntOrNull() ?: 8089,
                                enrollPort = manualEnrollPort.toIntOrNull() ?: 8446,
                            )
                            localMessage = r.message
                            localOk = r.success
                            if (r.success) {
                                manualHost = ""
                                manualUser = ""
                                manualPassword = ""
                            }
                        } finally {
                            manualBusy = false
                        }
                    }
                } else if (manualUser.isNotBlank()) {
                    localMessage = "Enter the password for certificate enrollment."
                    localOk = false
                } else {
                    val hostName = manualHost.trim()
                    val port = manualPort.toIntOrNull() ?: 8089
                    val existing = com.copix.androidtaktracker.core.config.ServerStreams.find(
                        config.servers, hostName, port, "ssl",
                    )
                    if (existing != null) {
                        localMessage = "$hostName:$port is already configured."
                        localOk = false
                    } else {
                        host.saveConfig { c ->
                            c.servers.add(
                                com.copix.androidtaktracker.core.config.ServerProfile(
                                    id = java.util.UUID.randomUUID().toString().replace("-", ""),
                                    displayName = hostName,
                                    host = hostName,
                                    port = port,
                                    protocol = "ssl",
                                ),
                            )
                        }
                        manualHost = ""
                        localMessage = "Server added (no certificate — enroll or import one for SSL)."
                        localOk = true
                    }
                }
            },
        ) { Text(if (manualUser.isNotBlank()) "Enroll & add server" else "Add manual server") }
    }
}
@Composable
private fun IdentityScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val gate = rememberEditGate(host)
    var callsign by remember(config.userIdentity.callsign) { mutableStateOf(config.userIdentity.callsign) }
    var team by remember(config.userIdentity.team) {
        mutableStateOf(config.userIdentity.team.ifBlank { config.deviceIdentity.team })
    }
    var role by remember(config.userIdentity.role) {
        mutableStateOf(config.userIdentity.role.ifBlank { config.deviceIdentity.role })
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb(
            "Android uses a single callsign (My callsign) for CoT — there is no separate device/computer " +
                "callsign like WinTAKTracker. Portal pushes append .att automatically.",
        )
        LockBanner(gate)
        val callsignOn = gate.enabled("callsign")
        val teamOn = gate.enabled("team")
        val roleOn = gate.enabled("role")
        val identityOwned = gate.setByMdm("callsign", "team", "role")
        SetByMdm(gate.setByMdm("callsign"))
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it },
            label = { Text("My callsign") },
            modifier = Modifier.fillMaxWidth(),
            enabled = callsignOn,
        )
        SetByMdm(gate.setByMdm("team"))
        EnumDropdown("Team", team, Teams, teamOn) { team = it }
        SetByMdm(gate.setByMdm("role"))
        EnumDropdown("Role", role, Roles, roleOn) { role = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.applyRemoteIdentityFromPortal,
                enabled = !gate.locked && !identityOwned,
                onCheckedChange = { v -> host.saveConfig { it.applyRemoteIdentityFromPortal = v } },
            )
            SettingLabel("Apply callsign/team/role from Portal / device-profile sync", !gate.locked && !identityOwned)
        }
        if (identityOwned) ManagedByMdmPill()
        Button(enabled = callsignOn || teamOn || roleOn, onClick = {
            host.saveConfig {
                val trimmed = callsign.trim()
                it.userIdentity.callsign = trimmed
                it.userIdentity.team = team
                it.userIdentity.role = role
                // Keep internal deviceIdentity aligned — Android has one operator callsign only.
                if (trimmed.isNotBlank()) it.deviceIdentity.callsign = trimmed
                it.deviceIdentity.team = team
                it.deviceIdentity.role = role
            }
        }) { Text("Save identity") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpsScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val gate = rememberEditGate(host)
    val fix by host.gps.fix.collectAsState()
    val gpsDuty by host.gps.duty.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb("Fused location (GNSS/Wi‑Fi). Optional IP geolocation fallback via ipwho.is.")
        LockBanner(gate)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.gps.adaptToMotion,
                enabled = gate.enabled(),
                onCheckedChange = { v -> host.saveConfig { it.gps.adaptToMotion = v } },
            )
            SettingLabel("Adapt GPS to motion (saves battery)", gate.enabled())
        }
        Blurb(
            "When still or walking slowly, GNSS steps down from high accuracy. A real move " +
                "or driving snaps back immediately. Pause and ATAK-defer also stop the GPS radio.",
        )
        EnumDropdown(
            "Source priority",
            config.gps.sourcePriority,
            listOf("FusedOnly", "FusedThenNetwork", "NetworkOnly"),
            gate.enabled(),
        ) { v -> host.saveConfig { it.gps.sourcePriority = v } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.gps.enableNetworkFallback,
                enabled = gate.enabled(),
                onCheckedChange = { v -> host.saveConfig { it.gps.enableNetworkFallback = v } },
            )
            SettingLabel("Network / IP fallback", gate.enabled())
        }
        OutlinedTextField(
            value = config.gps.lastFixHoldSeconds.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { n -> host.saveConfig { it.gps.lastFixHoldSeconds = n } }
            },
            label = { Text("Last-fix hold (s)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = gate.enabled(),
        )
        Text("Current: ${fix?.let { "${it.latitude}, ${it.longitude} (${it.source})" } ?: "none"}")
        Text("GPS duty: ${gpsDutyLabel(gpsDuty, config.gps.adaptToMotion)}")
        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }) { Text("Open location settings") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportingScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val gate = rememberEditGate(host)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Blurb(
            "Dynamic sends CoT less often when you are still or only shifting a few meters " +
                "(GPS jitter). Walking is slower than driving. Stale time stays long enough " +
                "that CloudTAK / ATAK keep your icon on the map between sends. Low battery " +
                "stretches Dynamic intervals; charging restores the normal cadence.",
        )
        LockBanner(gate)
        SetByMdm(gate.setByMdm("reportingStrategy"))
        EnumDropdown(
            "Strategy",
            config.reporting.strategy,
            listOf("Dynamic", "Constant"),
            gate.enabled("reportingStrategy"),
        ) { v -> host.saveConfig { it.reporting.strategy = v } }
        OutlinedTextField(
            value = config.reporting.constantIntervalSeconds.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { n -> host.saveConfig { it.reporting.constantIntervalSeconds = n.coerceAtLeast(5) } }
            },
            label = { Text("Constant interval (s)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = gate.enabled(),
        )
        OutlinedTextField(
            value = config.reporting.reliableStationarySeconds.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.let { n -> host.saveConfig { it.reporting.reliableStationarySeconds = n } }
            },
            label = { Text("Reliable stationary (s)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = gate.enabled(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.reporting.includeDeviceNameInRemarks,
                enabled = gate.enabled(),
                onCheckedChange = { v -> host.saveConfig { it.reporting.includeDeviceNameInRemarks = v } },
            )
            SettingLabel("Include device name in CoT remarks", gate.enabled())
        }
        SetByMdm(gate.setByMdm("deferToAtak"))
        EnumDropdown(
            "Defer to ATAK",
            config.atak.deferToAtak,
            listOf("Off", "WhenRunning", "WhenHeardOnMesh"),
            gate.enabled("deferToAtak"),
        ) { v -> host.saveConfig { it.atak.deferToAtak = v } }
        Blurb("When ATAK is active, AndroidTAKTracker suppresses its own PLI to avoid duplicate markers.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val gate = rememberEditGate(host)
    var testMsg by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LockBanner(gate)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.meshSa.enabled,
                enabled = gate.enabled(),
                onCheckedChange = { v -> host.saveConfig { it.meshSa.enabled = v } },
            )
            SettingLabel("Broadcast Mesh SA", gate.enabled(), Modifier.padding(start = 8.dp))
        }
        EnumDropdown(
            "Mode",
            config.meshSa.mode,
            listOf("Always", "OnlyWhenDisconnected"),
            gate.enabled(),
        ) { v -> host.saveConfig { it.meshSa.mode = v } }
        Text("Multicast ${config.meshSa.multicastAddress}:${config.meshSa.multicastPort}")
        Blurb("Many Wi‑Fi APs block multicast — test with a map client on the same LAN.")
        OutlinedButton(enabled = gate.enabled(), onClick = {
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
    val gate = rememberEditGate(host)
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LockBanner(gate)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.startup.startOnBoot,
                enabled = gate.enabled(),
                onCheckedChange = { v -> host.saveConfig { it.startup.startOnBoot = v } },
            )
            SettingLabel("Start when phone boots", gate.enabled(), Modifier.padding(start = 8.dp))
        }
        SetByMdm(gate.setByMdm("preventSleepWhileTracking"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = config.startup.preventSleepWhileTracking,
                enabled = gate.enabled("preventSleepWhileTracking"),
                onCheckedChange = { v -> host.saveConfig { it.startup.preventSleepWhileTracking = v } },
            )
            SettingLabel(
                "Prevent sleep while tracking",
                gate.enabled("preventSleepWhileTracking"),
                Modifier.padding(start = 8.dp),
            )
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
        Blurb(
            "On Headwind MDM fleets, enable Autostart apps in foreground and keep-alive. " +
                "Headwind cannot set battery Unrestricted; the tracker asks once in the foreground and still reports without it.",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(host: TrackingHost) {
    val config by host.config.collectAsState()
    val unlocked by host.settingsUnlocked.collectAsState()
    val gate = rememberEditGate(host)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lockPassword by remember { mutableStateOf("") }
    var lockMsg by remember { mutableStateOf<String?>(null) }
    var logText by remember { mutableStateOf("Loading logs…") }
    val logScroll = rememberScrollState()
    val lockOwnedByMdm = gate.setByMdm("settingsLock")

    fun refreshLogs() {
        scope.launch {
            logText = withContext(Dispatchers.IO) { host.readRecentLogs() }
            logScroll.scrollTo(logScroll.maxValue)
        }
    }

    LaunchedEffect(Unit) { refreshLogs() }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LockBanner(gate)
        EnumDropdown(
            "Log level",
            config.diagnostics.logLevel,
            listOf("Debug", "Information", "Warning", "Error"),
            gate.enabled(),
        ) { v -> host.saveConfig { it.diagnostics.logLevel = v } }
        Blurb("Default is Error (quiet). Switch to Information or Debug before reproducing a connection problem.")
        SetByMdm(gate.setByMdm("allowInsecureTlsSoftAccept"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = config.diagnostics.allowInsecureTlsSoftAccept,
                enabled = gate.enabled("allowInsecureTlsSoftAccept"),
                onCheckedChange = { v -> host.saveConfig { it.diagnostics.allowInsecureTlsSoftAccept = v } },
            )
            SettingLabel("Allow insecure TLS soft-accept (lab only)", gate.enabled("allowInsecureTlsSoftAccept"))
        }
        Blurb("Device UID: ${config.deviceUid}")
        Blurb("ATAK installed: ${host.atak.installed.value} · running: ${host.atak.running.value}")
        val fix by host.gps.fix.collectAsState()
        val snapshot by host.reportingSnapshot.collectAsState()
        Blurb(
            fix?.let { f ->
                "Position: ${"%.5f".format(f.latitude)}, ${"%.5f".format(f.longitude)} · " +
                    "±${f.accuracyMeters?.let { "%.0f m".format(it) } ?: "—"} · " +
                    "${f.speedMetersPerSecond?.let { "%.1f m/s".format(it) } ?: "—"} · ${f.source}"
            } ?: "Position: none",
        )
        Blurb(
            "Reporting: ${snapshot.motion} · interval ${snapshot.intervalSeconds}s · stale ${snapshot.staleSeconds}s · " +
                "GPS duty ${snapshot.gpsDuty} · ${snapshot.suppressed}",
        )

        Text("Recent logs", fontWeight = FontWeight.SemiBold)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                logText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp)
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(logScroll)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                ),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refreshLogs() }) { Text("Refresh logs") }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "AndroidTAKTracker logs")
                    putExtra(Intent.EXTRA_TEXT, host.readRecentLogs())
                }
                ctx.startActivity(Intent.createChooser(send, "Share logs"))
            }) { Text("Share logs") }
        }

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
        SetByMdm(lockOwnedByMdm)
        OutlinedTextField(
            value = lockPassword,
            onValueChange = { lockPassword = it },
            label = { Text(if (host.isSettingsLocked && !unlocked) "Unlock password" else "New lock password") },
            modifier = Modifier.fillMaxWidth(),
            enabled = host.isSettingsLocked && !unlocked || !lockOwnedByMdm,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (host.isSettingsLocked && !unlocked) {
                Button(onClick = {
                    lockMsg = if (host.unlockSettings(lockPassword)) {
                        lockPassword = ""
                        "Unlocked."
                    } else "Incorrect password."
                }) { Text("Unlock") }
            } else if (!lockOwnedByMdm) {
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
    val gate = rememberEditGate(host)
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
                enabled = !busy && !mdm && !gate.locked && last?.updateAvailable == true,
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
        // No background auto-update worker exists yet — a toggle here would be a dead setting.
        if (mdm) ManagedByMdmPill()
        Blurb(
            if (mdm) "Install updates from the MDM."
            else "Updates are manual: check above, then Download & install.",
        )
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
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "AndroidTAKTracker",
            modifier = Modifier
                .padding(top = 8.dp)
                .size(168.dp),
            contentScale = ContentScale.Fit,
        )
        Text("AndroidTAKTracker", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text("Version ${BuildConfig.VERSION_NAME}", textAlign = TextAlign.Center)
        Text("CopIX LLC", textAlign = TextAlign.Center)
        Text("AndroidTAKTracker Free Application License 1.0", textAlign = TextAlign.Center)
        Text(
            "Sibling of WinTAKTracker. Tracking-only — no COP, no built-in video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LinkButton("GitHub", "https://github.com/CopIXus/AndroidTAKTracker")
    }
}

private fun gpsDutyLabel(duty: GpsDuty, adaptToMotion: Boolean): String {
    if (!adaptToMotion) return "high (locked)"
    return when (duty) {
        GpsDuty.HIGH -> "high"
        GpsDuty.BALANCED -> "balanced"
        GpsDuty.LOW -> "low (still)"
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

private class EditGate(val locked: Boolean, val managed: Set<String>) {
    fun enabled(vararg keys: String): Boolean = !locked && keys.none { it in managed }
    fun setByMdm(vararg keys: String): Boolean = keys.any { it in managed }
    val serversEnabled: Boolean get() = enabled(*MdmSettingsApply.SERVER_KEYS.toTypedArray())
    val serversSetByMdm: Boolean get() = setByMdm(*MdmSettingsApply.SERVER_KEYS.toTypedArray())
}

@Composable
private fun rememberEditGate(host: TrackingHost): EditGate {
    val unlocked by host.settingsUnlocked.collectAsState()
    val managed by host.mdm.managedKeys.collectAsState()
    return EditGate(locked = host.isSettingsLocked && !unlocked, managed = managed)
}

@Composable
private fun LockBanner(gate: EditGate) {
    if (gate.locked) {
        Blurb("Settings are locked. You can view them, but not change them. Unlock under Diagnostics.")
    }
}

@Composable
private fun SetByMdm(show: Boolean) {
    if (show) ManagedByMdmPill()
}

@Composable
private fun ConnectionPill(state: TakConnectionState?) {
    val label = when (state) {
        TakConnectionState.CONNECTED -> "Connected"
        TakConnectionState.CONNECTING -> "Connecting"
        TakConnectionState.RECONNECTING -> "Reconnecting"
        TakConnectionState.ERROR -> "Error"
        TakConnectionState.DISCONNECTED, null -> "Disconnected"
    }
    val (bg, fg) = when (state) {
        TakConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TakConnectionState.ERROR ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun SettingLabel(text: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
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

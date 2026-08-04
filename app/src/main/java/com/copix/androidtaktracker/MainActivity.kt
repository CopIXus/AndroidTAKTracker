package com.copix.androidtaktracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import com.copix.androidtaktracker.core.identity.IdentityResolver
import com.copix.androidtaktracker.host.TrackingHost
import com.copix.androidtaktracker.onboarding.OnboardingScreen
import com.copix.androidtaktracker.service.TrackingForegroundService
import com.copix.androidtaktracker.ui.QrScanScreen
import com.copix.androidtaktracker.ui.SettingsSection
import com.copix.androidtaktracker.ui.screens.SectionContent
import com.copix.androidtaktracker.ui.theme.AndroidTakTrackerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingDeepLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TrackingForegroundService.start(this)
        pendingDeepLink = intent?.dataString

        val prefs = getSharedPreferences("att_ui", MODE_PRIVATE)
        setContent {
            AndroidTakTrackerTheme {
                val host = remember { TrackingHost.get(this) }
                var showOnboarding by remember {
                    mutableStateOf(!prefs.getBoolean("onboarding_done", false))
                }
                var showQr by remember { mutableStateOf(false) }
                var callsignDone by remember { mutableStateOf(false) }
                var statusMessage by remember { mutableStateOf<String?>(null) }
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                androidx.compose.runtime.DisposableEffect(Unit) {
                    val listener = Consumer<Intent> { intent ->
                        intent.dataString?.let { uri ->
                            scope.launch {
                                val r = host.enroll(uri)
                                snackbar.showSnackbar(r.message)
                            }
                        }
                    }
                    addOnNewIntentListener(listener)
                    pendingDeepLink?.let { uri ->
                        scope.launch {
                            val r = host.enroll(uri)
                            snackbar.showSnackbar(r.message)
                            pendingDeepLink = null
                        }
                    }
                    onDispose { removeOnNewIntentListener(listener) }
                }

                val config by host.config.collectAsState()
                val needsCallsign = !callsignDone && IdentityResolver.userNeedsSetup(config)

                when {
                    showOnboarding -> OnboardingScreen {
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                        showOnboarding = false
                    }
                    showQr -> QrScanScreen(
                        onResult = { raw ->
                            showQr = false
                            scope.launch {
                                val r = host.enroll(raw)
                                snackbar.showSnackbar(r.message)
                            }
                        },
                        onCancel = { showQr = false },
                    )
                    needsCallsign -> CallsignSetupScreen(
                        host = host,
                        onDone = { saved ->
                            callsignDone = true
                            if (saved) statusMessage = "Callsign saved"
                        },
                    )
                    else -> AppShell(
                        host = host,
                        snackbar = snackbar,
                        onOpenQr = { showQr = true },
                        pendingMessage = statusMessage,
                        onPendingMessageShown = { statusMessage = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun CallsignSetupScreen(host: TrackingHost, onDone: (saved: Boolean) -> Unit) {
    val config by host.config.collectAsState()
    var callsign by remember { mutableStateOf(config.userIdentity.callsign) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set your callsign", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This appears on TAK maps as your PLI label. Portal pushes append .att automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it },
            label = { Text("My callsign") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            enabled = callsign.isNotBlank(),
            onClick = {
                val ok = host.saveConfig {
                    it.userIdentity.callsign = callsign.trim()
                    it.userIdentity.setupPromptDismissed = true
                }
                if (ok) onDone(true)
                else error = "Could not save (settings locked?)."
            },
        ) { Text("Save") }
        TextButton(onClick = {
            host.saveConfig { it.userIdentity.setupPromptDismissed = true }
            onDone(false)
        }) { Text("Skip for now") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(
    host: TrackingHost,
    snackbar: SnackbarHostState,
    onOpenQr: () -> Unit,
    pendingMessage: String? = null,
    onPendingMessageShown: () -> Unit = {},
) {
    var section by remember { mutableStateOf(SettingsSection.Status) }
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(pendingMessage) {
        if (!pendingMessage.isNullOrBlank()) {
            snackbar.showSnackbar(pendingMessage)
            onPendingMessageShown()
        }
    }

    val navList: @Composable () -> Unit = {
        LazyColumn(Modifier.padding(8.dp)) {
            items(SettingsSection.entries) { item ->
                NavigationDrawerItem(
                    label = { Text(item.title) },
                    selected = section == item,
                    onClick = {
                        section = item
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(item.icon, contentDescription = null) },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }

    if (wide) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(section.title) }) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Row(Modifier.padding(pad).fillMaxSize()) {
                androidx.compose.foundation.layout.Box(Modifier.width(260.dp)) { navList() }
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .weight(1f)
                        .padding(16.dp),
                ) {
                    SectionContent(section, host, onOpenQr)
                }
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                androidx.compose.material3.ModalDrawerSheet { navList() }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(section.title) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = "Menu")
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { pad ->
                androidx.compose.foundation.layout.Box(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
                    SectionContent(section, host, onOpenQr)
                }
            }
        }
    }
}

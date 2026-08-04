package com.copix.androidtaktracker.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * First-run permission wizard. Android requires fine location before background location, so
 * those are requested in separate steps (bundling them often fails silently).
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    fun refresh() { tick++ }

    val fineGranted = remember(tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val bgGranted = remember(tick) {
        if (Build.VERSION.SDK_INT < 29) true
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(tick) {
        if (Build.VERSION.SDK_INT < 33) true
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val cameraGranted = remember(tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    val batteryExempt = remember(tick) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }
    val canInstall = remember(tick) {
        if (Build.VERSION.SDK_INT < 26) true
        else ctx.packageManager.canRequestPackageInstalls()
    }

    val basicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh() }

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    // Ask for the essential runtime permissions as soon as the wizard appears.
    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (!fineGranted) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (!cameraGranted) needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) basicLauncher.launch(needed.toTypedArray())
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to AndroidTAKTracker", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Grant these permissions so tracking works after reboot and in the background. " +
                "Android will show a system dialog for each step.",
            style = MaterialTheme.typography.bodyMedium,
        )

        PermissionRow("Notifications", notifGranted, "Status bar tracking notice")
        PermissionRow("Location (precise)", fineGranted, "Required for PLI / self-SA")
        PermissionRow("Location (background)", bgGranted, "Keep tracking with screen off")
        PermissionRow("Camera", cameraGranted, "Scan enrollment QR codes")
        PermissionRow("Battery unrestricted", batteryExempt, "Avoid OEM killing the service")
        PermissionRow("Install updates", canInstall, "Allow in-app APK updates from GitHub")

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val needed = mutableListOf<String>()
                if (!fineGranted) {
                    needed += Manifest.permission.ACCESS_FINE_LOCATION
                    needed += Manifest.permission.ACCESS_COARSE_LOCATION
                }
                if (!cameraGranted) needed += Manifest.permission.CAMERA
                if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
                    needed += Manifest.permission.POST_NOTIFICATIONS
                }
                if (needed.isNotEmpty()) basicLauncher.launch(needed.toTypedArray())
                else refresh()
            },
        ) { Text(if (fineGranted && cameraGranted && notifGranted) "Permissions granted" else "Ask for permissions") }

        if (fineGranted && !bgGranted && Build.VERSION.SDK_INT >= 29) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            ) { Text("Allow background location") }
            Text(
                "Choose “Allow all the time” on the next screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        },
                    )
                }
                refresh()
            },
        ) { Text(if (batteryExempt) "Battery already unrestricted" else "Disable battery optimization") }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                refresh()
            },
        ) { Text("Usage access (ATAK detection)") }

        if (Build.VERSION.SDK_INT >= 26) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${ctx.packageName}")
                        },
                    )
                    refresh()
                },
            ) { Text(if (canInstall) "Install updates allowed" else "Allow install unknown apps") }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onDone,
        ) {
            Text(if (fineGranted) "Continue" else "Continue without all permissions")
        }
        if (!fineGranted) {
            Text(
                "Without location permission the tracker cannot send PLI.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, why: String) {
    Text(
        "${if (granted) "✓" else "○"} $title — $why",
        style = MaterialTheme.typography.bodyMedium,
        color = if (granted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
    )
}

package com.copix.androidtaktracker.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* continue */ }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Welcome to AndroidTAKTracker", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This app sends your position to TAK servers. Grant the permissions below so tracking " +
                "works after reboot and in the background.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("• Notifications — persistent tracking status")
        Text("• Location (precise + background) — PLI / self-SA")
        Text("• Camera — scan TAK Portal / ATAK enrollment QR codes")
        Text("• Battery exemption — keep the tracker alive")
        Text("• Usage access — detect ATAK and avoid duplicate CoT")

        Button(onClick = {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA,
            )
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            if (Build.VERSION.SDK_INT >= 29) perms += Manifest.permission.ACCESS_BACKGROUND_LOCATION
            permissionLauncher.launch(perms.toTypedArray())
        }) { Text("Request permissions") }

        TextButton(onClick = {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${ctx.packageName}")
                    },
                )
            }
        }) { Text("Battery optimization exemption") }

        TextButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }) { Text("Open usage access settings") }

        Button(onClick = onDone) { Text("Continue") }
    }
}

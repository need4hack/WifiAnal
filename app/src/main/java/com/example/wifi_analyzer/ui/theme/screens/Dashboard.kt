package com.example.wifi_analyzer.ui.theme.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifi_analyzer.data.FoundDevice
import com.example.wifi_analyzer.data.CurrentNetworkInfo
import com.example.wifi_analyzer.ui.theme.Wifi_analyzerTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import android.Manifest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    onHostClick: (ip: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Сканер Моей Сети 📡") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            MyConnectionCard(
                info = uiState.networkInfo
            )

            Button(
                onClick = { viewModel.startScan() },
                enabled = !uiState.isScanning,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (uiState.isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Идет сканирование...")
                } else {
                    Text("Сканировать Локальную Сеть", style = MaterialTheme.typography.titleMedium)
                }
            }

            Text(
                "Найденные устройства (${uiState.foundDevices.size}):",
                style = MaterialTheme.typography.titleMedium
            )

            AnimatedVisibility(visible = uiState.isScanning && uiState.foundDevices.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.foundDevices, key = { it.ip }) { device ->
                    FoundDeviceItem(device = device, onClick = { onHostClick(device.ip) })
                }
            }
        }
    }
}

@Composable
fun MyConnectionCard(info: CurrentNetworkInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Текущее подключение", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            InfoRow(icon = Icons.Default.SignalWifi4Bar, text = "SSID: ${info.ssid}")
            InfoRow(icon = Icons.Default.Computer, text = "Мой IP: ${info.myIp}")
            InfoRow(icon = Icons.Default.Router, text = "Роутер: ${info.routerIp}")
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FoundDeviceItem(device: FoundDevice, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(device.hostname) },
        supportingContent = { Text(device.ip) },
        leadingContent = {
            Icon(Icons.Default.Computer, contentDescription = "Device")
        }
    )
}

// --- PREVIEW ---
@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview() {
    Wifi_analyzerTheme{
        DashboardScreen(onHostClick = {})
    }
}
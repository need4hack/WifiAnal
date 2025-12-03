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
import androidx.compose.material.icons.filled.Smartphone // Добавил иконку смартфона
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifi_analyzer.data.FoundDevice
import com.example.wifi_analyzer.data.CurrentNetworkInfo
import com.example.wifi_analyzer.ui.theme.Wifi_analyzerTheme

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

            MyConnectionCard(info = uiState.networkInfo)

            Button(
                onClick = { viewModel.startScan() },
                enabled = !uiState.isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
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
                    Text("Сканировать (LAN + P2P)", style = MaterialTheme.typography.titleMedium)
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
                // Используем уникальный ключ (MAC или IP), чтобы список не дёргался
                items(
                    items = uiState.foundDevices,
                    key = { device -> device.mac + device.ip }
                ) { device ->
                    FoundDeviceItem(
                        device = device,
                        onClick = {
                            // Если это P2P устройство без IP, клик не должен вести на сканер портов
                            if (device.ip != "P2P Discovery" && device.ip != "?.?.?.?") {
                                onHostClick(device.ip)
                            }
                        }
                    )
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
            HorizontalDivider()
            InfoRow(icon = Icons.Default.SignalWifi4Bar, text = "SSID: ${info.ssid}")
            InfoRow(icon = Icons.Default.Computer, text = "Мой IP: ${info.myIp}")
            InfoRow(icon = Icons.Default.Router, text = "Роутер: ${info.routerIp}")
        }
    }
}

@Composable
fun InfoRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FoundDeviceItem(device: FoundDevice, onClick: () -> Unit) {
    // Выбираем иконку: Если вендор похож на телефон - рисуем телефон
    val isPhone = device.vendor.contains("Samsung", ignoreCase = true) ||
            device.vendor.contains("Apple", ignoreCase = true) ||
            device.vendor.contains("Xiaomi", ignoreCase = true) ||
            device.vendor.contains("Phone", ignoreCase = true)

    val icon = if (isPhone) Icons.Default.Smartphone else Icons.Default.Computer

    // Красивое имя: Если hostname совпадает с IP, лучше показать Вендора в заголовке
    val headline = if (device.hostname != device.ip && device.hostname != "Unknown P2P Device") {
        device.hostname
    } else {
        "Устройство (${device.vendor})"
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(icon, contentDescription = "Device Type")
        },
        headlineContent = {
            Text(headline, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // IP адрес
                Text("IP: ${device.ip}", style = MaterialTheme.typography.bodyMedium)

                // MAC адрес (самое важное!)
                if (device.mac != "??:??:??:??:??:??") {
                    Text(
                        text = "MAC: ${device.mac}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                // Вендор (если известен)
                if (device.vendor != "Unknown") {
                    Text(
                        text = "Vendor: ${device.vendor}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
    HorizontalDivider()
}
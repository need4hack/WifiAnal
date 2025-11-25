package com.example.wifiinspector.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifi_analyzer.data.PortResult
import com.example.wifi_analyzer.data.PortStatus
import com.example.wifi_analyzer.ui.theme.Wifi_analyzerTheme
import com.example.wifi_analyzer.ui.theme.screens.HostDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailsScreen(
    viewModel: HostDetailsViewModel = viewModel(),
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сканер Портов") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            Text(
                "Цель: ${uiState.targetIp}", // Берем IP из ViewModel
                style = MaterialTheme.typography.headlineSmall
            )

            Button(
                onClick = { viewModel.startPortScan() },
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
                    Text("Сканирую порты...")
                } else {
                    Text("Сканировать популярные порты", style = MaterialTheme.typography.titleMedium)
                }
            }

            //
            Text(
                "Результаты:",
                style = MaterialTheme.typography.titleMedium
            )


            AnimatedVisibility(visible = uiState.isScanning && uiState.portResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.portResults) { port ->
                    PortResultItem(result = port)
                }
            }
        }
    }
}

@Composable
fun PortResultItem(result: PortResult) {
    val statusColor = when (result.status) {
        PortStatus.OPEN -> Color(0xFF4CAF50) // Green
        PortStatus.CLOSED -> Color(0xFFF44336) // Red
    }
    val statusWeight = if (result.status == PortStatus.OPEN) FontWeight.Bold else FontWeight.Normal

    ListItem(
        headlineContent = {
            Text(
                "Порт ${result.port} (${result.serviceName})",
                fontWeight = statusWeight
            )
        },
        trailingContent = {
            Text(
                text = result.status.name,
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = statusWeight
            )
        }
    )
}

// --- PREVIEW ---
@Preview(showBackground = true)
@Composable
fun HostDetailsScreenPreview() {
    Wifi_analyzerTheme {
        HostDetailsScreen(onBackClick = {})
    }
}

annotation class HostDetailsScreen

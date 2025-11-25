package com.example.wifi_analyzer.ui.theme.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.wifi_analyzer.data.NetworkRepository
import com.example.wifi_analyzer.data.PortResult
import com.example.wifi_analyzer.data.PortStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// состояние для экрана деталей хоста
data class HostDetailsUiState(
    val targetIp: String = "",
    val portResults: List<PortResult> = emptyList(),
    val isScanning: Boolean = false,
    val error: String? = null
)

class HostDetailsViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle // получаем IP из навигации
) : AndroidViewModel(application) {

    private val repository = NetworkRepository(application)

    private val _uiState = MutableStateFlow(HostDetailsUiState())
    val uiState: StateFlow<HostDetailsUiState> = _uiState.asStateFlow()

    private val targetIp: String = savedStateHandle.get<String>("ip") ?: "0.0.0.0"

    init {
        // устанавливаем IP при открытии экрана
        _uiState.update { it.copy(targetIp = targetIp) }
    }

    //Запускает сканирование портов для целевого IP
    fun startPortScan() {
        viewModelScope.launch {
            repository.scanPorts(targetIp)
                .onStart {
                    _uiState.update {
                        it.copy(
                            isScanning = true,
                            portResults = emptyList(), // чистим старые результаты
                            error = null
                        )
                    }
                }
                .onCompletion {
                    _uiState.update { it.copy(isScanning = false) }
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(isScanning = false, error = e.message)
                    }
                }
                .collect { result ->
                    // добавляем результаты в список
                    _uiState.update {
                        it.copy(portResults = it.portResults + result)
                    }
                }
        }
    }
}
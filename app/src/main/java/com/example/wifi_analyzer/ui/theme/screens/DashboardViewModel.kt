package com.example.wifi_analyzer.ui.theme.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wifi_analyzer.data.CurrentNetworkInfo
import com.example.wifi_analyzer.data.FoundDevice
import com.example.wifi_analyzer.data.NetworkRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// сохраняем состояния экрана
data class DashboardUiState(
    val networkInfo: CurrentNetworkInfo = CurrentNetworkInfo(),
    val foundDevices: List<FoundDevice> = emptyList(),
    val isScanning: Boolean = false,
    val error: String? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NetworkRepository(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        loadCurrentNetworkInfo()
    }

     //Загружает инфо о текущем Wi-Fi (SSID, IP, Роутер)
    fun loadCurrentNetworkInfo() {
        try {
            val info = repository.getCurrentNetworkInfo()
            _uiState.update { it.copy(networkInfo = info) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Не удалось получить инфо о сети. Проверьте разрешения.") }
        }
    }


     //Запускает сканирование локальной сети
     fun startScan() {
         if (scanJob?.isActive == true) return

         scanJob = viewModelScope.launch {
             val myIp = _uiState.value.networkInfo.myIp

             // Запускаем ДВА сканера параллельно и сливаем результаты (merge)
             // 1. Обычный пинг по IP
             val lanFlow = repository.scanLocalNetwork(myIp)
             // 2. Wi-Fi Direct для поиска MAC-адресов
             val p2pFlow = repository.scanWifiDirectDevices()

             merge(lanFlow, p2pFlow)
                 .onStart {
                     _uiState.update { it.copy(isScanning = true, foundDevices = emptyList(), error = null) }
                 }
                 .onCompletion {
                     _uiState.update { it.copy(isScanning = false) }
                 }
                 .catch { e ->
                     Log.e("Scan", "Error", e)
                     _uiState.update { it.copy(error = "Ошибка: ${e.message}", isScanning = false) }
                 }
                 .collect { device ->
                     _uiState.update { currentState ->
                         // Добавляем устройство в список
                         // Так как P2P устройства не имеют IP (пишем "P2P Discovery"), они не будут конфликтовать с LAN
                         val newList = currentState.foundDevices.toMutableList()

                         // Простая проверка на дубликаты по имени, если MAC уже есть
                         val exists = newList.any { it.mac == device.mac && device.mac != "??:??:??:??:??:??" }
                         if (!exists) {
                             newList.add(device)
                         }

                         currentState.copy(foundDevices = newList)
                     }
                 }
         }
     }
}
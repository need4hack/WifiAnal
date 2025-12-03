package com.example.wifi_analyzer.ui.theme.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wifi_analyzer.data.NetworkRepository
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NetworkRepository(application)

    // Прямой поток данных из БД
    val historyList = repository.getHistory()

    fun clearAll() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}
package com.example.wifi_analyzer.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ssid: String,
    val timestamp: Long = System.currentTimeMillis()
)
package com.example.wifi_analyzer.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanHistoryDao {
    // Получаем список, новые сверху
    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<ScanHistoryEntity>>

    // Вставляем запись (если такая же уже есть - игнорим или заменяем, тут просто вставка)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ScanHistoryEntity)

    // Удалить всё (опционально)
    @Query("DELETE FROM scan_history")
    suspend fun clearHistory()
}
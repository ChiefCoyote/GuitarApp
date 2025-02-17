package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTabString(string: TabString)

    @Query("SELECT * FROM overlay_table ORDER BY id ASC")
    fun getAllTabs(): Flow<List<TabString>>
}
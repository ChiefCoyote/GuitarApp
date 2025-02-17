package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTabString(string: TabString)

    @Delete
    suspend fun deleteTab(tab: TabString)

    @Query("SELECT * FROM overlay_table ORDER BY id ASC")
    fun getAllTabs(): Flow<List<TabString>>
}
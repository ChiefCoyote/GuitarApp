package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "overlay_table")
data class TabString(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String?,
    val content: String?
)
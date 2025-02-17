package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "overlay_table",
    indices = [Index(value = ["name", "content"], unique = true)]
    )
data class TabString(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String?,
    val content: String?
)
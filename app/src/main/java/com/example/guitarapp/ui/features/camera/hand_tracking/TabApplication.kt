package com.example.guitarapp.ui.features.camera.hand_tracking

import android.app.Application

class TabApplication: Application() {
    private val database by lazy { TabDatabase.getDatabase(this) }
    val repository by lazy { TabRepository(database.tabDao()) }
}
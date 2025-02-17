package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.Flow

class TabRepository(private val tabDao: TabDao) {
    val allTabs: Flow<List<TabString>> = tabDao.getAllTabs()

    @WorkerThread
    suspend fun insertTabString(tabString: TabString){
        tabDao.insertTabString(tabString)
    }

    @WorkerThread
    suspend fun deleteTab(tabString: TabString) {
        tabDao.deleteTab(tabString)
    }
}
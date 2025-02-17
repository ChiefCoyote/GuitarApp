package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OverlayViewModel(private val repository: TabRepository) : ViewModel() {
    private var tabStrings: LiveData<List<TabString>> = repository.allTabs.asLiveData()
    private var currentIndex: Int = 0

    private val _overlayTab = MutableStateFlow<TabString>(TabString(content = null, name=null))
    val overlayTab : StateFlow<TabString> = _overlayTab

    init {
        tabStrings.observeForever { list ->
            if (list.isNotEmpty()) {
                currentIndex = 0
                _overlayTab.value = list[currentIndex]
            }
        }
    }

    fun addtabString(newTabString: TabString) = viewModelScope.launch {
        repository.insertTabString(newTabString)
    }

    fun nextTab() {
        val list = tabStrings.value.orEmpty()
        if (list.isEmpty()) return

        currentIndex = (currentIndex + 1) % list.size
        _overlayTab.value = list[currentIndex]
    }


}

class OverlayModelFactory(private val repository: TabRepository): ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OverlayViewModel::class.java))
            return OverlayViewModel(repository) as T

        throw IllegalArgumentException("Unknown Class for ViewModel")
    }
}
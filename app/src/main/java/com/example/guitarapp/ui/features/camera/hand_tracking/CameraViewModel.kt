package com.example.guitarapp.ui.features.camera.hand_tracking

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraViewModel : ViewModel() {
    private val _state = MutableStateFlow(com.example.guitarapp.ui.features.camera.hand_tracking.CameraState())
    val state = _state.asStateFlow()

    override fun onCleared() {
        _state.value.capturedImage?.recycle()
        super.onCleared()
    }
}
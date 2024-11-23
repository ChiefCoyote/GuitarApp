package com.example.guitarapp.ui.features.camera.hand_tracking

import android.content.Context
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

class CameraViewModel : ViewModel() {
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val _state = MutableStateFlow(CameraState())
    val state = _state.asStateFlow()

    private val _handTrackingResult = MutableStateFlow<Result>(Result.Loading)
    //val handTrackingResult = _handTrackingResult.asStateFlow()
    val handTrackingResult : StateFlow<Result> = _handTrackingResult

    override fun onCleared() {
        _state.value.capturedImage?.recycle()
        super.onCleared()
    }

    suspend fun onResultsReceived(resultBundle: HandLandmarkerHelper.ResultBundle) {
        withContext(Dispatchers.Main) {
            _handTrackingResult.value = Result.Success(resultBundle)
        }

    }

    suspend fun onErrorReceived(error: String, errorCode: Int) {
        withContext(Dispatchers.Main) {
            _handTrackingResult.value = Result.Error(error, errorCode)
        }
    }

    fun startHandTracking(context: Context, backgroundExecutor: Executor) {
            backgroundExecutor.execute {
                handLandmarkerHelper = HandLandmarkerHelper(
                    runningMode = RunningMode.LIVE_STREAM,
                    context = context,
                    handLandmarkerHelperListener = object :
                        HandLandmarkerHelper.LandmarkerListener {
                        override fun onError(error: String, errorCode: Int) {
                            viewModelScope.launch {
                                onErrorReceived(error, errorCode)
                            }
                        }

                        override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
                            viewModelScope.launch {
                                println(resultBundle.results)
                                onResultsReceived(resultBundle)
                            }
                        }
                    }
                )
            }
    }

    fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = true
        )
    }
}

sealed class Result {
    object Loading : Result()
    data class Success(val resultBundle: HandLandmarkerHelper.ResultBundle) : Result()
    data class Error(val error: String, val errorCode: Int) : Result()
}
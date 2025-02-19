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
import org.opencv.core.Point
import java.util.concurrent.Executor

class CameraViewModel : ViewModel() {
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private val _state = MutableStateFlow(CameraState())
    val state = _state.asStateFlow()

    private val _handTrackingResult = MutableStateFlow<Result>(Result.Loading)
    //val handTrackingResult = _handTrackingResult.asStateFlow()
    val handTrackingResult : StateFlow<Result> = _handTrackingResult

    private val _guitarTrackingResult = MutableStateFlow<List<List<Point?>>>(emptyList())
    //val handTrackingResult = _handTrackingResult.asStateFlow()
    val guitarTrackingResult : StateFlow<List<List<Point?>>> = _guitarTrackingResult

    private val coordBuffer = ArrayDeque<List<List<Point?>>>(1)

    override fun onCleared() {
        _state.value.capturedImage?.recycle()
        super.onCleared()
    }

    suspend fun onResultsReceived(resultBundle: HandLandmarkerHelper.ResultBundle) {
        withContext(Dispatchers.Main) {
            _handTrackingResult.value = Result.Success(resultBundle)

            if (coordBuffer.size >= 10){
                coordBuffer.removeFirst()
            }
            coordBuffer.addLast(resultBundle.guitar)

            val rows = 6
            val cols = 5

            // Create the result matrix, initializing each cell with null.
            val result = MutableList(rows) { MutableList<Point?>(cols) { null } }

            if(coordBuffer.isNotEmpty()){
                for (i in 0 until rows) {
                    for (j in 0 until cols) {
                        // Get all non-null points at this position from all matrices.
                        val validPoints = coordBuffer.mapNotNull { matrix -> matrix[i][j] }
                        if (validPoints.isNotEmpty()) {
                            // Average the x and y coordinates separately.
                            val avgX = validPoints.map { it.x }.average()
                            val avgY = validPoints.map { it.y }.average()
                            result[i][j] = Point(avgX, avgY)
                        } else {
                            // If no non-null points, the result stays null.
                            result[i][j] = null
                        }
                    }
                }
            }
            // For each cell in the 6x6 grid...


            _guitarTrackingResult.value = result

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
                            //println(resultBundle.results.first())
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
    data object Loading : Result()
    data class Success(val resultBundle: HandLandmarkerHelper.ResultBundle) : Result()
    data class Error(val error: String, val errorCode: Int) : Result()
}
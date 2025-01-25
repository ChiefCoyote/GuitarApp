/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.guitarapp.ui.features.camera.hand_tracking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.round


class HandLandmarkerHelper (
    var runningMode: RunningMode = RunningMode.LIVE_STREAM,
    val context: Context,
    // this listener is only used when running in RunningMode.LIVE_STREAM
    val handLandmarkerHelperListener: LandmarkerListener? = null
) : DefaultLifecycleObserver {

    // For this example this needs to be a var so it can be reset on changes.
    // If the Hand Landmarker will not change, a lazy val would be preferable.
    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    private fun clearHandLandmarker() {
        handLandmarker?.close()
        handLandmarker = null
    }


    // Initialize the Hand landmarker using current settings on the
    // thread that is using it. CPU can be used with Landmarker
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the
    // Landmarker
    private fun setupHandLandmarker() {
        // Set general hand landmarker options
        val baseOptionBuilder = BaseOptions.builder()

        baseOptionBuilder.setDelegate(Delegate.GPU)

        baseOptionBuilder.setModelAssetPath("hand_landmarker.task")

        // Check if runningMode is consistent with handLandmarkerHelperListener
        when (runningMode) {
            RunningMode.LIVE_STREAM -> {
                if (handLandmarkerHelperListener == null) {
                    throw IllegalStateException(
                        "handLandmarkerHelperListener must be set when runningMode is LIVE_STREAM."
                    )
                }
            }
            else -> {
                // no-op
            }
        }

        try {
            val baseOptions = baseOptionBuilder.build()
            // Create an option builder with base options and specific
            // options only use for Hand Landmarker.
            val optionsBuilder =
                HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.5F)
                    .setMinTrackingConfidence(0.5F)
                    .setMinHandPresenceConfidence(0.5F)
                    .setNumHands(1)
                    .setRunningMode(runningMode)

            // The ResultListener and ErrorListener only use for LIVE_STREAM mode.
            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }

            val options = optionsBuilder.build()
            handLandmarker =
                HandLandmarker.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            handLandmarkerHelperListener?.onError(
                "Hand Landmarker failed to initialize. See error logs for " +
                        "details"
            )
            Log.e(
                "HandLandmarkerHelper", "MediaPipe failed to load the task with error: " + e
                    .message
            )
        } catch (e: RuntimeException) {
            // This occurs if the model being used does not support GPU
            handLandmarkerHelperListener?.onError(
                "Hand Landmarker failed to initialize. See error logs for " +
                        "details", 1
            )
            Log.e(
                "HandLandmarkerHelper",
                "Image classifier failed to load model with error: " + e.message
            )
        }
    }

    // Convert the ImageProxy to MP Image and feed it to HandlandmarkerHelper.
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {
        if (runningMode != RunningMode.LIVE_STREAM) {
            throw IllegalArgumentException(
                "Attempting to call detectLiveStream" +
                        " while not using RunningMode.LIVE_STREAM"
            )
        }
        val frameTime = SystemClock.uptimeMillis()

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer =
            Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            // Rotate the frame received from the camera to be in the same direction as it'll be shown
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            // flip image if user use front camera
            if (isFrontCamera) {
                postScale(
                    -1f,
                    1f,
                    imageProxy.width.toFloat(),
                    imageProxy.height.toFloat()
                )
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
            matrix, true
        )

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        detectAsync(mpImage, frameTime)
    }

    // Run hand hand landmark using MediaPipe Hand Landmarker API
    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        handLandmarker?.detectAsync(mpImage, frameTime)
        // As we're using running mode LIVE_STREAM, the landmark result will
        // be returned in returnLivestreamResult function
    }


    // Return the landmark result to this HandLandmarkerHelper's caller
    private fun returnLivestreamResult(
        result: HandLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        val cvImage = guitarLandmarks(input)

        handLandmarkerHelperListener?.onResults(
            ResultBundle(
                listOf(result),
                cvImage,
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    // Return errors thrown during detection to this HandLandmarkerHelper's
    // caller
    private fun returnLivestreamError(error: RuntimeException) {
        handLandmarkerHelperListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    private fun guitarLandmarks(input: MPImage): Bitmap {
        val image = BitmapExtractor.extract(input)
        //println(image)
        val mat = Mat(image.height, image.width, CvType.CV_8UC4)
        Utils.bitmapToMat(image, mat)
        //println(mat)

        val grayMat = Mat()
        val edgesMat = Mat()
        val lines = Mat()
        val colorMat = Mat()

        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)

        Imgproc.GaussianBlur(grayMat, grayMat, Size(5.0, 5.0), 1.6)

        val clahe: CLAHE = Imgproc.createCLAHE(0.75, Size(16.0, 16.0))
        clahe.apply(grayMat, grayMat)

        Imgproc.Canny(grayMat, edgesMat, 50.0, 100.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        Imgproc.dilate(edgesMat, edgesMat, kernel)

        Imgproc.HoughLinesP(edgesMat, lines, 1.0, Math.PI / 180, 100, 25.0, 10.0)

        //println(lines)

        /*for (i in (0 until lines.rows())){
            var line = lines.get(i, 0)
            println(line[0])
            println(line[1])
            println(line[2])
            println(line[3])
        }*/

        Imgproc.cvtColor(edgesMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        val verticalLines = mutableListOf<Pair<Point, Point>>()
        val horizontalLines = mutableListOf<Pair<Point, Point>>()

        val gradientGroups = mutableMapOf<Double, MutableList<Pair<Point, Point>>>()

        for(i in 0 until lines.rows()){
            val line = lines.get(i, 0)
            //println(line)
            val x1 = line[0].toDouble()
            //println(x1)
            val y1 = line[1].toDouble()
            //println(y1)
            val x2 = line[2].toDouble()
            //println(x2)
            val y2 = line[3].toDouble()

            val slope = if (x2 - x1 == 0.0) Double.POSITIVE_INFINITY else (y2 - y1) / (x2 - x1)

            if (abs(slope) > 0.75) { // High slope -> Vertical
                verticalLines.add(Point(x1, y1) to Point(x2, y2))
            } else { // Low slope -> Horizontal
                horizontalLines.add(Point(x1, y1) to Point(x2, y2))
                val roundedGradient = "%.1f".format(slope).toDouble()
                //val roundedGradient = round(slope)
                gradientGroups.computeIfAbsent(roundedGradient) { mutableListOf() }.add(Point(x1, y1) to Point(x2, y2))
            }



            //println("Line: ($x1, $y1) to ($x2, $y2)")
            //Imgproc.line(colorMat, Point(x1, y1), Point(x2, y2), Scalar(0.0, 255.0, 0.0), 2)
        }


        //println(gradientGroups.keys.size)


        val largestList = gradientGroups.maxByOrNull { it.value.size }?.key

        val stringList = gradientGroups[largestList]
        var gradient = 0.0

        val lineGroups = mutableMapOf<Double, MutableList<Pair<Point, Point>>>()
        val intercepts = mutableSetOf<Double>()
        if (stringList != null) {
            for(line in stringList){
                if(largestList != null){
                    gradient = if (line.second.x - line.first.x == 0.0) Double.POSITIVE_INFINITY else (line.second.y - line.first.y) / (line.second.x - line.first.x)
                    var intercept = 0.0
                    if(line.first.x == 0.0 || gradient == 0.0){
                        intercept = line.first.y
                    } else{
                        intercept = line.first.y / (gradient * line.first.x)
                    }
                    if(intercept > 10000.0){
                        println("wow")
                        println(gradient)
                        println(line.first.x)
                        println(line.first.y)
                    }
                    intercepts.add(round(intercept / 10) * 10)
                    lineGroups.computeIfAbsent(round(intercept / 10) * 10) { mutableListOf() }.add(line)
                    //Imgproc.line(colorMat, line.first, line.second, Scalar(255.0, 0.0, 0.0), 2)
                }

            }
        }
        // GROUP LINES TOGETHER BASED ON TRAJECTORY

        println(intercepts.size)
        println(lineGroups.keys.size)
        println(lineGroups.keys)

        for(intercept in intercepts) {
            val y1 = intercept
            val x1 = 0.0
            val x2 = colorMat.width()
            val y2 = x2 * gradient + intercept

            Imgproc.line(colorMat, Point(x1,y1), Point(x2.toDouble(),y2), Scalar(255.0, 0.0, 0.0), 2)
        }

        val finalBitmap = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(colorMat, finalBitmap)

        return finalBitmap
    }

    override fun onResume(owner: LifecycleOwner) {
        setupHandLandmarker()
        super.onResume(owner)
    }

    override fun onPause(owner: LifecycleOwner) {
        clearHandLandmarker()
        super.onPause(owner)
    }

    override fun onStop(owner: LifecycleOwner) {
        clearHandLandmarker()
        super.onStop(owner)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        clearHandLandmarker()
        super.onDestroy(owner)
    }

    data class ResultBundle(
        val results: List<HandLandmarkerResult>,
        val guitar: Bitmap,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String, errorCode: Int = 0)
        fun onResults(resultBundle: ResultBundle)
    }
}

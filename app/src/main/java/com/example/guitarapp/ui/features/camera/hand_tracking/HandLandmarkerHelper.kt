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
import smile.clustering.xmeans
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

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
            var x1 = line[0].toDouble()
            //println(x1)
            var y1 = line[1].toDouble()
            //println(y1)
            var x2 = line[2].toDouble()
            //println(x2)
            var y2 = line[3].toDouble()

            if (x1 > x2){
                x1 = x2.also { x2 = x1 }
                y1 = y2.also { y2 = y1 }
            }


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

        var stringList = gradientGroups[largestList]
        var gradient = 0.0

        val lineLengths = mutableMapOf<Double, MutableList<Pair<Point, Point>>>()

        val lineGroups = mutableMapOf<Double, MutableList<Pair<Point, Point>>>()
        val intercepts = mutableSetOf<Double>()

        //Determine the y intercept of each line
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
                    Imgproc.line(colorMat, line.first, line.second, Scalar(255.0, 0.0, 0.0), 2)
                }

                val length = sqrt((line.second.x - line.first.y).pow(2) + (line.second.y - line.first.y).pow(2))
                lineLengths.computeIfAbsent(length) { mutableListOf() }.add(line)
            }
        }
        // GROUP LINES TOGETHER BASED ON TRAJECTORY

        //println("lengthNum")
        //println(lineLengths.keys.size)
        //println(intercepts.size)
        //println(lineGroups.keys.size)
        //println(lineGroups.keys)

        //SHOW THE LINES IN THE 10 LONGEST GROUPS
        /*for (longLine in 0..9) {
            val sortedLengths = lineLengths.keys.sortedDescending()
            val lineData = lineLengths[sortedLengths[longLine]]
            if (lineData != null) {
                for(eachLine in lineData)
                    Imgproc.line(colorMat, eachLine.first, eachLine.second, Scalar(0.0, 0.0, 255.0), 2)
            }
        }*/

        //GROUP LINES TOGETHER BASED ON PROXIMITY


        //TAKE INTERCEPTS AND DRAW A LINE ACROSS THE WHOLE SCREEN
        //TOO MANY LINES AND A LOT ARE AT THE TOP
        /*for(intercept in intercepts) {
            val y1 = intercept
            val x1 = 0.0
            val x2 = colorMat.width()
            val y2 = x2 * gradient + intercept

            //Imgproc.line(colorMat, Point(x1,y1), Point(x2.toDouble(),y2), Scalar(255.0, 0.0, 0.0), 2)
        }*/


        //Sort by x1 coord
        //If there exists a point 2 that  is close enough to point 1, combine the lines together.

        stringList?.sortBy { it.first.x }

        println("stringlist")
        if (stringList != null) {
            println(stringList.size)
        }

        val combinedLines = mutableMapOf<Point, Point>()
        var successfullyCombine = true

        while (successfullyCombine) {
            combinedLines.clear()
            successfullyCombine = false
            if (stringList != null) {
                for(line in stringList) {
                    val closePair = combinedLines.entries.find { pointDistance(it.key, line.first) < 15}

                    if(closePair != null){
                        val startPoint = closePair.value
                        combinedLines.remove(closePair.key)

                        combinedLines[line.second] = startPoint

                        successfullyCombine = true
                    } else {
                        combinedLines[line.second] = line.first
                    }
                }

                stringList = combinedLines.map {(key, value) -> Pair(value, key)}.toMutableList()

            }

        }





        var painted = 255.0
        var counteed = 0

        val longLines = mutableListOf<Pair<Double, Pair<Point, Point>>>()
        if (stringList != null) {
            for(line in stringList) {
                val length = hypot(line.second.x - line.first.x, line.second.y - line.first.y)
                longLines.add(Pair(length, line))
                //Imgproc.line(colorMat, line.first, line.second, Scalar(0.0, 0.0, 255.0), 2)
            }
            //Imgproc.line(colorMat, stringList[0].first, stringList[0].second, Scalar(0.0, 0.0, 255.0), 2)
        }
        longLines.sortByDescending { it.first }


        val combinedIntercpet = mutableMapOf<Double, Pair<Point, Point>>()

        //Calculate y intercepts, those that are close in value should be combined with the smallest x1 and greatest x2


        // IMPORTANT
        // ONLY COMBINE LINES IF THEY HAVE OVERLAPPING X COORDINATES
        // Hopefull avoids cross screen diagonals randomly appearing and may help when extrapolating with hidden sections
        // MAYBE go through each line and calculate how many are in close proximity, the grouping with the most are the strings


        for(line in longLines) {
            val gradx = if (line.second.second.x - line.second.first.x == 0.0) Double.POSITIVE_INFINITY else (line.second.second.y - line.second.first.y) / (line.second.second.x - line.second.first.x)
            var intercept = 0.0
            if(line.second.first.x == 0.0 || gradx == 0.0){
                intercept = line.second.first.y
            } else{
                intercept = line.second.first.y / (gradx * line.second.first.x)
            }

            val closeIntercept = combinedIntercpet.entries.find { abs(it.key - intercept) < 15 && overlapX(it.value, line.second)}
            var point1 = Point(0.0, 0.0)
            var point2 = Point(0.0, 0.0)
            var key = 0.0
            if(closeIntercept != null) {
                if (closeIntercept.value.first.x < line.second.first.x){
                    point1 = closeIntercept.value.first
                } else {
                    point1 = line.second.first
                }
                val x = if (closeIntercept.value.second.x > line.second.second.x) {
                    closeIntercept.value.second.x
                } else {
                    line.second.second.x
                }
                val y = (x * gradx) + intercept

                point2 = Point(x, y)

                key = closeIntercept.key
            } else {
                key = intercept
                point1 = line.second.first
                point2 = line.second.second
            }
            combinedIntercpet[key] = Pair(point1, point2)
        }



        val strings = mutableListOf<Pair<Double, Pair<Point, Point>>>()
        val clusterIntercepts = mutableListOf<Double>()
        for(line in combinedIntercpet.values) {

            if ("%.1f".format(lineGrad(line.first, line.second)).toDouble() == largestList){
                counteed++
                strings.add(Pair(pointDistance(line.first, line.second), Pair(line.first, line.second)))
                clusterIntercepts.add(findIntercept(line.first, line.second))

            }


        }
        strings.sortByDescending { it.first }

        greatestCluster(clusterIntercepts)

        for (guitarString in strings.take(12)) {
            Imgproc.line(colorMat, guitarString.second.first, guitarString.second.second, Scalar(0.0, 0.0, 255.0), 2)

        }



        //Imgproc.line(colorMat, Point(0.0,0.0), Point(255.0, 400.0), Scalar(255.0, 0.0, 0.0), 2)

        println("CombinedLines")
        println(counteed)


        val finalBitmap = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(colorMat, finalBitmap)

        return finalBitmap
    }

    private fun overlapX(line1: Pair<Point, Point>, line2: Pair<Point, Point>): Boolean {
        return if (line2.first.x + 10 >= line1.first.x && line2.first.x - 10 <= line1.second.x){
            true
        } else if (line2.second.x + 10 >= line1.first.x && line2.second.x - 10 <= line1.second.x) {
            true
        } else if (line1.first.x + 10 >= line2.first.x && line1.first.x - 10 <= line2.second.x) {
            true
        } else if (line1.second.x + 10 >= line2.first.x && line1.second.x - 10 <= line2.second.x) {
            true
        } else{
            false
        }
    }

    private fun pointDistance(point1: Point, point2: Point): Double {
        return hypot(point2.x - point1.x, point2.y - point1.y)
    }

    private fun lineGrad(point1: Point, point2: Point): Double {
        return if (point2.x - point1.x == 0.0) Double.POSITIVE_INFINITY else (point2.y - point1.y) / (point2.x - point1.x)
    }

    private fun findIntercept(point1: Point, point2: Point): Double {
        val gradient = if (point2.x - point1.x == 0.0) Double.POSITIVE_INFINITY else (point2.y - point1.y) / (point2.x - point1.x)
        val intercept = if(point1.x == 0.0 || gradient == 0.0){
            point1.y
        } else{
            point1.y / (gradient * point1.x)
        }
        return intercept
    }

    private fun greatestCluster(yValues: List<Double>): Map<Int, List<Double>> {
        if (yValues.isEmpty()) return emptyMap()

        val yArray = yValues.map { doubleArrayOf(it) }.toTypedArray()

        val clusters = xmeans(yArray, 10)

        val sortClusters = yValues.indices.groupBy { clusters.y[it] }.mapValues { (_, indices) -> indices.map { yValues[it] } }

        println("clusters")
        for ((cluster, points) in sortClusters) {
            println(cluster)
            println(points)
        }
        return sortClusters
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

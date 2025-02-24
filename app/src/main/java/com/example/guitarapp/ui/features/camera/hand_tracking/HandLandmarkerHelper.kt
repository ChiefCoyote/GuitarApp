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

//import smile.clustering.kmeans
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
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
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

        val guitarLandmarkList = guitarLandmarks(input)

        handLandmarkerHelperListener?.onResults(
            ResultBundle(
                listOf(result),
                guitarLandmarkList,
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

    private fun guitarLandmarks(input: MPImage): List<List<Point?>> {
        val image = BitmapExtractor.extract(input)
        val mat = Mat(image.height, image.width, CvType.CV_8UC4)
        Utils.bitmapToMat(image, mat)

        val transformedMats = applyTransforms(mat)

        val horizMat = transformedMats.first
        val vertMat = transformedMats.second

        var colorMat = Mat()
        val horizontalLines = Mat()
        val verticalLines = Mat()


        val lsd = Imgproc.createLineSegmentDetector()
        lsd.detect(vertMat, verticalLines)

        Imgproc.HoughLinesP(horizMat, horizontalLines, 1.0, Math.PI / 180, 100, 75.0, 20.0)
        //Imgproc.HoughLinesP(vertMat, verticalLines, 1.0, Math.PI / 180, 100, 20.0, 20.0)
        Imgproc.cvtColor(horizMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        val strongHorizontal = strongLines(horizontalLines, 6,true)
        val strongVertical = strongLines(verticalLines, 50,false)

        val combinedHorizontal = combineLines(strongHorizontal, true)
        val combinedVertical = combineLines(strongVertical, false)

        val ridgedVertical = verticalRidge(combinedVertical)
        val frets = selectFret(ridgedVertical)

        val extrapolatedFrets = extrapolateFret(frets)

        val extendedLines = extendLines(combinedHorizontal)

        val singleLines = removeDuplicateLines(extendedLines)

        val extrapolatedStrings = extrapolateStrings3(singleLines)

        //colorMat = drawLines(extrapolatedFrets, colorMat)
        //colorMat = drawLines(extrapolatedFrets, colorMat)
        //colorMat = drawLines(combinedHorizontal, colorMat)
        //colorMat = drawLines(extrapolatedStrings, colorMat)

        //val finalBitmap = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        //Utils.matToBitmap(colorMat, finalBitmap)


        //return finalBitmap

        val combinedCoords = combineLocations(extrapolatedStrings, extrapolatedFrets)

        return normalizeCoords(combinedCoords, mat)
    }

    private fun combineLocations(strings: MutableList<Pair<Point,Point>>, frets: MutableList<Pair<Point,Point>>) :  MutableList<MutableList<Point?>>{
        val stringList = strings.take(6)
        val fretList = frets.take(6)
        val combinedCoords : MutableList<MutableList<Point?>> = MutableList(6) {MutableList(5) {null} }
        if (stringList.size != 6 || fretList.size != 6) return combinedCoords



        for(i in 0 until 5){
            val x = fretList[0].first.x
            combinedCoords[i][0] = Point(x, findYCoord(stringList[i], x))
        }


        for (i in 0 until 6){
            for (j in 1 until 5){
                val x = fretList[j].first.x + ((fretList[j + 1].first.x - fretList[j].first.x) / 2)
                combinedCoords[i][j] = Point(x, findYCoord(stringList[i], x))
            }
        }

        return combinedCoords
    }

    private fun findYCoord(line: Pair<Point,Point>, x: Double) : Double {
        val gradient = lineGrad(line.first,line.second)
        val intercept = findIntercept(line.first, line.second)

        return ((gradient * x) + intercept)
    }

    private fun normalizeCoords(coords: MutableList<MutableList<Point?>>, mat: Mat) : List<List<Point?>> {
        val width = mat.cols().toDouble()
        val height = mat.rows().toDouble()

        return coords.map { list ->
            list.map {point ->
                point?.let {Point(it.x / width, it.y/height)}
            }
        }
    }


    /*private fun oldGuitarLandmarks(input: MPImage): Bitmap {
        val image = BitmapExtractor.extract(input)

        val mat = Mat(image.height, image.width, CvType.CV_8UC4)
        Utils.bitmapToMat(image, mat)


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


        Imgproc.cvtColor(edgesMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        val verticalLines = mutableListOf<Pair<Point, Point>>()
        val horizontalLines = mutableListOf<Pair<Point, Point>>()

        val gradientGroups = mutableMapOf<Double, MutableList<Pair<Point, Point>>>()

        for(i in 0 until lines.rows()){
            val line = lines.get(i, 0)

            var x1 = line[0].toDouble()

            var y1 = line[1].toDouble()

            var x2 = line[2].toDouble()

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
        }




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


                    }
                    intercepts.add(round(intercept / 10) * 10)
                    lineGroups.computeIfAbsent(round(intercept / 10) * 10) { mutableListOf() }.add(line)
                    Imgproc.line(colorMat, line.first, line.second, Scalar(255.0, 0.0, 0.0), 2)
                }

                val length = sqrt((line.second.x - line.first.y).pow(2) + (line.second.y - line.first.y).pow(2))
                lineLengths.computeIfAbsent(length) { mutableListOf() }.add(line)
            }
        }

        stringList?.sortBy { it.first.x }




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





        var counteed = 0

        val longLines = mutableListOf<Pair<Double, Pair<Point, Point>>>()
        if (stringList != null) {
            for(line in stringList) {
                val length = hypot(line.second.x - line.first.x, line.second.y - line.first.y)
                longLines.add(Pair(length, line))
            }
        }
        longLines.sortByDescending { it.first }


        val combinedIntercpet = mutableMapOf<Double, Pair<Point, Point>>()




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

        for(line in combinedIntercpet.values) {

            if ("%.1f".format(lineGrad(line.first, line.second)).toDouble() == largestList){
                counteed++
                strings.add(Pair(pointDistance(line.first, line.second), Pair(line.first, line.second)))

            }


        }
        strings.sortByDescending { it.first }
        val clusterIntercepts = mutableListOf<Double>()

        val possibleStrings = strings.take(15)

        for (string in possibleStrings) {
            clusterIntercepts.add(findIntercept(string.second.first, string.second.second))
        }

        val cluster = greatestCluster(clusterIntercepts)

        val topString = topString(cluster)


        for (guitarString in possibleStrings) {
            if (topString.second == findIntercept(guitarString.second.first, guitarString.second.second)){
                val startPoint = guitarString.second.first
                val endPoint =guitarString.second.second
                for (i in 0 until 6) {
                    val stringStartPoint = Point(startPoint.x, startPoint.y + (i * topString.first))
                    val stringEndPoint = Point(endPoint.x, endPoint.y + (i * topString.first))
                    Imgproc.line(colorMat, stringStartPoint, stringEndPoint, Scalar(0.0, 0.0, 255.0), 2)
                }
            }

            break

        }




        val finalBitmap = Bitmap.createBitmap(colorMat.cols(), colorMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(colorMat, finalBitmap)

        return finalBitmap
    }*/

    private fun extrapolateStrings3(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point,Point>> {
        if(lines.size > 2) {
            val extrapolatedLines = mutableListOf<Pair<Point,Point>>()
            lines.sortBy { findIntercept(it.first, it.second) }


            var averageLeftDistance = 0.0
            var averageRightDistance = 0.0

            for (i in 0 until lines.size - 1) {
                averageLeftDistance += lines[i + 1].first.y - lines[i].first.y
                averageRightDistance += lines[i + 1].second.y - lines[i].second.y
            }

            averageLeftDistance /= (lines.size - 1)
            averageRightDistance /= (lines.size - 1)

            //averageLeftDistance *= 2
            //averageRightDistance *= 2
            println("average Distances")
            println(averageLeftDistance)
            println(averageRightDistance)

            extrapolatedLines.add(lines.first())

            for (i in lines.size until 6) {
                val lineAbove = lines[i - 1]
                lines.add(
                    Pair(
                        Point(lineAbove.first.x, lineAbove.first.y + averageLeftDistance),
                        Point(lineAbove.second.x, lineAbove.second.y + averageRightDistance)
                    )
                )
            }

            return lines
        } else{
            return mutableListOf()
        }
    }

    private fun extrapolateStrings2(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point,Point>> {
        if(lines.size > 2) {
            lines.sortBy { findIntercept(it.first, it.second) }


            var averageLeftDistance = 0.0
            var averageRightDistance = 0.0

            for (i in 0 until lines.size - 1) {
                averageLeftDistance += lines[i + 1].first.y - lines[i].first.y
                averageRightDistance += lines[i + 1].second.y - lines[i].second.y
            }

            averageLeftDistance /= lines.size
            averageRightDistance /= lines.size

            for (i in lines.size until 6) {
                val lineAbove = lines[i - 1]
                lines.add(
                    Pair(
                        Point(lineAbove.first.x, lineAbove.first.y + averageLeftDistance),
                        Point(lineAbove.second.x, lineAbove.second.y + averageRightDistance)
                    )
                )
            }

            return lines
        } else{
            return mutableListOf()
        }
    }

    private fun extrapolateStrings(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point,Point>> {
        if (lines.size > 2){
            val extrapolatedStrings = mutableListOf<Pair<Point,Point>>()
            val stringHeights = mutableListOf<Double>()
            for (line in lines){
                val yintercept = round(findIntercept(line.first,line.second)/10) * 10
                stringHeights.add(yintercept)
            }

            //println(stringHeights)

            stringHeights.sort()
            var minDistance = Double.POSITIVE_INFINITY

            for (i in 0 until stringHeights.size - 1){
                //println(stringHeights[i+1])
                //println(stringHeights[i])
                val difference = stringHeights[i+1] - stringHeights[i]
                if ((difference < minDistance) && (difference != 0.0)){
                    minDistance = difference
                }
            }

            //println(minDistance)
            lines.sortBy { it.first.y }

            val topString = lines.first()
            val gradient = lineGrad(topString.first, topString.second)
            val yIntercept = findIntercept(topString.first, topString.second)

            for (i in 0 until 6){
                val x1 = topString.first.x
                val x2 = topString.second.x
                val newIntercept = yIntercept + (minDistance * i)

                val y1 = (x1 * gradient) + newIntercept
                val y2 = (x2 * gradient) + newIntercept

                //println(y1)
                extrapolatedStrings.add(Pair(Point(x1,y1), Point(x2,y2)))
            }
            return extrapolatedStrings
        } else {
            return mutableListOf()
        }



    }

    private fun extrapolateLine(line : Pair<Point,Point>, startX : Double, endX : Double) : Pair<Point,Point>{
        val gradient = lineGrad(line.first, line.second)
        val yIntercept = findIntercept(line.first, line.second)

        val startPoint = Point(startX,(gradient * startX) + yIntercept)
        val endPoint = Point(endX, (gradient * endX) + yIntercept)

        return Pair(startPoint, endPoint)
    }

    private fun extendLines(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point,Point>> {
        var leftMost = Double.POSITIVE_INFINITY
        var rightMost = Double.NEGATIVE_INFINITY

        val extendedLines = mutableListOf<Pair<Point,Point>>()

        for (line in lines){
            if(line.first.x < leftMost) leftMost=line.first.x
            if(line.second.x > rightMost) rightMost=line.second.x
        }

        for (line in lines){
            extendedLines.add(extrapolateLine(line, leftMost, rightMost))
        }

        return extendedLines
    }

    private fun removeDuplicateLines(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point,Point>> {

        val yMap = mutableMapOf<Double, MutableList<Pair<Point,Point>>>()
        val singleLines = mutableListOf<Pair<Point,Point>>()
        for (line in lines){
            val yintercept = round(findIntercept(line.first,line.second)/10) * 10
            yMap.getOrPut(yintercept) { mutableListOf()}.add(line)
        }
        println(yMap.keys)
        for ((_, segments) in yMap){
            if(segments.size == 1){
                singleLines.add(segments.first())
            } else{
                var leftMost = Point(Double.POSITIVE_INFINITY, 0.0)
                var rightMost = Point(Double.NEGATIVE_INFINITY, 0.0)

                for (segment in segments){
                    if(segment.first.x < leftMost.x){
                        leftMost = segment.first
                    }
                    if (segment.first.x > rightMost.x){
                        rightMost = segment.first
                    }
                    if(segment.second.x < leftMost.x){
                        leftMost = segment.second
                    }
                    if (segment.second.x > rightMost.x){
                        rightMost = segment.second
                    }
                }


                singleLines.add(Pair(leftMost, rightMost))
            }
        }

        return singleLines
    }

    private fun combineLines(lines: MutableList<Pair<Point,Point>>, horizontal: Boolean) : MutableList<Pair<Point,Point>> {
        val combinedLines = mutableListOf<Pair<Point, Point>>()
        val threshold: Int
        if(horizontal){
            threshold = 10
        } else{
            threshold = 8
        }

        while (lines.isNotEmpty()){
            var line = lines.removeAt(0)
            val remove = mutableListOf<Pair<Point, Point>>()
            for (connection in lines){
                if (pointDistance(line.second, connection.first) < threshold){
                    line = Pair(line.first,connection.second)
                    remove.add(connection)
                }
            }
            for (connection in remove){
                lines.remove(connection)
            }

            combinedLines.add(line)
        }
        return combinedLines
    }

    private fun verticalRidge(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point,Point>>{
        lines.sortBy { it.first.x }

        val ridgeLines = mutableListOf<Pair<Point, Point>>()

        while (lines.isNotEmpty()){
            var line = lines.removeAt(0)
            val remove = mutableListOf<Pair<Point, Point>>()
            for (connection in lines){
                val distance = connection.first.x - line.first.x
                if (distance < 10){
                    val x1 = line.first.x + (distance/2)
                    val x2 = line.second.x + ((connection.second.x - line.second.x)/2)

                    val y1 = if (line.first.y < connection.first.y){
                        line.first.y
                    } else{
                        connection.first.y
                    }

                    val y2 = if (line.second.y > connection.second.y){
                        line.second.y
                    } else{
                        connection.second.y
                    }

                    line = Pair(Point(x1,y1),Point(x2,y2))
                    remove.add(connection)
                }
            }
            for (connection in remove){
                lines.remove(connection)
            }

            ridgeLines.add(line)
        }

        return ridgeLines
    }

    private fun selectFret(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        val n = lines.size
        val sumX = lines.sumOf { it.first.x }
        val sumY = lines.sumOf { it.first.y }
        val sumXY = lines.sumOf { it.first.x * it.first.y }
        val sumX2 = lines.sumOf { it.first.x * it.first.x }

        val gradient = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - gradient * sumX) / n

        fun distanceToLine(p: Point): Double {
            return abs(p.y - (gradient * p.x + intercept))
        }

        fun adjustStartPoint(p1 : Point, p2 : Point): Point {
            val yOnRegression = gradient * p1.x + intercept
            // Check if p1 and p2 are on opposite sides of the regression line.
            if ((p1.y - yOnRegression) * (p2.y - yOnRegression) <= 0) {
                // They cross: snap p1 to the regression line.
                return Point(p1.x, yOnRegression)
            }
            // Otherwise, leave p1 as is.
            return p1
        }

        val tolerance = 10.0
        val selectedLines = mutableListOf<Pair<Point, Point>>()

        for (line in lines) {
            val (p1, p2) = line
            // Adjust the start point if the line crosses the regression line.
            val newStart = adjustStartPoint(p1, p2)
            // Then keep the line if the (adjusted) start point is close to the regression line.
            if (distanceToLine(newStart) < tolerance) {
                selectedLines.add(Pair(newStart, p2))
            }
        }
        return selectBottomFret(selectedLines)
    }

    private fun selectBottomFret(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        val n = lines.size
        val sumX = lines.sumOf { it.second.x }
        val sumY = lines.sumOf { it.second.y }
        val sumXY = lines.sumOf { it.second.x * it.second.y }
        val sumX2 = lines.sumOf { it.second.x * it.second.x }

        val gradient = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - gradient * sumX) / n

        fun distanceToLine(p: Point): Double {
            return abs(p.y - (gradient * p.x + intercept))
        }

        fun adjustEndPoint(p1 : Point, p2 : Point): Point {
            val yOnRegression = gradient * p2.x + intercept
            // Check if p1 and p2 are on opposite sides of the regression line.
            if ((p1.y - yOnRegression) * (p2.y - yOnRegression) <= 0) {
                // They cross: snap p1 to the regression line.
                return Point(p2.x, yOnRegression)
            }
            // Otherwise, leave p1 as is.
            return p2
        }

        val tolerance = 10.0
        val selectedLines = mutableListOf<Pair<Point, Point>>()

        for (line in lines) {
            val (p1, p2) = line
            // Adjust the end point if the line crosses the regression line.
            val newEnd = adjustEndPoint(p1, p2)
            // Then keep the line if the (adjusted) start point is close to the regression line.
            if (distanceToLine(newEnd) < tolerance) {
                selectedLines.add(Pair(p1, newEnd))
            }
        }
        return selectedLines
    }

    private fun fretLength(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        val lengthMap = mutableMapOf<Double, MutableList<Pair<Point, Point>>>()

        for (line in lines){
            val length = round((pointDistance(line.first, line.second))/10) * 10
            lengthMap.computeIfAbsent(length) { mutableListOf()}.add(line)
        }

        val uniformFrets = lengthMap.maxByOrNull { it.value.size }?.value

        if (uniformFrets.isNullOrEmpty()){
            return emptyList<Pair<Point, Point>>().toMutableList()
        }

        return uniformFrets
    }

    private fun extrapolateFret4(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        if (lines.size < 2) return emptyList<Pair<Point,Point>>().toMutableList()

        val distance = lines[lines.size-1].first.x - lines[lines.size - 2].first.x

        if(lines[1].first.x - lines[0].first.x < distance){
            lines.removeAt(1)
        }


        return lines
    }

    private fun extrapolateFret3(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        val fretLines = mutableListOf<Pair<Point, Point>>()
        println("extrapolate")
        lines.sortBy { it.first.x }
        if (lines.size < 3) return fretLines

        val fret1 = lines[1]
        val lastFrets = lines.takeLast(4)
        val j = lastFrets.size

        val fretN = lastFrets[1]
        val fretj = lastFrets.last()

        val deltaN = fretN.first.x - fret1.first.x
        val deltaNplusj = fretj.first.x - fretN.first.x

        val R_measured = deltaNplusj / deltaN

        val a = (2.0).pow(1.0/12)

        val numerator = R_measured * a.pow(-1)
        val denominator = 1 - a.pow(-j) + R_measured
        val Y = numerator / denominator

        val n = -(ln(Y) / ln(a))

        val numFrets = n+j
        println("frets")
        println(numFrets)


        if (numFrets < 2) return fretLines

        val r = 1 / (2.0).pow(1.0 / 12.0)

        val totalDistance = fretj.first.x - fret1.first.x

        val seriesSum = (1 - r.pow(numFrets - 1)) / (1 - r)

        val d1 = totalDistance / seriesSum

        fretLines.add(fret1)

        for (i in 2..numFrets.toInt()){
            val gapSum = d1 * ((1 - r.pow(i - 1)) / 1 - r)
            fretLines.add(Pair(Point(fret1.first.x + gapSum, fret1.first.y), Point(fret1.second.x + gapSum, fret1.second.y)))
        }

        return fretLines
    }

    private fun extrapolateFret2(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        val fretLines = mutableListOf<Pair<Point, Point>>()
        lines.sortBy { it.first.x }
        if (lines.size < 3) return fretLines

        val fretDistances = lines.zipWithNext {p1, p2 -> p2.first.x - p1.first.x}

        val ratios = fretDistances.zipWithNext {p1, p2 -> p2 / p1}

        val ratio = ratios.average()

        return fretLines
    }

    private fun extrapolateFret(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        lines.sortBy { it.first.x }

        val rightmostFrets = lines.takeLast(6)

        if (rightmostFrets.size < 3) return emptyList<Pair<Point, Point>>().toMutableList()

        val fretDistances = mutableListOf<Double>()

        for (i in 0 until rightmostFrets.size - 1){
            fretDistances.add(rightmostFrets[i + 1].first.x - rightmostFrets[i].first.x)
        }

        val ratios = fretDistances.zipWithNext {d1, d2 -> d2 / d1}
        var ratio = ratios.average()

        val realRatio = 0.94387



        val extrapolatedFrets = mutableListOf<Pair<Point, Point>>()

        println("ratio")
        println(ratio)

        /*if ((ratio * 10).toInt() == 0 || ratio > 1){
            ratio = realRatio
        }*/

        ratio = realRatio

        println("success")

        /*extrapolatedFrets.add(rightmostFrets.last())
        extrapolatedFrets.add(rightmostFrets[rightmostFrets.size - 2])
        extrapolatedFrets.add(rightmostFrets[rightmostFrets.size - 3])*/

        for (i in 1 until rightmostFrets.size + 1){
            extrapolatedFrets.add(rightmostFrets[rightmostFrets.size - i])
        }

        var distance = fretDistances[0]

        var repeat = true
        while (repeat){
            distance /= ratio
            val currentFret = extrapolatedFrets.last()

            val x1 = currentFret.first.x - distance
            val x2 = currentFret.second.x - distance

            if(x1 < 0 && x2 < 0){
                repeat = false
                break
            }
            extrapolatedFrets.add(Pair(Point(x1,currentFret.first.y), Point(x2, currentFret.second.y)))
        }

        return extrapolatedFrets

    }

    private fun strongLines(lines: Mat, cutoff: Int, horizontal: Boolean) : MutableList<Pair<Point,Point>> {
        var lineCounter = cutoff
        val strongLines = mutableListOf<Pair<Point,Point>>()

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

            if(horizontal){
                if (x1 > x2){
                    x1 = x2.also { x2 = x1 }
                    y1 = y2.also { y2 = y1 }
                }
            } else{
                if (y1 > y2){
                    x1 = x2.also { x2 = x1 }
                    y1 = y2.also { y2 = y1 }
                }
            }

            val gradient = abs(lineGrad(Point(x1,y1), Point(x2,y2)))
            if (horizontal && (gradient < 1) && (lineCounter > 0)) {
                lineCounter--
                strongLines.add(Pair(Point(x1,y1), Point(x2,y2)))
            } else if (!horizontal && gradient > 1 && lineCounter > 0) {
                lineCounter--
                strongLines.add(Pair(Point(x1,y1), Point(x2,y2)))
            }

        }

        return strongLines
    }

    private fun drawLines(lines: List<Pair<Point,Point>>, canvas: Mat) : Mat{
        var counter = 0

        val colors = listOf(
            Scalar(255.0, 0.0, 0.0),   // Blue
            Scalar(0.0, 255.0, 0.0),   // Green
            Scalar(0.0, 0.0, 255.0),   // Red
            Scalar(255.0, 255.0, 0.0), // Cyan
            Scalar(255.0, 0.0, 255.0), // Magenta
            Scalar(0.0, 255.0, 255.0) // Yellow
        )

        for(line in lines){
            //println("String " + counter.toString())
            //println(line.first)
            //println(line.second)
            Imgproc.line(canvas, line.first, line.second, colors[counter % 6], 1)
            counter++
        }
        println(counter)
        return canvas
    }

    private fun applyTransforms(mat: Mat): Pair<Mat, Mat>{
        val grayMat = Mat()
        val edgesMat = Mat()
        val maskedMat = Mat()


        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)

        Imgproc.GaussianBlur(grayMat, grayMat, Size(5.0, 5.0), 1.6)

        val clahe: CLAHE = Imgproc.createCLAHE(0.75, Size(16.0, 16.0))
        clahe.apply(grayMat, grayMat)

        Imgproc.Canny(grayMat, edgesMat, 50.0, 100.0)

        val maskMat =Mat(edgesMat.size(), edgesMat.type(), Scalar(255.0))
        val roi = Rect(0, 0, edgesMat.cols(), (edgesMat.rows() / 5) * 3)
        val roi2 = Rect(edgesMat.cols() - 2, 0, 2, edgesMat.rows())
        val roi3 = Rect(0, 0, 3, edgesMat.rows())
        maskMat.submat(roi).setTo(Scalar(0.0))
        maskMat.submat(roi2).setTo(Scalar(0.0))
        maskMat.submat(roi3).setTo(Scalar(0.0))

        Core.bitwise_and(edgesMat, maskMat, maskedMat)

        val horizontalMat = detectHorizontal(maskedMat)
        val verticalMat = detectVertical(maskedMat)

        //val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        //Imgproc.dilate(maskedMat, maskedMat, kernel, Point(-1.0, -1.0), 3)

        //Imgproc.erode(maskedMat, maskedMat, kernel, Point(-1.0, -1.0), 4)

        //val testMat = Mat()
        //Imgproc.morphologyEx(maskedMat, testMat, Imgproc.MORPH_CLOSE, kernel)


        return Pair(horizontalMat, verticalMat)
        //return Pair(maskedMat, maskMat)
    }

    private fun detectHorizontal(mat: Mat) : Mat{
        //Remove Horizontal
        val horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0,1.0))

        val noHorizontal = Mat()
        Imgproc.erode(mat, noHorizontal, horizontalKernel)

        val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 4.0))

        val processedMat = Mat()
        Imgproc.morphologyEx(noHorizontal, processedMat, Imgproc.MORPH_CLOSE, verticalKernel)

        //val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        //Imgproc.dilate(maskedMat, maskedMat, kernel, Point(-1.0, -1.0), 3)

        //Imgproc.erode(maskedMat, maskedMat, kernel, Point(-1.0, -1.0), 4)

        //Strengthen Vertical
        //Imgproc.Sobel(processedMat, processedMat, CvType.CV_16S, 1, 0)

        //Core.convertScaleAbs(processedMat, processedMat)

        return processedMat
    }

    private fun detectVertical(mat: Mat) : Mat{
        //Remove Horizontal
        val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0,2.0))

        val noVertical = Mat()
        Imgproc.erode(mat, noVertical, verticalKernel)

        val horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(8.0, 5.0))

        val processedMat = Mat()
        Imgproc.morphologyEx(noVertical, processedMat, Imgproc.MORPH_CLOSE, horizontalKernel)

        //Strengthen Horizontal
        //Imgproc.Sobel(processedMat, processedMat, CvType.CV_16S, 0, 1)

        //Core.convertScaleAbs(processedMat, processedMat)

        return processedMat
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
        val gradient = lineGrad(point1, point2)
        val intercept = if(point1.x == 0.0 || gradient == 0.0){
            point1.y
        } else{
            point1.y - (gradient * point1.x)
        }
        return intercept
    }

    /*private fun greatestCluster(yValues: List<Double>): List<Double> {
        if (yValues.isEmpty()) return emptyList()

        val yArray = yValues.map { doubleArrayOf(it) }.toTypedArray()

        val clusters = kmeans(yArray, 6)

        val sortClusters = yValues.indices.groupBy { clusters.y[it] }.mapValues { (_, indices) -> indices.map { yValues[it] } }

        println("clusters")
        for ((cluster, points) in sortClusters) {
            println(cluster)
            println(points)
        }

        val biggestCluster = sortClusters.maxByOrNull { it.value.size }?.value

        return if (biggestCluster.isNullOrEmpty()){
            emptyList()
        } else{
            biggestCluster
        }

    }*/


    private fun topString(yValues: List<Double>): Pair<Double, Double> {
        val gaps = mutableListOf<Double>()
        val sortedValues = yValues.sorted()
        for (i in 0 until sortedValues.size - 1) {
            for (j in (i + 1) until sortedValues.size) {
                val gap = abs(sortedValues[i] - sortedValues[j])
                if (gap > 5){
                    gaps.add(gap)
                }
            }
        }

        gaps.sort()

        val returnGap = if(gaps.size > 0){
            gaps[0]
        } else {
            0.0
        }

        val returnYVvlaue = if(sortedValues.isNotEmpty()){
            sortedValues[0]
        } else {
            0.0
        }

        return Pair(returnGap, returnYVvlaue)
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
        val guitar: List<List<Point?>>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(error: String, errorCode: Int = 0)
        fun onResults(resultBundle: ResultBundle)
    }
}

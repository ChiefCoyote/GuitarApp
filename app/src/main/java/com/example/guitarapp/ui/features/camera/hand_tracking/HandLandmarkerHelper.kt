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
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
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
        val maskMat = transformedMats.second

        var colorMat = Mat()
        val horizontalLines = Mat()
        val verticalLines = Mat()

        Imgproc.HoughLinesP(horizMat, horizontalLines, 1.0, Math.PI / 180, 100, 75.0, 20.0)
        Imgproc.cvtColor(horizMat, colorMat, Imgproc.COLOR_GRAY2BGR)

        val strongHorizontal = strongLines(horizontalLines, 6,true)

        val combinedHorizontal = combineLines(strongHorizontal, true)

        val extendedLines = extendLines(combinedHorizontal)

        val singleLines = removeDuplicateLines(extendedLines)

        val extrapolatedStrings = extrapolateStrings3(singleLines)

        val vertMat = detectVertical(maskMat, extrapolatedStrings)

        val lsd = Imgproc.createLineSegmentDetector()
        lsd.detect(vertMat, verticalLines)


        val strongVertical = strongLines(verticalLines, 50,false)
        val combinedVertical = combineLines(strongVertical, false)
        val ridgedVertical = verticalRidge(combinedVertical)
        val frets = selectFret(ridgedVertical)
        val extrapolatedFrets = extrapolateFret(frets).reversed().toMutableList()
        //return finalBitmap

        val combinedCoords = combineLocations(extrapolatedStrings, extrapolatedFrets)

        return normalizeCoords(combinedCoords, mat)
    }

    private fun combineLocations(strings: MutableList<Pair<Point,Point>>, frets: MutableList<Pair<Point,Point>>) :  MutableList<MutableList<Point?>>{
        val stringList = strings.take(6)
        val fretList = frets.take(6)
        val combinedCoords : MutableList<MutableList<Point?>> = MutableList(6) {MutableList(5) {null} }
        if (stringList.size != 6 || fretList.size != 6) return combinedCoords



        for(i in 0 until 6){
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

    private fun extrapolateFret(lines: MutableList<Pair<Point,Point>>) : MutableList<Pair<Point, Point>> {
        lines.sortBy { it.first.x }

        val rightmostFrets = lines.takeLast(6)

        if (rightmostFrets.size < 3) return emptyList<Pair<Point, Point>>().toMutableList()

        val fretDistances = mutableListOf<Double>()

        for (i in 0 until rightmostFrets.size - 1){
            fretDistances.add(rightmostFrets[i + 1].first.x - rightmostFrets[i].first.x)
        }

        val realRatio = 0.94387



        val extrapolatedFrets = mutableListOf<Pair<Point, Point>>()


        for (i in 1 until rightmostFrets.size + 1){
            extrapolatedFrets.add(rightmostFrets[rightmostFrets.size - i])
        }
        //CALCULATE DISTANCES
        val fillDistances = mutableListOf<Double>()

        for (i in 0 until extrapolatedFrets.size - 1) {
            fillDistances.add(extrapolatedFrets[i].first.x - extrapolatedFrets[i + 1].first.x)
        }
        if (fillDistances[0] < 20) return emptyList<Pair<Point, Point>>().toMutableList()

        //FORWARD PASS TO CORRECT ERRORS
        var forwardLoop = fillDistances.size
        var counter = 1
        while (counter < forwardLoop){
            if (fillDistances[counter] < (fillDistances[counter - 1] / realRatio) - 10){
                //REMOVE CURRENT FRET
                if(counter + 1 < fillDistances.size){
                    val ammendDistance = fillDistances[counter] + fillDistances[counter + 1]
                    fillDistances[counter + 1] = ammendDistance
                }

                fillDistances.removeAt(counter)
                extrapolatedFrets.removeAt(counter + 1)

                forwardLoop--
                counter--

            }
            else if (fillDistances[counter] > (fillDistances[counter - 1] / realRatio) + 10){
                //ADD NEW FRET
                val newDistance = fillDistances[counter - 1] / realRatio
                val previousFret = extrapolatedFrets[counter]

                val x1 = previousFret.first.x - newDistance
                val x2 = previousFret.second.x - newDistance

                if (x1 < 0 || x2 < 0) {
                    break
                }


                extrapolatedFrets.add(counter + 1 ,Pair(Point(x1,previousFret.first.y -40), Point(x2, previousFret.second.y + 40)))
                fillDistances.add(counter, newDistance)

                val ammendDistance = fillDistances[counter + 1] - newDistance
                fillDistances[counter + 1] = ammendDistance

                forwardLoop++


                //HAVE A MAX OF 20 FRETS TO STOP INFINITE LOOP
                if(forwardLoop > 20){
                    break
                }
            }
            counter++
        }

        //BACKWARD PASS TO FILL MISSING SLOTS

        var distance = fretDistances[0]

        var repeat = true
        while (repeat) {
            distance /= realRatio
            val currentFret = extrapolatedFrets.last()

            val x1 = currentFret.first.x - distance
            val x2 = currentFret.second.x - distance

            if (x1 < 0 && x2 < 0) {
                break
            }
            extrapolatedFrets.add(
                Pair(
                    Point(x1, currentFret.first.y),
                    Point(x2, currentFret.second.y)
                )
            )
        }

        return extrapolatedFrets

    }

    private fun strongLines(lines: Mat, cutoff: Int, horizontal: Boolean) : MutableList<Pair<Point,Point>> {
        var lineCounter = cutoff
        val strongLines = mutableListOf<Pair<Point,Point>>()

        for(i in 0 until lines.rows()){
            val line = lines.get(i, 0)
            var x1 = line[0]
            var y1 = line[1]
            var x2 = line[2]
            var y2 = line[3]
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
            Imgproc.line(canvas, line.first, line.second, colors[counter % 6], 1)
            counter++
        }
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
        val roi = Rect(0, 0, edgesMat.cols(), (edgesMat.rows() / 7) * 3)
        val roi2 = Rect(edgesMat.cols() - 2, 0, 2, edgesMat.rows())
        val roi3 = Rect(0, 0, 3, edgesMat.rows())
        maskMat.submat(roi).setTo(Scalar(0.0))
        maskMat.submat(roi2).setTo(Scalar(0.0))
        maskMat.submat(roi3).setTo(Scalar(0.0))

        Core.bitwise_and(edgesMat, maskMat, maskedMat)

        val horizontalMat = detectHorizontal(maskedMat)

        return Pair(horizontalMat, maskedMat)
    }

    private fun detectHorizontal(mat: Mat) : Mat{
        //Remove Horizontal
        val horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0,1.0))

        val noHorizontal = Mat()
        Imgproc.erode(mat, noHorizontal, horizontalKernel)

        val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 4.0))

        val processedMat = Mat()
        Imgproc.morphologyEx(noHorizontal, processedMat, Imgproc.MORPH_CLOSE, verticalKernel)

        return processedMat
    }

    private fun detectVertical(mat: Mat, stringList: MutableList<Pair<Point,Point>>) : Mat{
        val result = Mat()

        if (stringList.size > 1){
            //Mask to fretboard
            val topString = stringList[0]
            val bottomString = stringList.last()

            val maskMat =Mat(mat.size(), mat.type(), Scalar(0.0))
            val polygon = ArrayList<Point>()
            polygon.add(topString.first)
            polygon.add(topString.second)
            polygon.add(bottomString.second)
            polygon.add(bottomString.first)

            val matOfPoint = MatOfPoint(*polygon.toTypedArray())
            Imgproc.fillPoly(maskMat, listOf(matOfPoint), Scalar(255.0))



            mat.copyTo(result, maskMat)
        } else {
            mat.copyTo(result)
        }



        //Remove Horizontal
        val verticalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0,2.0))

        val noVertical = Mat()
        Imgproc.erode(result, noVertical, verticalKernel)

        val horizontalKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(8.0, 5.0))

        val processedMat = Mat()
        Imgproc.morphologyEx(noVertical, processedMat, Imgproc.MORPH_CLOSE, horizontalKernel)

        return processedMat
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

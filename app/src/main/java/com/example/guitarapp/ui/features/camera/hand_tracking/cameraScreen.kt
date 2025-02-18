package com.example.guitarapp.ui.features.camera.hand_tracking

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import kotlin.math.abs
import androidx.camera.core.Preview as camPreview
import androidx.compose.ui.graphics.Color as graphColor

@Composable
fun CameraScreen(
    cameraViewModel: CameraViewModel = viewModel()


) {
    val overlayViewModel: OverlayViewModel = viewModel(factory = OverlayModelFactory((LocalContext.current.applicationContext as TabApplication).repository))
    // Use overlayViewModel as needed
    CameraContent(cameraViewModel, overlayViewModel)

}
//
@Composable
private fun CameraContent(cameraViewModel: CameraViewModel, overlayViewModel: OverlayViewModel) {
    val cameraState : CameraState by cameraViewModel.state.collectAsStateWithLifecycle()
    val handTrackingResult by cameraViewModel.handTrackingResult.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val screenWidth = configuration.screenWidthDp * density
    val screenHeight = configuration.screenHeightDp * density
    val backgroundExecutor = remember{ Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            backgroundExecutor.shutdown()
        }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    //val cameraController = remember { LifecycleCameraController(context) }

    val windowManager = context.getSystemService(WindowManager::class.java)
    val metrics = windowManager?.currentWindowMetrics
    val bounds = metrics?.bounds

    val width = bounds?.width() ?: 0
    val height = bounds?.height() ?: 0

    var fileName by remember { mutableStateOf("No file selected") }
    var fileContent by remember { mutableStateOf("") }

    val overlayTab by overlayViewModel.overlayTab.collectAsStateWithLifecycle()

    var cameraProvider: ProcessCameraProvider
    var preview: camPreview
    var imageAnalysis: ImageAnalysis
    var cameraSelector: CameraSelector


    cameraViewModel.startHandTracking(context, backgroundExecutor)

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }


    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

    cameraProviderFuture.addListener({

        cameraProvider = cameraProviderFuture.get()

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()


        preview = camPreview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(context.display.rotation)
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(backgroundExecutor) { imageProxy ->
            cameraViewModel.detectHand(imageProxy)
        }



        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview, imageAnalysis
            )

            preview.surfaceProvider = previewView.surfaceProvider
        }
        catch (exc: Exception) {
            exc.printStackTrace()
        }


    }, ContextCompat.getMainExecutor(context))


    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            fileName = getFileName(context, it)
            fileContent = readTextFromUri(context, it)
            val content = verifyContent(content = fileContent)
            if (content != null ){
                overlayViewModel.addtabString(TabString(name = content.first, content = content.second))
                Toast.makeText(context, content.first + " was uploaded", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "File format is not correct", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                factory = { previewView }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
            ){
                val blackbarSize = (width - ((height / 3) * 4)) / 2
                Column (
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .width(Dp(((width - ((height / 3) * 4))).toFloat()))
                ){
                    Row(
                        modifier = Modifier
                            .padding(paddingValues)
                    ){

                        Spacer(modifier = Modifier.width(Dp(10.0f)))
                        Button(onClick = { overlayViewModel.previousTab() }, modifier = Modifier.width(Dp((blackbarSize / 7).toFloat()))) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Camera")
                        }
                        Spacer(modifier = Modifier.width(Dp(8.0f)))
                        Button(onClick = { overlayViewModel.nextTab() }, modifier = Modifier.width(Dp((blackbarSize / 7).toFloat()))) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Camera")
                        }
                    }
                    Spacer(modifier = Modifier.height(Dp(8.0f)))
                    Row(
                        modifier = Modifier
                            .padding(paddingValues)
                    ){
                        Spacer(modifier = Modifier.width(Dp(12.0f)))
                        Text(
                            text = "Select Chord",
                            style = TextStyle(color = graphColor(255f, 255f, 255f, 1f)),
                            fontSize = 24.sp
                        )
                    }

                }

                Row(
                    modifier = Modifier
                        .padding(paddingValues)
                        .align(Alignment.BottomStart)
                ){
                    Spacer(modifier = Modifier.width(Dp(10.0f)))
                    Button(onClick = {
                        filePickerLauncher.launch("text/plain")
                    }, modifier = Modifier.width(Dp((blackbarSize / 7).toFloat()))){
                        Icon(imageVector = Icons.Filled.Upload, contentDescription = "Upload")
                    }
                    Spacer(modifier = Modifier.width(Dp(10.0f)))
                    Button(onClick = {overlayViewModel.deleteTab()}, modifier = Modifier.width(Dp((blackbarSize / 7).toFloat()))) {
                        Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }

                Row (
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(160.dp)
                        .height(80.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ){
                    Spacer(modifier = Modifier.height(Dp(30.0f)))
                    Column(
                        modifier = Modifier
                            .padding(paddingValues)
                    ){
                        Text(
                            text = overlayTab.name ?:"",
                            style = TextStyle(color = graphColor(255f, 255f, 255f, 1f)),
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(Dp(12.0f)))

                    }


                }

            }




            Canvas(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
            ) {
                val blackbarSize = (width - ((height / 3) * 4)) / 2

                val start = width - blackbarSize
                val end = width
                val barWidth = blackbarSize
                val barHeight = height
                val chartWidth = (barWidth/20) * 14
                val chartHeight = (height/2)
                val chartPad = (barWidth/20) * 3
                val chartStart = start + chartPad
                val chartEnd = chartStart + chartWidth

                val stringSpace = chartWidth / 5
                val fretSpace = chartHeight / 5

                val firstFretLocation = (height/3) - (fretSpace / 2)

                val grid: Array<Array<Point>> = Array(7) {row ->
                    Array(6) { col ->
                        Point(firstFretLocation + (row * fretSpace), chartStart + (col * stringSpace))
                    }
                }

                drawRect(
                    color = androidx.compose.ui.graphics.Color.White,
                    topLeft = Offset(chartStart - 2f, (height/3f) - 20),
                    size = Size(chartWidth.toFloat() + 4, 20f)
                )

                drawRect(
                    color = androidx.compose.ui.graphics.Color.White,
                    topLeft = Offset(chartStart.toFloat(), (height/3f)),
                    size = Size(chartWidth.toFloat(), chartHeight.toFloat()),
                    style = Stroke(width=4f)
                )

                for (i in 0 until 5){
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.White,
                        start = Offset(chartStart.toFloat() + (i * stringSpace), (height/3f)),
                        end = Offset(chartStart.toFloat() + (i * stringSpace), (height/3f) + chartHeight),
                        strokeWidth = 4f
                    )
                }

                for (i in 0 until 5){
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.White,
                        start = Offset(chartStart.toFloat(), (height/3f) + (i * fretSpace)),
                        end = Offset(chartEnd.toFloat(), (height/3f) + (i * fretSpace)),
                        strokeWidth = 5f
                    )
                }
                /*for (row in grid) {
                    for (col in row) {
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.White,
                            center = Offset(col.y.toFloat(), col.x.toFloat()),
                            radius = 20f
                        )
                    }
                }*/
                val content  = overlayTab.content
                if(content != null){
                    val notes = content.split(",")
                    val playedStringsList = mutableListOf<Int>()
                    for (i in 0 until 6){
                        val index = 5-i
                        val split = notes[i].split("-")
                        val finger = split[0]
                        val fret = split[1]

                        if (finger == "_"){
                            if (fret == "0"){
                                drawIntoCanvas { canvas ->
                                    // Create and configure a Paint object
                                    val paint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.WHITE
                                        textSize = 48f  // Set your desired text size (in pixels)
                                        isAntiAlias = true
                                    }
                                    // Draw text on the canvas at position (x, y)
                                    val point = grid[0][index]
                                    canvas.nativeCanvas.drawText("O", point.y.toFloat() - 15, point.x.toFloat(), paint)
                                }
                            } else if (fret == "x"){
                                drawIntoCanvas { canvas ->
                                    // Create and configure a Paint object
                                    val paint = android.graphics.Paint().apply {
                                        color = android.graphics.Color.WHITE
                                        textSize = 48f  // Set your desired text size (in pixels)
                                        isAntiAlias = true
                                    }
                                    // Draw text on the canvas at position (x, y)
                                    val point = grid[0][index]
                                    canvas.nativeCanvas.drawText("X", point.y.toFloat() - 15, point.x.toFloat(), paint)
                                }
                            }
                        } else{
                            playedStringsList.add(i)
                            val notePoint = grid[fret.toInt()][index]
                            drawCircle(
                                color = androidx.compose.ui.graphics.Color.White,
                                center = Offset(notePoint.y.toFloat(), notePoint.x.toFloat()),
                                radius = 20f
                            )


                            drawIntoCanvas { canvas ->
                                // Create and configure a Paint object
                                val paint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.WHITE
                                    textSize = 48f  // Set your desired text size (in pixels)
                                    isAntiAlias = true
                                }
                                // Draw text on the canvas at position (x, y)
                                val point = grid[6][index]
                                canvas.nativeCanvas.drawText(finger , point.y.toFloat() - 15, point.x.toFloat(), paint)
                            }
                        }
                    }

                    when(handTrackingResult){
                        is Result.Success -> {
                            val data = (handTrackingResult as Result.Success).resultBundle
                            val stringLocations = data.guitar
                            val imageWidth = data.inputImageWidth
                            val imageHeight = data.inputImageHeight
                            val widthScaleFactor = (width * 1f) / imageWidth
                            val heightScaleFactor = (height * 1f) / imageHeight

                            val landmarkIndices = intArrayOf(4,8,12,16,20)

                            val landMarkData =data.results.first()
                            for (landmark in landMarkData.landmarks()){
                                for (landmarkindex in landmarkIndices) {
                                    drawCircle(
                                        color = androidx.compose.ui.graphics.Color.Red,
                                        center = Offset((landmark[landmarkindex].x() * (width - blackbarSize - blackbarSize)) + blackbarSize,landmark[landmarkindex].y() * imageHeight * heightScaleFactor),
                                        radius = 20f
                                    )
                                }
                            }

                            if(stringLocations.isNotEmpty()){
                                for (playedString in playedStringsList){
                                    val string = stringLocations[5- playedString]
                                    drawLine(
                                        color = androidx.compose.ui.graphics.Color.Green,
                                        start = Offset(((string.first.x.toFloat()) * (width - blackbarSize - blackbarSize)) + blackbarSize, string.first.y.toFloat() * imageHeight * heightScaleFactor),
                                        end = Offset(((string.second.x.toFloat()) * (width - blackbarSize - blackbarSize)) + blackbarSize, string.second.y.toFloat() * imageHeight * heightScaleFactor),
                                        strokeWidth = 5f
                                    )
                                }
                            }



                        }

                        is Result.Loading -> {
                            println("Loading")
                        }

                        is Result.Error -> {
                            println("Error")
                        }
                    }

                }





            }
        }

    }

}

fun getFileName(context: Context, uri: Uri): String {
    var name = "Unknown"
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                name = it.getString(nameIndex)
            }
        }
    }
    return name
}

fun readTextFromUri(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).readText()
        } ?: "Error reading file"
    } catch (e: Exception) {
        Log.e("FilePicker", "Error reading file", e)
        "Error reading file"
    }
}

private fun verifyContent(content: String) : Pair<String,String>? {
    val parts = content.split("|")

    if (parts.size!=2) return null

    val nameSection = parts[0]
    val tabSection = parts[1]
    val tabs = tabSection.split(",")

    val nameRegex = "^[A-Za-z0-9 ]{1,14}$".toRegex()

    if (!nameRegex.matches(nameSection)) return null

    if (tabs.size!=6) return null
    val seenFingers = booleanArrayOf(false,false,false,false,false)
    var smallestFret = 20
    var biggestFret = 1
    for (tab in tabs){
        val fingerFretPairs = tab.split("-")

        if (fingerFretPairs.size != 2) return null

        val finger = fingerFretPairs[0]
        val fret = fingerFretPairs[1]

        val completeFingerRegex = "^[1-5_]$".toRegex()
        val completeFretRegex = "^(?:x|[0-9]|1\\d|20)\$".toRegex()
        if (!(completeFingerRegex.matches(finger) && completeFretRegex.matches(fret))) return null

        if (finger == "_" && !(fret == "x" || fret == "0")) return null

        val fingerRegex = "^[1-5]$".toRegex()
        val fretRegex = "^[1-20]$".toRegex()

        if(fingerRegex.matches(finger)){
            if (fret =="x" || fret == "0"){
                return null
            }
            if (seenFingers[finger.toInt() - 1]){
                return null
            } else {
                seenFingers[finger.toInt() - 1] = true
            }
        }

        if (fretRegex.matches(fret)){
            if (fret.toInt() < smallestFret) smallestFret = fret.toInt()
            if (fret.toInt() > biggestFret) biggestFret = fret.toInt()
        }
    }
    if(((abs(biggestFret - smallestFret) > 5)) && seenFingers.contains(true)) return null

    return Pair(nameSection, tabSection)
}


@Preview
@Composable
private fun Preview_CameraContent() {
    CameraContent(viewModel(), viewModel())
}
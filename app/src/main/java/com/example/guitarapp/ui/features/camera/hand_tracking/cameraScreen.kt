package com.example.guitarapp.ui.features.camera.hand_tracking

import android.content.Context
import android.graphics.Color
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
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

            }




            Canvas(modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
            ) {
                when(handTrackingResult){
                    is Result.Success -> {
                        val data = (handTrackingResult as Result.Success).resultBundle
                        val imageWidth = data.inputImageWidth
                        val imageHeight = data.inputImageHeight
                        val widthScaleFactor = (width * 1f) / imageWidth
                        val heightScaleFactor = (height * 1f) / imageHeight
                        val blackbarSize = (width - ((height / 3) * 4)) / 2
                        val landMarkData =data.results.first()
                        for (landmark in landMarkData.landmarks()){
                            for (normalisedLandmark in landmark) {
                                drawCircle(
                                    color = androidx.compose.ui.graphics.Color.Red,
                                    center = Offset((normalisedLandmark.x() * (width - blackbarSize - blackbarSize)) + blackbarSize,normalisedLandmark.y() * imageHeight * heightScaleFactor),
                                    radius = 10f
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
    }

    println("9")
    return Pair(nameSection, tabSection)
}


private fun switch() {
    println("click")

}

@Preview
@Composable
private fun Preview_CameraContent() {
    CameraContent(viewModel(), viewModel())
}
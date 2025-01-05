package com.example.guitarapp.ui.features.camera.hand_tracking

import android.graphics.Color
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.Executors
import androidx.camera.core.Preview as camPreview

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
) {
    CameraContent(viewModel)
}

@Composable
private fun CameraContent(viewModel: CameraViewModel) {
    val cameraState : CameraState by viewModel.state.collectAsStateWithLifecycle()
    val handTrackingResult by viewModel.handTrackingResult.collectAsStateWithLifecycle()
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

    var cameraProvider: ProcessCameraProvider
    var preview: camPreview
    var imageAnalysis: ImageAnalysis
    var cameraSelector: CameraSelector


    viewModel.startHandTracking(context, backgroundExecutor)

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
            viewModel.detectHand(imageProxy)
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


    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues: PaddingValues ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            factory = { previewView }
        )

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
                                center = Offset((((normalisedLandmark.x() * imageWidth * widthScaleFactor) / 4 ) * 3),normalisedLandmark.y() * imageHeight * heightScaleFactor),
                                radius = 10f
                            )
                        }
                    }

                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.Blue,
                        center = Offset(0f + blackbarSize,0f),
                        radius = 50f
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.Blue,
                        center = Offset(2340f - blackbarSize,0f),
                        radius = 50f
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.Blue,
                        center = Offset(0f + blackbarSize,1080f),
                        radius = 50f
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.Blue,
                        center = Offset(2340f - blackbarSize,1080f),
                        radius = 50f
                    )
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


@Preview
@Composable
private fun Preview_CameraContent() {
    CameraContent(viewModel())
}
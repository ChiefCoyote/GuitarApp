package com.example.guitarapp.ui.features.camera.hand_tracking

import android.graphics.Color
import android.view.Surface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val backgroundExecutor = Executors.newSingleThreadExecutor()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    //val cameraController = remember { LifecycleCameraController(context) }

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
            .setTargetRotation(context.display.rotation ?: Surface.ROTATION_90)
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

    /*val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
        .build()*/

    //cameraController.setCameraSelector(cameraSelector)

    /*cameraController.setImageAnalysisAnalyzer(backgroundExecutor) { imageProxy ->
        imageProxy.use { anImageProxy ->
            viewModel.detectHand(anImageProxy)
        }
    }*/




    /*LaunchedEffect(previewView) {

        previewView.controller = cameraController

        cameraController.bindToLifecycle(lifecycleOwner)
    }*/

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues: PaddingValues ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            factory = { previewView }
        )
    }

}


@Preview
@Composable
private fun Preview_CameraContent() {
    CameraContent(viewModel())
}
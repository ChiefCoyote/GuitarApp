package com.example.guitarapp.ui.features.camera.hand_tracking

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {

    CameraContent()
}

@Composable
private fun CameraContent() {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { LifecycleCameraController(context) }

    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
        .build()

    cameraController.setCameraSelector(cameraSelector)

    /*LaunchedEffect(cameraController) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        cameraController.cameraProvider = cameraProvider
    }*/

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues: PaddingValues ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            factory = { context: Context ->
                PreviewView(context).apply{
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    setBackgroundColor(Color.BLACK)
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }.also { previewView ->
                    previewView.controller = cameraController
                    cameraController.bindToLifecycle(lifecycleOwner)
                }

        })
    }
}

@Preview
@Composable
private fun Preview_CameraContent() {
    CameraContent()
}
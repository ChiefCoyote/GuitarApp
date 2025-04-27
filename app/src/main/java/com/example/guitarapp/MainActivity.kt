package com.example.guitarapp

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.guitarapp.ui.MainScreen
import com.example.guitarapp.ui.features.camera.hand_tracking.OverlayModelFactory
import com.example.guitarapp.ui.features.camera.hand_tracking.OverlayViewModel
import com.example.guitarapp.ui.features.camera.hand_tracking.TabApplication
import com.example.guitarapp.ui.theme.GuitarAppTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val overlayViewModel: OverlayViewModel by viewModels{
        OverlayModelFactory((application as TabApplication).repository)
    }
    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val window = window
        val controller = window.insetsController
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars())
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if(OpenCVLoader.initLocal()) {
            println("loaded")
        }

        setContent {
            GuitarAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

}
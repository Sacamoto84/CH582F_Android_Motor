package com.example.ch582motor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ch582motor.ui.AppRoot
import com.example.ch582motor.ui.theme.Ch582motorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ch582motorTheme {
                AppRoot()
            }
        }
    }
}

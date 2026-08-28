package com.trapezo.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trapezo.pos.ui.TrapezoRoot
import com.trapezo.pos.ui.theme.TrapezoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrapezoTheme {
                TrapezoRoot()
            }
        }
    }
}

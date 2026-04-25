package com.heirloom.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.heirloom.app.ui.RestoreScreen
import com.heirloom.app.ui.theme.HeirloomTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HeirloomTheme {
                RestoreScreen()
            }
        }
    }
}

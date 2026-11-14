package org.fanficarchive.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.fanficarchive.android.screens.FanficNavHost
import org.fanficarchive.android.screens.theme.FanficTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FanficApp
        setContent {
            FanficTheme {
                FanficNavHost(repository = app.repository)
            }
        }
    }
}

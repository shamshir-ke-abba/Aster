package com.aster.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.aster.app.ui.navigation.AsterNavHost
import com.aster.app.ui.theme.AsterTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Aster's single Activity.
 *
 * Edge-to-edge is enabled via [enableEdgeToEdge], which is the canonical
 * modern approach (Activity.enableEdgeToEdge from activity-ktx 1.8+).
 *
 * The Compose content draws behind system bars, and individual screens
 * consume window insets via WindowInsets padding where appropriate.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsterTheme {
                AsterNavHost(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

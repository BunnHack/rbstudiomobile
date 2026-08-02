package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.RobloxLoginScreen
import com.example.ui.StudioScreen
import com.example.ui.TemplatesScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodels.StudioViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enterImmersiveEditorMode()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StudioAppEntry(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveEditorMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveEditorMode()
    }

    private fun enterImmersiveEditorMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }
}

@Composable
fun StudioAppEntry(modifier: Modifier = Modifier) {
    val viewModel: StudioViewModel = viewModel()
    val isLauncherActive by viewModel.isLauncherActive.collectAsState()
    val roblosecurityCookie by viewModel.roblosecurityCookie.collectAsState()
    var showLogin by remember { mutableStateOf(roblosecurityCookie.isBlank()) }

    Box(modifier = modifier.fillMaxSize()) {
        if (showLogin && roblosecurityCookie.isBlank()) {
            RobloxLoginScreen(
                onCookieCaptured = { cookie ->
                    viewModel.saveRobloxLoginCookie(cookie)
                    showLogin = false
                },
                onContinueOffline = {
                    showLogin = false
                }
            )
        } else {
            if (isLauncherActive) {
                TemplatesScreen(viewModel = viewModel)
            } else {
                StudioScreen(viewModel = viewModel)
            }
        }
    }
}

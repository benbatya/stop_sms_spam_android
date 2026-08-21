package com.batya.stopsmsspam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.batya.stopsmsspam.ui.AppRoot
import com.batya.stopsmsspam.ui.MainViewModel
import com.batya.stopsmsspam.ui.theme.StopSpamTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            StopSpamTheme {
                AppRoot(viewModel)
            }
        }
    }

    /**
     * The SMS role and the runtime permissions are both granted in system UI we navigate away
     * to, so the app re-reads them every time it comes back to the foreground.
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshEnvironment()
    }
}

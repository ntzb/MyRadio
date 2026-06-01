package com.ntzb.myradio.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ntzb.myradio.ui.theme.MyRadioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyRadioTheme {
                RequestNotificationPermission()
                val vm: RadioViewModel = viewModel()
                RadioRoot(vm)
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
fun RadioRoot(vm: RadioViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    if (showNowPlaying && state.current != null) {
        NowPlayingScreen(state, vm, onBack = { showNowPlaying = false })
    } else {
        RadioScreen(
            state = state,
            vm = vm,
            onOpenNowPlaying = { if (state.current != null) showNowPlaying = true }
        )
    }
}

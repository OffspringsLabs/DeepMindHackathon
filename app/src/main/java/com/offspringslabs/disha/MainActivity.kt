package com.offspringslabs.disha

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.offspringslabs.disha.ui.DishaScreen
import com.offspringslabs.disha.ui.DishaTheme

class MainActivity : ComponentActivity() {
    private val vm: DishaViewModel by viewModels()

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.onMicTap() else vm.setStatus("Microphone permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DishaTheme {
                DishaScreen(vm = vm, onMic = ::onMic)
            }
        }
    }

    private fun onMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.onMicTap()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

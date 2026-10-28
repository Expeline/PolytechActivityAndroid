package ru.expeline.polytech

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.expeline.polytech.ui.PolytechApp
import ru.expeline.polytech.ui.ScheduleViewModel
import ru.expeline.polytech.ui.ScheduleViewModelFactory

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val app = application as PolytechApplication
            val model: ScheduleViewModel = viewModel(factory = ScheduleViewModelFactory(app.repository, app))
            PolytechApp(model)
        }
    }
}


package dev.ilgax.utils

import android.Manifest
import android.content.pm.PackageManager
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

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ilgax.utils.core.ModuleRegistry
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.ui.theme.UtilsTheme
import dev.ilgax.utils.ui.UtilsRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val registry: ModuleRegistry get() = (application as UtilsApplication).registry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UtilsTheme { UtilsApp(registry) }
        }
    }

    override fun onStart() {
        super.onStart()
        ShizukuState.refresh()
        registry.modules.forEach { it.onAppVisible(true) }
    }

    override fun onStop() {
        registry.modules.forEach { it.onAppVisible(false) }
        super.onStop()
    }
}

/** Stateful wrapper around the stateless [UtilsRoot]: selection survives rotation and process death. */
@Composable
private fun UtilsApp(registry: ModuleRegistry) {
    val shizuku by ShizukuState.status.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val activity = androidx.compose.ui.platform.LocalContext.current

    // Foreground services started by the modules need the notification permission to show their status.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    UtilsRoot(
        modules = registry.modules,
        shizuku = shizuku,
        selectedId = selectedId,
        onSelect = { selectedId = it },
        onToggle = { module -> scope.launch { module.toggle() } },
    )
}

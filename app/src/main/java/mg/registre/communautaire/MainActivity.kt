package mg.registre.communautaire

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import mg.registre.communautaire.ui.MainViewModel
import mg.registre.communautaire.ui.RegistreApp
import mg.registre.communautaire.ui.theme.RegistreTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationsIfNeeded()

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            RegistreTheme(fontScale = state.fontScale) {
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(650)
                    splash = false
                }

                if (splash) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.88f),
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = androidx.compose.ui.unit.dp(4f),
                            )
                        }
                    }
                } else {
                    RegistreApp(
                        state = state,
                        onSelectType = viewModel::selectType,
                        onSelectYear = viewModel::selectYear,
                        onFontScale = viewModel::setFontScale,
                        onSaveStandard = viewModel::saveStandard,
                        onSavePermission = viewModel::savePermission,
                        onClearError = viewModel::clearError,
                    )
                }
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

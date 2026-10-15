package nl.jan.airco

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.jan.airco.ui.LoginScreen
import nl.jan.airco.ui.UnitsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AircoTheme {
                val vm: AircoViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                when (state.screen) {
                    Screen.Login -> LoginScreen(state, vm)
                    Screen.Units -> UnitsScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun AircoTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> darkColorScheme(primary = Color(0xFF9ECAFF))
        else -> lightColorScheme(primary = Color(0xFF1E5FA8))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

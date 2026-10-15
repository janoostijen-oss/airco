package nl.jan.airco

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.jan.airco.ui.Ink
import nl.jan.airco.ui.LoginScreen
import nl.jan.airco.ui.Manrope
import nl.jan.airco.ui.UnitsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Altijd donker ontwerp: lichte iconen in status- en navigatiebalk.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
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
    val colors = darkColorScheme(
        primary = Ink.Accent,
        onPrimary = Ink.Ground,
        background = Ink.Ground,
        onBackground = Ink.Text,
        surface = Color(0xFF151922),
        onSurface = Ink.Text,
        surfaceVariant = Color(0xFF1C2029),
        onSurfaceVariant = Ink.Muted2,
        outline = Color(0xFF2E3442),
        error = Ink.Error,
    )
    val base = Typography()
    val typography = Typography(
        bodyLarge = base.bodyLarge.withFont(),
        bodyMedium = base.bodyMedium.withFont(),
        bodySmall = base.bodySmall.withFont(),
        labelLarge = base.labelLarge.withFont(),
        labelMedium = base.labelMedium.withFont(),
        titleMedium = base.titleMedium.withFont(),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

private fun TextStyle.withFont() = copy(fontFamily = Manrope)

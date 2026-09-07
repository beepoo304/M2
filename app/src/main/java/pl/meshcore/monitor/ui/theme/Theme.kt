package pl.meshcore.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MeshCoreColors = darkColorScheme(
    primary = Color(0xFF67D78A),
    onPrimary = Color(0xFF05210D),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE2E5E3),
    surface = Color(0xFF171A1C),
    onSurface = Color(0xFFD6D9D7),
    surfaceVariant = Color(0xFF23272A),
    onSurfaceVariant = Color(0xFF9DA4A0),
    outline = Color(0xFF3B4140),
    error = Color(0xFFFF6B6B),
)

@Composable
fun MeshCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshCoreColors,
        content = content,
    )
}

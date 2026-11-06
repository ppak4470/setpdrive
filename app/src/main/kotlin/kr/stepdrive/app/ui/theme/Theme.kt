package kr.stepdrive.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cockpit palette shared with the design mockups.
val Bg = Color(0xFF0A0E13)
val Surface1 = Color(0xFF121820)
val Surface2 = Color(0xFF18212B)
val Surface3 = Color(0xFF202B37)
val Line = Color(0xFF26323F)
val Tx = Color(0xFFEAF0F6)
val Mu = Color(0xFF8D9BAC)
val Dim = Color(0xFF5D6B7B)
val Accent = Color(0xFF4C8DFF)
val Ok = Color(0xFF3DD68C)
val Warn = Color(0xFFFFB547)
val Bad = Color(0xFFFF5F5F)

@Composable
fun StepDriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Tx,
            surface = Surface1,
            onSurface = Tx,
            surfaceVariant = Surface2,
            onSurfaceVariant = Mu,
            outline = Line,
            error = Bad,
        ),
        content = content,
    )
}

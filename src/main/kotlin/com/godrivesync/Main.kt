package com.godrivesync

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.skia.Image
import java.awt.Dimension
import com.godrivesync.data.preferences.AppearancePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ICON_RESOURCE = "/icons/app-icon.png"

fun main() {
    val preferences = AppearancePreferences()
    // Read before creating the window to avoid flashing a different theme at startup.
    val initialStyle = preferences.loadStyle()
    application {
        val icon = remember { loadWindowIcon() }
        var style by remember { mutableStateOf(initialStyle) }
        var themeSaveFailed by remember { mutableStateOf(false) }
        var saveRevision by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            // A single collector serializes writes, including rapid theme changes.
            snapshotFlow { style to saveRevision }.collect { (selected, _) ->
                themeSaveFailed = try {
                    withContext(Dispatchers.IO) { preferences.saveStyle(selected) }
                    false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Go Drive Sync",
            icon = icon,
            state = rememberWindowState(
                position = WindowPosition(Alignment.Center),
                size = DpSize(1120.dp, 800.dp),
            ),
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(900, 640) }
            App(style, { style = it }, icon, themeSaveFailed, { saveRevision++ })
        }
    }
}

fun loadWindowIcon(): Painter? {
    val stream = object {}::class.java.getResourceAsStream(ICON_RESOURCE) ?: return null
    return try {
        val bytes = stream.use { it.readAllBytes() }
        BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
    } catch (_: Exception) {
        null
    }
}

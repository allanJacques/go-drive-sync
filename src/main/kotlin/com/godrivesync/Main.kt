package com.godrivesync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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

private const val ICON_RESOURCE = "/icons/app-icon.png"

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Go Drive Sync",
        icon = loadWindowIcon(),
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = DpSize(1024.dp, 720.dp),
        ),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {}
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
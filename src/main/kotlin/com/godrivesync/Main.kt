package com.godrivesync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Go Drive Sync",
        state = WindowState(size = DpSize(1024.dp, 720.dp)),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
        }
    }
}
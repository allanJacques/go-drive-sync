package com.godrivesync.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Small, cached vectors: no icon font or additional icon library to load.
object AppIcons {
    val Dashboard = lineIcon("Dashboard") {
        moveTo(3f, 3f); lineTo(10f, 3f); lineTo(10f, 10f); lineTo(3f, 10f); close()
        moveTo(14f, 3f); lineTo(21f, 3f); lineTo(21f, 10f); lineTo(14f, 10f); close()
        moveTo(3f, 14f); lineTo(10f, 14f); lineTo(10f, 21f); lineTo(3f, 21f); close()
        moveTo(14f, 14f); lineTo(21f, 14f); lineTo(21f, 21f); lineTo(14f, 21f); close()
    }
    val Sync = lineIcon("Sincronizar") {
        moveTo(20f, 9f); curveTo(18f, 3f, 10f, 1f, 5f, 7f)
        moveTo(20f, 3f); lineTo(20f, 9f); lineTo(14f, 9f)
        moveTo(4f, 15f); curveTo(6f, 21f, 14f, 23f, 19f, 17f)
        moveTo(4f, 21f); lineTo(4f, 15f); lineTo(10f, 15f)
    }
    val History = lineIcon("Histórico") {
        moveTo(21f, 12f)
        curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        curveTo(3f, 7f, 7f, 3f, 12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f)
    }
    val Folder = lineIcon("Mapeamentos") {
        moveTo(3f, 7f); lineTo(3f, 5f); lineTo(9f, 5f); lineTo(12f, 8f)
        lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(3f, 10f); lineTo(21f, 10f)
    }
    val Accounts = lineIcon("Contas") {
        moveTo(14f, 7f)
        curveTo(14f, 12f, 6f, 12f, 6f, 7f)
        curveTo(6f, 2f, 14f, 2f, 14f, 7f)
        moveTo(3f, 21f); lineTo(3f, 19f)
        curveTo(3f, 13f, 17f, 13f, 17f, 19f); lineTo(17f, 21f)
        moveTo(18f, 4f); curveTo(22f, 5f, 22f, 10f, 18f, 11f)
        moveTo(20f, 15f); curveTo(23f, 16f, 22f, 19f, 22f, 21f)
    }
    val Settings = lineIcon("Configurações") {
        moveTo(5f, 3f); lineTo(5f, 7f); moveTo(5f, 11f); lineTo(5f, 21f)
        moveTo(12f, 3f); lineTo(12f, 13f); moveTo(12f, 17f); lineTo(12f, 21f)
        moveTo(19f, 3f); lineTo(19f, 7f); moveTo(19f, 11f); lineTo(19f, 21f)
        moveTo(2f, 7f); lineTo(8f, 7f); lineTo(8f, 11f); lineTo(2f, 11f); close()
        moveTo(9f, 13f); lineTo(15f, 13f); lineTo(15f, 17f); lineTo(9f, 17f); close()
        moveTo(16f, 7f); lineTo(22f, 7f); lineTo(22f, 11f); lineTo(16f, 11f); close()
    }
    val ArrowRight = lineIcon("Avançar") {
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(14f, 6f); lineTo(20f, 12f); lineTo(14f, 18f)
    }
    val Cloud = lineIcon("Google Drive") {
        moveTo(6f, 18f); curveTo(0f, 18f, 0f, 10f, 6f, 10f)
        curveTo(6f, 2f, 18f, 2f, 18f, 10f)
        curveTo(24f, 10f, 24f, 18f, 18f, 18f); close()
    }
}

private fun lineIcon(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(
    name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )
}.build()

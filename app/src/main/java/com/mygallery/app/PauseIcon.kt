package com.mygallery.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Pause symbol drawn in code, because the extended icon set is not included in this build. */
val PauseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes("M6 19h4V5H6v14zm8-14v14h4V5h-4z"),
        fill = SolidColor(Color.Black),
    ).build()
}

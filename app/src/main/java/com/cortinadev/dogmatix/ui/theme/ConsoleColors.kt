package com.cortinadev.dogmatix.ui.theme

import androidx.compose.ui.graphics.Color
import com.cortinadev.dogmatix.util.ConsoleFamily

/** The colour of a console's family (see [ConsoleFamily]): chips, cover placeholders, charts. */
fun consoleColor(consoleId: String): Color = Color(ConsoleFamily.of(consoleId).argb)

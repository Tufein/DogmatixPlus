package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens

/**
 * Search box that stays out of the D-pad traversal: it only takes focus while [active]
 * (set by the Y button or a tap through [onActivate]) and hands it back on B / Back via [onDismiss].
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    active: Boolean,
    onActivate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    trailing: @Composable (() -> Unit)? = null,
    /**
     * Horizontal shift (px) of the box's left edge and content, read in the draw / placement
     * phases only: lets the filter panel animation drag the field along without re-measuring it.
     */
    contentShift: () -> Int = { 0 }
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Let `canFocus = active` reach the focus node before asking for focus.
        withFrameNanos { }
        val focusedNow = runCatching { focusRequester.requestFocus() }.getOrDefault(false)
        // Never leave `active` stuck on if the field could not take focus.
        if (!focusedNow) onDismiss()
    }
    LaunchedEffect(focused) {
        if (!focused && active) onDismiss()
    }

    // 5.0: a panel-like well (fill, hairline, a touch of top light in dark themes), drawn with the
    // panel slide's shift so it costs no relayout.
    val tokens = LocalDogmatixTokens.current
    val fill = scheme.surfaceContainer
    val hairline = if (active) scheme.primary.copy(alpha = 0.55f) else tokens.hairline
    val highlight = tokens.highlight
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawBehind {
                val shift = contentShift().toFloat()
                val radius = CornerRadius(12.dp.toPx())
                val area = Size(size.width - shift, size.height)
                drawRoundRect(fill, topLeft = Offset(shift, 0f), size = area, cornerRadius = radius)
                if (highlight.alpha > 0f) drawRoundRect(
                    Brush.verticalGradient(listOf(highlight, Color.Transparent), endY = size.height),
                    topLeft = Offset(shift, 0f), size = area, cornerRadius = radius
                )
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    hairline,
                    topLeft = Offset(shift + stroke / 2, stroke / 2),
                    size = Size(area.width - stroke, area.height - stroke),
                    cornerRadius = radius,
                    style = Stroke(stroke)
                )
            }
            .focusRing(source, 12.dp, startShift = contentShift)
            // pointerInput (not clickable): no focusable node, so the D-pad never lands on the box
            // and no focusProperties are needed here. A `canFocus = false` on this Row would leak
            // into the text field whenever the Row has no focus target of its own.
            .pointerInput(active) {
                detectTapGestures {
                    if (active) {
                        runCatching { focusRequester.requestFocus() }
                        keyboard?.show()
                    } else onActivate()
                }
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            // Clipped here (not on the whole field) so the focus halo outside the field stays visible.
            modifier = Modifier.weight(1f).clipToBounds().offset { IntOffset(contentShift(), 0) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = if (active || value.isNotEmpty()) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            interactionSource = source,
            // The field can only take focus while `active` (Y or tap), so focus == open keyboard.
            // The D-pad can never land here on its own, hence no showKeyboardOnFocus = false.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .focusProperties { canFocus = active }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.ButtonA -> { keyboard?.show(); true }
                        Key.Back, Key.ButtonB, Key.Escape -> {
                            keyboard?.hide()
                            focusManager.clearFocus()
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && !focused) {
                        Text(
                            stringResource(R.string.search_library),
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    inner()
                    if (!active) {
                        // While inactive the text field swallows taps but cannot focus; catch them here.
                        Box(
                            Modifier
                                .matchParentSize()
                                .pointerInput(Unit) { detectTapGestures { onActivate() } }
                        )
                    }
                }
            }
        )
        }
        trailing?.invoke()
    }
}

package com.termux.terminal.compose

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Repeat interval in milliseconds. */
private const val REPEAT_DELAY = 80L

/**
 * Callback interface for extra key button clicks.
 */
fun interface ExtraKeysCallback {
    /**
     * Called when an extra key button is clicked.
     *
     * @param key The key identifier (e.g., "ESC", "CTRL", "TAB")
     * @param isMacro Whether this is a macro (space-separated key sequence)
     */
    fun onKeyClick(key: String, isMacro: Boolean)
}

/**
 * Extra keys bar composable for terminal control keys.
 *
 * Renders the extra keys as a [HorizontalPager]: the configured rows are the first
 * page, and additional pages (e.g. special keys like F1-F12, INS, DEL) are reachable
 * by swiping sideways, with a dot indicator when more than one page exists.
 *
 * Supports long-press repeat for navigation and editing keys, and modifier lock on long-press.
 *
 * @param config The extra keys configuration
 * @param activeModifiers Sticky modifier keys currently active (e.g. "CTRL")
 * @param onToggleModifier Callback to toggle a sticky modifier key
 * @param callback Callback for key clicks
 * @param modifier Modifier to apply
 */
@Composable
fun ExtraKeysBar(
    config: ExtraKeysConfig = ExtraKeysConfig.DEFAULT,
    activeModifiers: Set<String> = emptySet(),
    onToggleModifier: (String) -> Unit = {},
    callback: ExtraKeysCallback,
    modifier: Modifier = Modifier
) {
    if (config.pages.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        val pagerState = rememberPagerState(pageCount = { config.pages.size })
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            ExtraKeysPage(
                rows = config.pages[page],
                activeModifiers = activeModifiers,
                onToggleModifier = onToggleModifier,
                callback = callback
            )
        }
        if (config.pages.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(config.pages.size) { index ->
                    val isCurrent = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(6.dp)
                            .background(
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

/**
 * Renders a single configuration page (a set of key rows) as the classic stacked layout.
 *
 * @param rows The key rows of the page
 * @param activeModifiers Sticky modifier keys currently active (e.g. "CTRL")
 * @param onToggleModifier Callback to toggle a sticky modifier key
 * @param callback Callback for key clicks
 * @param modifier Modifier to apply
 */
@Composable
private fun ExtraKeysPage(
    rows: List<List<ExtraKeyConfig>>,
    activeModifiers: Set<String>,
    onToggleModifier: (String) -> Unit,
    callback: ExtraKeysCallback,
    modifier: Modifier = Modifier
) {
    fun getModifierPrefix(): String {
        val prefix = StringBuilder()
        if (activeModifiers.contains("CTRL")) prefix.append("CTRL ")
        if (activeModifiers.contains("ALT")) prefix.append("ALT ")
        if (activeModifiers.contains("SHIFT")) prefix.append("SHIFT ")
        if (activeModifiers.contains("FN")) prefix.append("FN ")
        return prefix.toString()
    }

    fun onKeyAction(key: String) {
        val prefix = getModifierPrefix()
        val fullKey = if (prefix.isNotEmpty()) "$prefix$key" else key
        callback.onKeyClick(fullKey, prefix.isNotEmpty())
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth().then(
                    if (rowIndex > 0) Modifier.padding(top = 2.dp) else Modifier
                ),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { keyConfig ->
                    ExtraKeyButton(
                        config = keyConfig,
                        isActive = keyConfig.key in activeModifiers,
                        onClick = {
                            if (keyConfig.isModifier) {
                                onToggleModifier(keyConfig.key)
                            } else {
                                onKeyAction(keyConfig.key)
                            }
                        },
                        onLongPressRepeat = {
                            if (!keyConfig.isModifier) {
                                onKeyAction(keyConfig.key)
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * A single extra key button with long-press repeat support.
 *
 * @param config The key configuration
 * @param isActive Whether the button is in active state (for modifiers)
 * @param onClick Callback when the button is clicked
 * @param onLongPressRepeat Callback for each repeat during long press
 * @param modifier Modifier to apply
 */
@Composable
private fun ExtraKeyButton(
    config: ExtraKeyConfig,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongPressRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val displayFontSize = if (config.key in setOf("UP", "DOWN", "LEFT", "RIGHT")) 18.sp else 11.sp

    if (config.isRepetitive && !config.isModifier) {
        LongPressRepeatButton(
            onClick = onClick,
            onLongPressRepeat = onLongPressRepeat,
            modifier = modifier.height(36.dp),
            contentColor = contentColor
        ) {
            Text(
                text = config.display,
                fontSize = displayFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier.height(36.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = contentColor
            ),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = config.display,
                fontSize = displayFontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * A text-style button that triggers [onLongPressRepeat] at regular intervals while held down.
 *
 * A tap fires [onClick] once. Holding past the platform long-press timeout starts
 * repeating [onLongPressRepeat] every [REPEAT_DELAY] until the finger is released.
 * Uses a single [androidx.compose.foundation.combinedClickable] detector so taps are
 * not swallowed by competing gesture handlers. Ripple is drawn via [LocalIndication]
 * and the text keeps the flat "TextButton" look through a transparent [Surface].
 *
 * @param onClick Callback for a single tap
 * @param onLongPressRepeat Callback for each repeat tick while held down
 * @param modifier Modifier to apply
 * @param contentColor Text/icon color
 * @param content Button content
 */
@Composable
private fun LongPressRepeatButton(
    onClick: () -> Unit,
    onLongPressRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val latestRepeat by rememberUpdatedState(onLongPressRepeat)
    val latestClick by rememberUpdatedState(onClick)
    var isRepeating by remember { mutableStateOf(false) }

    LaunchedEffect(isRepeating, isPressed) {
        if (isRepeating && isPressed) {
            while (isPressed) {
                latestRepeat()
                delay(REPEAT_DELAY)
            }
            isRepeating = false
        }
    }

    Surface(
        modifier = modifier,
        color = Color.Transparent,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = { latestClick() },
                    onLongClick = { isRepeating = true },
                    interactionSource = interactionSource,
                    indication = LocalIndication.current
                ),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

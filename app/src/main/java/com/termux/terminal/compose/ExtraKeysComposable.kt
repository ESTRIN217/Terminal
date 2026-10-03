package com.termux.terminal.compose

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Repeat interval in milliseconds. */
private const val REPEAT_DELAY = 80L

/** Keys rendered with the larger arrow glyphs. */
private val ARROW_KEYS = setOf("UP", "DOWN", "LEFT", "RIGHT")

/**
 * Callback interface for extra key button clicks.
 */
fun interface ExtraKeysCallback {
    /**
     * Called when an extra key button is clicked.
     *
     * @param key The key identifier (e.g. "ESC", "CTRL", "TAB"), or the full macro sequence
     * when [isMacro] is true (e.g. "CTRL f d")
     * @param isMacro Whether [key] must be expanded as a space-separated key sequence
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
 * Supports long-press repeat for navigation and editing keys, and swipe up on a key
 * configured with a `popup` to send the popup key instead.
 *
 * @param config The extra keys configuration
 * @param activeModifiers Sticky modifier keys currently active (e.g. "CTRL")
 * @param onToggleModifier Callback to toggle a sticky modifier key
 * @param allCaps Whether button labels are uppercased ({@code extra-keys-text-all-caps})
 * @param callback Callback for key clicks
 * @param modifier Modifier to apply
 */
@Composable
fun ExtraKeysBar(
    config: ExtraKeysConfig = ExtraKeysConfig.EMPTY,
    activeModifiers: Set<String> = emptySet(),
    onToggleModifier: (String) -> Unit = {},
    allCaps: Boolean = true,
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
                allCaps = allCaps,
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
 * @param allCaps Whether button labels are uppercased
 * @param callback Callback for key clicks
 * @param modifier Modifier to apply
 */
@Composable
private fun ExtraKeysPage(
    rows: List<List<ExtraKeyConfig>>,
    activeModifiers: Set<String>,
    onToggleModifier: (String) -> Unit,
    allCaps: Boolean,
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

    fun onKeyAction(config: ExtraKeyConfig) {
        val prefix = getModifierPrefix()
        val fullKey = if (prefix.isEmpty()) config.key else prefix + config.key
        // A sticky modifier turns every press into a (single step) macro; the key's own macro
        // flag is preserved so a multi-key macro is always expanded.
        callback.onKeyClick(fullKey, prefix.isNotEmpty() || config.isMacro)
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
                        allCaps = allCaps,
                        onClick = {
                            if (keyConfig.isModifier) {
                                onToggleModifier(keyConfig.key)
                            } else {
                                onKeyAction(keyConfig)
                            }
                        },
                        onRepeat = {
                            if (!keyConfig.isModifier) {
                                onKeyAction(keyConfig)
                            }
                        },
                        onPopupClick = { popupConfig -> onKeyAction(popupConfig) },
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * A single extra key button with long-press repeat and swipe-up popup support.
 *
 * @param config The key configuration
 * @param isActive Whether the button is in active state (for modifiers)
 * @param allCaps Whether the label is uppercased
 * @param onClick Callback when the button is tapped
 * @param onRepeat Callback for each repeat while held down
 * @param onPopupClick Callback for the popup key when the button is swiped up and released
 * @param modifier Modifier to apply
 */
@Composable
private fun ExtraKeyButton(
    config: ExtraKeyConfig,
    isActive: Boolean,
    allCaps: Boolean,
    onClick: () -> Unit,
    onRepeat: () -> Unit,
    onPopupClick: (ExtraKeyConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = MaterialTheme.shapes.small
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    // Popup state: `shownPopup` is non-null while the popup key is previewed above the button,
    // which also suspends the auto repeat.
    val popupConfig = config.popup
    var shownPopup by remember { mutableStateOf<ExtraKeyConfig?>(null) }
    var isRepeating by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<Rect?>(null) }

    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnRepeat by rememberUpdatedState(onRepeat)
    val latestOnPopupClick by rememberUpdatedState(onPopupClick)

    LaunchedEffect(isRepeating, isPressed, shownPopup) {
        if (isRepeating && isPressed && shownPopup == null) {
            while (isPressed && shownPopup == null) {
                latestOnRepeat()
                delay(REPEAT_DELAY)
            }
            isRepeating = false
        }
    }

    // Swipe up on a key with a popup to preview it, swipe back down to cancel. The movement is
    // consumed so `combinedClickable` cancels both the tap and the long press, leaving this
    // detector as the only resolver of the press; this mirrors the touch handling of the
    // classic ExtraKeysView.
    val popupGesture = if (popupConfig == null) {
        Modifier
    } else {
        Modifier.pointerInput(popupConfig) {
            awaitEachGesture {
                val pointerId = awaitFirstDown(requireUnconsumed = false).id
                var offsetY = 0f
                var intercepted = false
                var showing = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: break
                    if (change.pressed) {
                        offsetY += change.positionChange().y
                        if (!showing && offsetY <= -touchSlopPx) {
                            showing = true
                            intercepted = true
                            isRepeating = false
                            shownPopup = popupConfig
                            change.consume()
                        } else if (showing && offsetY >= 0f) {
                            showing = false
                            intercepted = true
                            shownPopup = null
                            change.consume()
                        }
                    }
                    if (!change.pressed) {
                        if (intercepted) {
                            if (showing) latestOnPopupClick(shownPopup ?: popupConfig)
                            else latestOnClick()
                        }
                        shownPopup = null
                        break
                    }
                }
            }
        }
    }

    val anchorModifier = if (popupConfig == null) {
        Modifier
    } else {
        Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInWindow()
            anchor = Rect(
                offset = position,
                size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            )
        }
    }

    val buttonModifier = modifier
        .height(36.dp)
        .clip(shape)
        .then(anchorModifier)
        .then(popupGesture)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onLongClick = if (config.isRepetitive && !config.isModifier) {
                { isRepeating = true }
            } else {
                null
            },
            onClick = { latestOnClick() }
        )

    Surface(
        modifier = buttonModifier,
        color = Color.Transparent,
        contentColor = contentColor,
        shape = shape
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = buttonLabel(config, allCaps),
                fontSize = if (config.key in ARROW_KEYS) 18.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // Popup key preview, shown right above the button while it is swiped up.
    val popup = shownPopup
    val popupAnchor = anchor
    if (popup != null && popupAnchor != null) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(
                popupAnchor.left.toInt(),
                (popupAnchor.top - popupAnchor.height).toInt()
            ),
            properties = PopupProperties(focusable = false)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = shape
            ) {
                Box(
                    modifier = Modifier.size(
                        width = with(density) { popupAnchor.width.toDp() },
                        height = with(density) { popupAnchor.height.toDp() }
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = buttonLabel(popup, allCaps),
                        fontSize = if (popup.key in ARROW_KEYS) 18.sp else 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * The label shown on a button, honouring the {@code extra-keys-text-all-caps} property.
 *
 * @param config The key configuration
 * @param allCaps Whether the label is uppercased
 * @return The text to render
 */
private fun buttonLabel(config: ExtraKeyConfig, allCaps: Boolean): String =
    if (allCaps) config.display.uppercase() else config.display

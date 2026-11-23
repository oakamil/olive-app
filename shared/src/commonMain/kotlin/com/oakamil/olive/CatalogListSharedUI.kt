// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.AutoMirrored.Filled
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.Icons.Outlined
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource

val CATALOG_TYPE_OPTIONS = listOf(
    "Asterism",
    "Asteroid",
    "Cluster + Nebula",
    "Comet",
    "Dark Nebula",
    "Galaxy",
    "Globular Cluster",
    "Multiple Star",
    "Nebula",
    "Open Cluster",
    "Planet",
    "Planetary Nebula",
    "Star"
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DisableSystemKeyboard(
    disable: Boolean = true,
    content: @Composable () -> Unit
) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            if (!disable) {
                nextHandler.startInputMethod(request)
            } else {
                awaitCancellation()
            }
        },
        content = content
    )
}

fun TextFieldValue.insertTextAtCursor(insert: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val updated = buildString(text.length - (end - start) + insert.length) {
        append(text, 0, start)
        append(insert)
        append(text, end, text.length)
    }
    val cursor = start + insert.length
    return TextFieldValue(text = updated, selection = TextRange(cursor))
}

fun TextFieldValue.backspaceAtCursor(): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    if (start != end) {
        val updated = text.removeRange(start, end)
        return TextFieldValue(text = updated, selection = TextRange(start))
    }
    if (start <= 0) return this
    val updated = text.removeRange(start - 1, start)
    return TextFieldValue(text = updated, selection = TextRange(start - 1))
}

@Composable
private fun isLandscapeWindowLayout(): Boolean {
    val windowSize = LocalWindowInfo.current.containerSize
    if (windowSize.width <= 0 || windowSize.height <= 0) return false
    val widthDp = with(LocalDensity.current) { windowSize.width.toDp() }
    return windowSize.width > windowSize.height && widthDp >= 480.dp
}

private fun landscapeKeyboardWidth(maxWidth: Dp): Dp =
    (maxWidth * 0.5f).coerceAtMost(480.dp)

@Composable
fun rememberAutoFocusRequester(enabled: Boolean = true): FocusRequester {
    val focusRequester = remember { FocusRequester() }
    var hasRequested by remember { mutableStateOf(false) }
    if (enabled && !hasRequested) {
        LaunchedEffect(Unit) {
            withFrameNanos {}
            @Suppress("AssignedValueIsNeverRead")
            hasRequested = true
            runCatching { focusRequester.requestFocus() }
        }
    }
    return focusRequester
}

@Composable
fun rememberSyncedTextFieldValue(text: String): MutableState<TextFieldValue> {
    val state = remember {
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    LaunchedEffect(text) {
        if (state.value.text != text) {
            state.value = TextFieldValue(text, TextRange(text.length))
        }
    }
    return state
}

@Composable
fun NightVisionSearchBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val focusRequester = rememberAutoFocusRequester(enabled = autoFocus)
    DisableSystemKeyboard(disable = true) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        onFocus()
                    }
                },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Default.Search, contentDescription = null) },
            trailingIcon = if (value.text.isNotEmpty()) {
                {
                    IconButton(onClick = { onValueChange(TextFieldValue("")) }) {
                        Icon(
                            Default.Close,
                            contentDescription = stringResource(Res.string.cd_clear_search),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true
        )
    }
}

@Composable
fun ColumnScope.NightVisionKeyboardSplitLayout(
    showKeyboard: Boolean,
    landscapeKeyboardOnRight: Boolean,
    content: @Composable (Modifier) -> Unit,
    keyboard: @Composable (isLandscape: Boolean, Modifier) -> Unit
) {
    val isLandscape = isLandscapeWindowLayout()
    val currentContent by rememberUpdatedState(content)
    val currentKeyboard by rememberUpdatedState(keyboard)
    val movableContent = remember {
        movableContentOf<Modifier> { mod -> currentContent(mod) }
    }
    val movableKeyboard = remember {
        movableContentOf<Boolean, Modifier> { landscape, mod ->
            currentKeyboard(landscape, mod)
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
    ) {
        if (isLandscape && showKeyboard) {
            val keyboardSlotWidth = landscapeKeyboardWidth(maxWidth)
            val keyboardSlot: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .width(keyboardSlotWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    movableKeyboard(true, Modifier.fillMaxWidth())
                }
            }
            Row(modifier = Modifier.fillMaxSize()) {
                if (landscapeKeyboardOnRight) {
                    movableContent(Modifier.weight(1f).fillMaxHeight())
                    keyboardSlot()
                } else {
                    keyboardSlot()
                    movableContent(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                movableContent(Modifier.weight(1f).fillMaxWidth())
                if (showKeyboard) {
                    movableKeyboard(false, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun NightVisionKeyboardDialogLayout(
    showKeyboard: Boolean,
    landscapeKeyboardOnRight: Boolean,
    maxCardWidth: Dp,
    onScrimClick: () -> Unit,
    fillCardHeightInLandscape: Boolean = false,
    cardContent: @Composable (Modifier) -> Unit,
    keyboardPanel: @Composable (isLandscape: Boolean, Modifier) -> Unit
) {
    val isLandscape = isLandscapeWindowLayout()
    val onUserInteraction = LocalOnUserInteraction.current
    val currentOnScrimClick by rememberUpdatedState(onScrimClick)
    val currentCardContent by rememberUpdatedState(cardContent)
    val currentKeyboardPanel by rememberUpdatedState(keyboardPanel)
    val movableCard = remember {
        movableContentOf<Modifier> { mod -> currentCardContent(mod) }
    }
    val movableKeyboard = remember {
        movableContentOf<Boolean, Modifier> { landscape, mod ->
            currentKeyboardPanel(landscape, mod)
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .notifyUserInteraction(onUserInteraction)
            .pointerInput(Unit) {
                detectTapGestures { currentOnScrimClick() }
            }
            .platformDialogSafeAreaPadding()
    ) {
        if (showKeyboard && isLandscape) {
            val keyboardSlotWidth = landscapeKeyboardWidth(maxWidth)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val cardSlot: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val cardMod = if (fillCardHeightInLandscape) {
                            Modifier.widthIn(max = maxCardWidth).fillMaxSize()
                        } else {
                            Modifier.widthIn(max = maxCardWidth).fillMaxWidth()
                        }
                        movableCard(cardMod)
                    }
                }
                val keyboardSlot: @Composable () -> Unit = {
                    Box(
                        modifier = Modifier
                            .width(keyboardSlotWidth)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        movableKeyboard(true, Modifier.fillMaxWidth())
                    }
                }
                if (landscapeKeyboardOnRight) {
                    cardSlot()
                    keyboardSlot()
                } else {
                    keyboardSlot()
                    cardSlot()
                }
            }
        } else if (showKeyboard) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    movableCard(Modifier.widthIn(max = maxCardWidth).fillMaxWidth())
                }
                movableKeyboard(false, Modifier.fillMaxWidth())
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                movableCard(Modifier.widthIn(max = maxCardWidth).fillMaxWidth())
            }
        }
    }
}

@Composable
fun NightVisionKeyboardDock(
    isLandscape: Boolean,
    viewModel: MainViewModel,
    onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onClose: () -> Unit,
    showShiftAndPunctuation: Boolean = false,
    showNewline: Boolean = false,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val handleClose = {
        focusManager.clearFocus()
        onClose()
    }
    val onKeyClick: (Char) -> Unit = { ch ->
        onEdit { it.insertTextAtCursor(ch.toString()) }
    }
    val onSpace: () -> Unit = {
        onEdit { it.insertTextAtCursor(" ") }
    }
    val onBackspace: () -> Unit = {
        onEdit { it.backspaceAtCursor() }
    }
    val landscapeKeyboardOnRight = viewModel.landscapeKeyboardOnRight
    val dockModifier = modifier
        .background(MaterialTheme.colorScheme.surface)
        .pointerInput(Unit) {
            detectTapGestures { }
        }

    if (isLandscape) {
        val maxDockHeight = if (showShiftAndPunctuation) 228.dp else 184.dp
        val sideButtons = @Composable {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = handleClose,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        Default.Close,
                        contentDescription = stringResource(Res.string.cd_close_keyboard),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        viewModel.landscapeKeyboardOnRight = !viewModel.landscapeKeyboardOnRight
                    },
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = if (landscapeKeyboardOnRight) Filled.KeyboardArrowLeft else Filled.KeyboardArrowRight,
                        contentDescription = stringResource(Res.string.cd_swap_keyboard_side),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Row(
            modifier = dockModifier
                .heightIn(max = maxDockHeight)
                .fillMaxHeight()
        ) {
            if (landscapeKeyboardOnRight) {
                sideButtons()
            }
            NightVisionKeyboard(
                isLandscape = true,
                showShiftAndPunctuation = showShiftAndPunctuation,
                showNewline = showNewline,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                onKeyClick = onKeyClick,
                onSpace = onSpace,
                onBackspace = onBackspace
            )
            if (!landscapeKeyboardOnRight) {
                sideButtons()
            }
        }
    } else {
        Column(modifier = dockModifier) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = handleClose) {
                    Icon(
                        Default.Close,
                        contentDescription = stringResource(Res.string.cd_close_keyboard),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            NightVisionKeyboard(
                isLandscape = false,
                showShiftAndPunctuation = showShiftAndPunctuation,
                showNewline = showNewline,
                modifier = Modifier.fillMaxWidth(),
                onKeyClick = onKeyClick,
                onSpace = onSpace,
                onBackspace = onBackspace
            )
        }
    }
}

@Composable
fun NightVisionKeyboard(
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    showShiftAndPunctuation: Boolean = false,
    showNewline: Boolean = false,
    onKeyClick: (Char) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit
) {
    var isShifted by remember { mutableStateOf(false) }

    val letterRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        if (showShiftAndPunctuation) {
            listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
        } else {
            listOf("␣", "z", "x", "c", "v", "b", "n", "m", "⌫")
        }
    )

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .pointerInput(Unit) {
                detectTapGestures { }
            }
    ) {
        val rowModifier = if (isLandscape) {
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .weight(1f)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        }
        letterRows.forEach { row ->
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.Center
            ) {
                val paddingWeight = (10 - row.size) / 2f
                if (paddingWeight > 0f) {
                    Spacer(modifier = Modifier.weight(paddingWeight))
                }

                row.forEach { rawKey ->
                    val displayKey = if (isShifted && rawKey.length == 1 && rawKey[0] in 'a'..'z') {
                        rawKey.uppercase()
                    } else {
                        rawKey
                    }
                    NightVisionKey(
                        text = displayKey,
                        isLandscape = isLandscape,
                        highlighted = rawKey == "⇧" && isShifted,
                        onClick = {
                            when (rawKey) {
                                "⇧" -> isShifted = !isShifted
                                "␣" -> onSpace()
                                "⌫" -> onBackspace()
                                else -> {
                                    val ch = displayKey[0]
                                    onKeyClick(ch)
                                    if (isShifted && rawKey[0] in 'a'..'z') {
                                        isShifted = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                    )
                }

                if (paddingWeight > 0f) {
                    Spacer(modifier = Modifier.weight(paddingWeight))
                }
            }
        }

        if (showShiftAndPunctuation) {
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.Center
            ) {
                NightVisionKey(
                    text = ",",
                    isLandscape = isLandscape,
                    onClick = { onKeyClick(',') },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                )
                NightVisionKey(
                    text = "-",
                    isLandscape = isLandscape,
                    onClick = { onKeyClick('-') },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                )
                NightVisionKey(
                    text = "␣",
                    isLandscape = isLandscape,
                    onClick = onSpace,
                    modifier = Modifier
                        .weight(3.5f)
                        .padding(horizontal = 2.dp)
                )
                NightVisionKey(
                    text = ".",
                    isLandscape = isLandscape,
                    onClick = { onKeyClick('.') },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 2.dp)
                )
                NightVisionKey(
                    text = if (showNewline) "↵" else "/",
                    isLandscape = isLandscape,
                    onClick = {
                        onKeyClick(if (showNewline) '\n' else '/')
                    },
                    modifier = Modifier
                        .weight(1.2f)
                        .padding(horizontal = 2.dp)
                )
            }
        }
    }
}

@Composable
fun NightVisionKey(
    modifier: Modifier = Modifier,
    text: String,
    isLandscape: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressedState by interactionSource.collectIsPressedAsState()

    val isActive = isPressedState || highlighted
    val bgColor =
        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val textColor =
        if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    val boxModifier = if (isLandscape) modifier.fillMaxHeight() else modifier.height(36.dp)
    Box(
        modifier = boxModifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .background(bgColor, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = textColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HqBulkDownloadHeader(
    isDownloading: Boolean,
    hqState: HiPSHqDownloadState,
    missingKeys: IntArray?,
    onShowProgress: () -> Unit,
    onRequestDownload: (IntArray) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isDownloading && hqState is HiPSHqDownloadState.Downloading) {
            val pct = ((hqState.completedTiles * 100) /
                    hqState.totalTiles.coerceAtLeast(1)).coerceIn(0, 100)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.hips_downloading_inline, pct),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clickable(onClick = onShowProgress)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.hips_action_show),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else if (missingKeys != null && missingKeys.isNotEmpty()) {
            val estMb = HiPSDownloadManager.formatMbValue(
                HiPSDownloadManager.estimatedTilesBytes(missingKeys)
            )
            OutlinedButton(
                onClick = { onRequestDownload(missingKeys) },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(stringResource(Res.string.btn_download_catalog_images, estMb))
            }
        }
    }
}

@Composable
fun FilterSettingsIcon(
    hasActiveFilters: Boolean,
    contentDescription: String
) {
    Box {
        Icon(
            imageVector = Default.Settings,
            contentDescription = contentDescription
        )
        if (hasActiveFilters) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
fun CatalogFilterSortDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    sortOptions: List<Pair<String, StringResource>>,
    sortBy: String,
    onSortByChange: (String) -> Unit,
    visibilityFilter: String,
    onVisibilityFilterChange: (String) -> Unit,
    magLimit: Double? = null,
    onMagLimitChange: ((Double?) -> Unit)? = null,
    typeFilters: Set<String>? = null,
    onTypeFiltersChange: ((Set<String>) -> Unit)? = null,
    observedFilter: String? = null,
    onObservedFilterChange: ((String) -> Unit)? = null
) {
    val extColors = LocalOliveExtendedColors.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        Text(
            text = stringResource(Res.string.filter_sort_by),
            modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        sortOptions.forEach { (key, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                onClick = { onSortByChange(key) },
                trailingIcon = {
                    if (sortBy == key) {
                        Icon(
                            Default.RadioButtonChecked,
                            contentDescription = stringResource(Res.string.cd_selected),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
        HorizontalDivider()
        Text(
            text = stringResource(Res.string.filter_visibility),
            modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        val visibilityOptions = listOf(
            "Above Horizon" to Res.string.filter_visibility_above_horizon,
            "All Objects" to Res.string.cat_all_objects
        )
        visibilityOptions.forEach { (key, labelRes) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                onClick = { onVisibilityFilterChange(key) },
                trailingIcon = {
                    if (visibilityFilter == key) {
                        Icon(
                            Default.RadioButtonChecked,
                            contentDescription = stringResource(Res.string.cd_selected),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
        if (observedFilter != null && onObservedFilterChange != null) {
            HorizontalDivider()
            Text(
                text = stringResource(Res.string.filter_observed),
                modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val observedOptions = listOf(
                "All" to Res.string.filter_observed_all,
                "Unobserved" to Res.string.filter_observed_unobserved,
                "Observed" to Res.string.filter_observed_only
            )
            observedOptions.forEach { (key, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onObservedFilterChange(key) },
                    trailingIcon = {
                        if (observedFilter == key) {
                            Icon(
                                Default.RadioButtonChecked,
                                contentDescription = stringResource(Res.string.cd_selected),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }
        }
        if (onMagLimitChange != null) {
            HorizontalDivider()
            Text(
                text = stringResource(Res.string.filter_magnitude_limit),
                modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val magOptions = listOf(
                Res.string.mag_no_limit to null,
                Res.string.mag_naked_eye to 6.0,
                Res.string.mag_binoculars to 9.0,
                Res.string.mag_small_scope to 12.0,
                Res.string.mag_large_scope to 15.0
            )
            magOptions.forEach { (labelRes, value) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = { onMagLimitChange(value) },
                    trailingIcon = {
                        if (magLimit == value) {
                            Icon(
                                Default.RadioButtonChecked,
                                contentDescription = stringResource(Res.string.cd_selected),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }
        }
        if (typeFilters != null && onTypeFiltersChange != null) {
            HorizontalDivider()
            Text(
                text = stringResource(Res.string.filter_object_type),
                modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            val allTypesSelected = typeFilters.size >= CATALOG_TYPE_OPTIONS.size
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.type_all_types)) },
                leadingIcon = {
                    Icon(
                        CustomIcons.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    onTypeFiltersChange(
                        if (allTypesSelected) emptySet() else CATALOG_TYPE_OPTIONS.toSet()
                    )
                },
                trailingIcon = {
                    if (allTypesSelected) {
                        Icon(
                            Default.Check,
                            contentDescription = stringResource(Res.string.cd_selected),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
            CATALOG_TYPE_OPTIONS.forEach { type ->
                val isSelected = type in typeFilters
                DropdownMenuItem(
                    leadingIcon = {
                        val (iVec, iCol) = getIconAndColorForType(type, extColors)
                        Icon(iVec, contentDescription = null, tint = iCol)
                    },
                    text = { Text(getLocalizedObjectType(type)) },
                    onClick = {
                        onTypeFiltersChange(
                            if (isSelected) typeFilters - type else typeFilters + type
                        )
                    },
                    trailingIcon = {
                        if (isSelected) {
                            Icon(
                                Default.Check,
                                contentDescription = stringResource(Res.string.cd_selected),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun CatalogObjectListRow(
    obj: CatalogObject,
    isBelowHorizon: Boolean,
    isEngineRunning: Boolean,
    isObserved: Boolean,
    telescopeAltAz: AltAz?,
    currentRa: Double?,
    currentDec: Double?,
    lat: Double?,
    lon: Double?,
    currentTimeMs: Long,
    onClick: () -> Unit,
    onCenter: () -> Unit,
    onGoTo: () -> Unit,
    modifier: Modifier = Modifier,
    extraSubtext: String? = null,
    isEditMode: Boolean = false,
    showDragHandle: Boolean = true,
    dragHandleModifier: Modifier = Modifier,
    onRemoveClick: (() -> Unit)? = null
) {
    val extColors = LocalOliveExtendedColors.current
    ListItem(
        modifier = modifier.clickable(enabled = !isEditMode, onClick = onClick),
        colors = if (isBelowHorizon) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = if (isEditMode && showDragHandle) {
            {
                Icon(
                    imageVector = Default.DragHandle,
                    contentDescription = stringResource(Res.string.cd_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .size(24.dp)
                )
            }
        } else {
            null
        },
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = obj.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isObserved) {
                    Icon(
                        imageVector = Default.CheckCircle,
                        contentDescription = stringResource(Res.string.cd_observed),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        },
        supportingContent = {
            val localizedType = getLocalizedObjectType(obj.objectType)
            val magText = when {
                obj.magnitude != null -> stringResource(
                    Res.string.format_mag,
                    String.format("%.1f", obj.magnitude)
                )

                obj.catalogLabel == "Planet" || obj.catalogLabel == UserDatabase.CUSTOM_CATALOG_LABEL -> ""
                else -> stringResource(
                    Res.string.format_mag,
                    stringResource(Res.string.value_not_available)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (iVec, iCol) = obj.getIconAndColor(extColors)
                        Icon(
                            imageVector = iVec,
                            contentDescription = null,
                            tint = iCol,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 4.dp)
                        )
                        Text(
                            text = localizedType,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (obj.designation.isNotBlank() && obj.displayName != obj.designation &&
                        obj.catalogLabel != UserDatabase.CUSTOM_CATALOG_LABEL
                    ) {
                        Text(
                            text = obj.designation,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (magText.isNotBlank()) {
                        Text(
                            text = magText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!extraSubtext.isNullOrBlank()) {
                    Text(
                        text = extraSubtext,
                        style = MaterialTheme.typography.bodySmall,
                        color = extColors.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        trailingContent = {
            if (isEditMode) {
                if (onRemoveClick != null) {
                    IconButton(onClick = onRemoveClick) {
                        Icon(
                            imageVector = Outlined.Delete,
                            contentDescription = stringResource(Res.string.cd_delete),
                            tint = extColors.ctaStop
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isBelowHorizon) {
                        Text(
                            stringResource(Res.string.status_below_horizon),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp, start = 4.dp)
                        )
                    } else {
                        val hasSolve =
                            isEngineRunning && telescopeAltAz != null &&
                                    currentRa != null && currentDec != null &&
                                    lat != null && lon != null
                        if (hasSolve) {
                            val objAltAz = AstrometryUtils.calculateAltAz(
                                obj.ra,
                                obj.dec,
                                lat,
                                lon,
                                currentTimeMs
                            )
                            val distance = AstrometryUtils.angularDistance(
                                currentRa,
                                currentDec,
                                obj.ra,
                                obj.dec
                            )
                            val bearing = targetBearingAltAz(
                                telescopeAltAz.az,
                                telescopeAltAz.alt,
                                objAltAz.az,
                                objAltAz.alt
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(
                                    start = 4.dp,
                                    top = 4.dp,
                                    end = 12.dp,
                                    bottom = 4.dp
                                )
                            ) {
                                Icon(
                                    imageVector = Default.ArrowUpward,
                                    contentDescription = stringResource(Res.string.cd_direction),
                                    modifier = Modifier
                                        .rotate(bearing.toFloat())
                                        .size(24.dp)
                                )
                                Text(
                                    text = stringResource(
                                        Res.string.format_degree,
                                        String.format("%.1f", distance)
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    IconBarItem(
                        icon = Default.CenterFocusStrong,
                        label = stringResource(Res.string.crop_center),
                        onClick = onCenter
                    )

                    if (!isBelowHorizon && isEngineRunning) {
                        IconBarItem(
                            icon = Default.GpsFixed,
                            label = stringResource(Res.string.btn_goto),
                            onClick = onGoTo
                        )
                    }
                }
            }
        }
    )
}

@Stable
class DragReorderState(
    val listState: LazyListState,
    private val haptic: HapticFeedback,
    private val onMoveKeys: (fromKey: Any, toKey: Any) -> Boolean,
    private val onDragEnd: () -> Unit
) {
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    var draggedOffsetY by mutableFloatStateOf(0f)
        private set
    var autoScrollDelta by mutableFloatStateOf(0f)
        private set

    fun startDrag(itemKey: Any) {
        draggedKey = itemKey
        draggedOffsetY = 0f
        autoScrollDelta = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun onDragBy(deltaY: Float) {
        val key = draggedKey ?: return
        draggedOffsetY += deltaY

        val layoutInfo = listState.layoutInfo
        val currentInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val draggedCenter = currentInfo.offset + draggedOffsetY + currentInfo.size / 2f

        val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key != key &&
                    draggedCenter in other.offset.toFloat()..(other.offset + other.size).toFloat()
        }
        if (targetInfo != null && onMoveKeys(key, targetInfo.key)) {
            val offsetDiff = targetInfo.offset - currentInfo.offset
            draggedOffsetY -= offsetDiff
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }

        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat()
        val edgeZone = 96f
        val topEdge = currentInfo.offset + draggedOffsetY
        val bottomEdge = topEdge + currentInfo.size
        autoScrollDelta = when {
            topEdge < viewportStart + edgeZone -> -18f
            bottomEdge > viewportEnd - edgeZone -> 18f
            else -> 0f
        }
    }

    fun endDrag() {
        val hadDrag = draggedKey != null
        draggedKey = null
        draggedOffsetY = 0f
        autoScrollDelta = 0f
        if (hadDrag) {
            onDragEnd()
        }
    }
}

@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    onMoveKeys: (fromKey: Any, toKey: Any) -> Boolean,
    onDragEnd: () -> Unit
): DragReorderState {
    val haptic = LocalHapticFeedback.current
    val latestOnMove by rememberUpdatedState(onMoveKeys)
    val latestOnEnd by rememberUpdatedState(onDragEnd)
    val state = remember(listState, haptic) {
        DragReorderState(
            listState = listState,
            haptic = haptic,
            onMoveKeys = { from, to -> latestOnMove(from, to) },
            onDragEnd = { latestOnEnd() }
        )
    }
    LaunchedEffect(state.draggedKey, state.autoScrollDelta) {
        while (state.draggedKey != null && state.autoScrollDelta != 0f) {
            val scrolled = state.listState.scrollBy(state.autoScrollDelta)
            if (scrolled != 0f) {
                state.onDragBy(scrolled)
            }
            delay(16L)
        }
    }
    return state
}

fun Modifier.dragReorderItem(
    state: DragReorderState,
    itemKey: Any,
    enableLongPressDrag: Boolean
): Modifier {
    val isDragging = state.draggedKey == itemKey
    val visualModifier = if (isDragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.draggedOffsetY
                scaleX = 1.02f
                scaleY = 1.02f
            }
    } else {
        Modifier
    }
    val gestureModifier = if (enableLongPressDrag) {
        Modifier.pointerInput(itemKey, state) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.startDrag(itemKey) },
                onDragEnd = { state.endDrag() },
                onDragCancel = { state.endDrag() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    state.onDragBy(dragAmount.y)
                }
            )
        }
    } else {
        Modifier
    }
    return this
        .then(visualModifier)
        .then(gestureModifier)
}

fun Modifier.dragReorderHandle(
    state: DragReorderState,
    itemKey: Any
): Modifier = this.pointerInput(itemKey, state) {
    detectDragGestures(
        onDragStart = { state.startDrag(itemKey) },
        onDragEnd = { state.endDrag() },
        onDragCancel = { state.endDrag() },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDragBy(dragAmount.y)
        }
    )
}

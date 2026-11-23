// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.Icons.Outlined
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@Composable
fun ListNameDialog(
    title: String,
    initialName: String,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val focusManager = LocalFocusManager.current
    val voiceController = rememberVoiceDictationController()

    var nameValue by remember(initialName) {
        mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length)))
    }
    var speechBaseText by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(true) }
    val hideKeyboard = {
        showKeyboard = false
        focusManager.clearFocus()
    }
    val handleDismissOrHideKeyboard = {
        voiceController.stopListening()
        if (showKeyboard) {
            hideKeyboard()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = handleDismissOrHideKeyboard,
        properties = fullScreenDialogProperties(dismissOnClickOutside = false)
    ) {
        val dialogShape = RoundedCornerShape(16.dp)

        val cardContent: @Composable (Modifier) -> Unit = { cardModifier ->
            val focusRequester = rememberAutoFocusRequester()
            Surface(
                shape = dialogShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = cardModifier
                    .border(1.dp, extColors.subtleBorder, dialogShape)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OliveDialogHeader(
                        title = title,
                        onDismiss = {
                            voiceController.stopListening()
                            onDismiss()
                        }
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DisableSystemKeyboard(disable = true) {
                            OutlinedTextField(
                                value = nameValue,
                                onValueChange = { nameValue = it },
                                label = { Text(stringResource(Res.string.label_list_name)) },
                                placeholder = { Text(stringResource(Res.string.placeholder_list_name)) },
                                singleLine = true,
                                trailingIcon = if (voiceController.isSupported) {
                                    {
                                        IconButton(
                                            onClick = {
                                                hideKeyboard()
                                                if (!voiceController.isListening) {
                                                    speechBaseText = nameValue.text
                                                }
                                                voiceController.toggleListening { sessionText, _ ->
                                                    val combined = appendDictationText(
                                                        speechBaseText,
                                                        sessionText
                                                    )
                                                    nameValue = TextFieldValue(
                                                        combined,
                                                        TextRange(combined.length)
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (voiceController.isListening) {
                                                    Outlined.MicOff
                                                } else {
                                                    Outlined.Mic
                                                },
                                                contentDescription = stringResource(
                                                    if (voiceController.isListening) Res.string.cd_stop_voice_input
                                                    else Res.string.cd_voice_input
                                                ),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { state ->
                                        if (state.isFocused) {
                                            voiceController.stopListening()
                                            showKeyboard = true
                                        }
                                    }
                            )
                        }

                        if (voiceController.isListening) {
                            Text(
                                text = stringResource(Res.string.status_listening),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    voiceController.stopListening()
                                    onDismiss()
                                }
                            ) {
                                Text(
                                    text = stringResource(Res.string.btn_cancel),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                enabled = nameValue.text.trim().isNotEmpty(),
                                onClick = {
                                    voiceController.stopListening()
                                    onConfirm(nameValue.text.trim())
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(stringResource(Res.string.btn_save))
                            }
                        }
                    }
                }
            }
        }

        val keyboardPanel: @Composable (Boolean, Modifier) -> Unit = { isLandscape, kbModifier ->
            NightVisionKeyboardDock(
                isLandscape = isLandscape,
                viewModel = viewModel,
                onEdit = { transform -> nameValue = transform(nameValue) },
                onClose = hideKeyboard,
                showShiftAndPunctuation = true,
                modifier = kbModifier
            )
        }

        NightVisionKeyboardDialogLayout(
            showKeyboard = showKeyboard,
            landscapeKeyboardOnRight = viewModel.landscapeKeyboardOnRight,
            maxCardWidth = 440.dp,
            onScrimClick = handleDismissOrHideKeyboard,
            cardContent = cardContent,
            keyboardPanel = keyboardPanel
        )
    }

    VoicePermissionDeniedDialog(voiceController)
}

@Composable
fun ObservingListPickerDialog(
    lists: List<ObservingListSummary>,
    memberListIds: Set<Long>,
    lastSelectedListId: Long,
    onToggleList: (listId: Long, currentlyMember: Boolean) -> Unit,
    onCreateNewList: () -> Unit,
    onDismiss: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val pinnedListId = remember { lastSelectedListId }
    val orderedLists = remember(lists, pinnedListId) {
        val pinned = lists.firstOrNull { it.id == pinnedListId }
        if (pinned != null) listOf(pinned) + lists.filterNot { it.id == pinned.id } else lists
    }
    InfoPopup(
        title = stringResource(Res.string.title_observing_lists),
        onDismiss = onDismiss,
        fillMaxWidth = true,
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCreateNewList) {
                    Icon(Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.btn_new_list),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(Res.string.btn_done),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            orderedLists.forEach { list ->
                val isMember = list.id in memberListIds
                val isPinned = list.id == pinnedListId
                val rowShape = RoundedCornerShape(8.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPinned) {
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    rowShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onToggleList(list.id, isMember) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isMember,
                        onCheckedChange = { onToggleList(list.id, isMember) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isPinned) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isPinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${list.itemCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = extColors.secondaryText
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddObjectsToListDialog(
    list: ObservingListSummary,
    existingKeys: Set<String>,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    var queryValue by remember { mutableStateOf(TextFieldValue("")) }
    val searchQuery = queryValue.text
    var showKeyboard by remember { mutableStateOf(true) }
    val hideKeyboard = {
        showKeyboard = false
        focusManager.clearFocus()
    }
    var pendingOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(existingKeys) {
        if (pendingOverrides.isNotEmpty()) {
            pendingOverrides = pendingOverrides.filter { (key, expected) ->
                (key in existingKeys) != expected
            }
        }
    }
    var results by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var previewObj by remember { mutableStateOf<CatalogObject?>(null) }

    LaunchedEffect(searchQuery, viewModel.cometsRefreshVersion) {
        isLoading = true
        val query = searchQuery.trim()
        val lat = viewModel.currentLat
        val lon = viewModel.currentLon
        results = withContext(Dispatchers.IO) {
            val dynamicMatches = buildList {
                val allDynamic =
                    viewModel.getPlanets(lat, lon) +
                            viewModel.getAsteroids(lat, lon) +
                            viewModel.getComets(lat, lon)
                for (obj in allDynamic) {
                    if (query.isEmpty() ||
                        obj.displayName.contains(query, ignoreCase = true) ||
                        obj.designation.contains(query, ignoreCase = true)
                    ) {
                        add(obj)
                    }
                }
            }
            val sqlMatches = viewModel.catalogDb.searchObjects(
                searchQuery = query,
                catalogFilter = null,
                sortBy = "catalog ID",
                visibilityFilter = "All Objects",
                limit = 200,
                offset = 0
            )
            if (query.isEmpty()) {
                (dynamicMatches.take(12) + sqlMatches)
            } else {
                dynamicMatches + sqlMatches
            }
        }
        isLoading = false
    }

    BackHandler {
        if (showKeyboard) {
            hideKeyboard()
        } else {
            onDismiss()
        }
    }

    FullScreenSurfaceDialog(
        onDismissRequest = {
            if (showKeyboard) {
                hideKeyboard()
            } else {
                onDismiss()
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.title_add_to_list, list.name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(Res.string.btn_done),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            NightVisionSearchBar(
                value = queryValue,
                onValueChange = { queryValue = it },
                placeholder = stringResource(Res.string.hint_search_catalog_to_add),
                onFocus = { showKeyboard = true },
                autoFocus = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )

            val resultsContent: @Composable (Modifier) -> Unit = { contentModifier ->
                LazyColumn(modifier = contentModifier.fillMaxSize()) {
                    items(
                        items = results,
                        key = { "${it.catalogLabel}_${it.id}_${it.catalogEntry}" }
                    ) { obj ->
                        val key = UserDatabase.objectKey(obj.catalogLabel, obj.catalogEntry)
                        val isAdded = pendingOverrides[key] ?: (key in existingKeys)
                        val toggleMembership = {
                            val targetAdded = !isAdded
                            val ref = UserDatabase.targetRefFrom(
                                catalogObject = obj,
                                coords = obj.ra to obj.dec,
                                fallbackTitle = obj.displayName
                            )
                            pendingOverrides = pendingOverrides + (key to targetAdded)
                            coroutineScope.launch {
                                if (targetAdded) {
                                    viewModel.addObjectToList(list.id, ref)
                                } else {
                                    viewModel.removeObjectFromList(
                                        list.id,
                                        obj.catalogLabel,
                                        obj.catalogEntry
                                    )
                                }
                            }
                            Unit
                        }

                        ListItem(
                            modifier = Modifier.clickable(onClick = toggleMembership),
                            headlineContent = {
                                Text(
                                    text = obj.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                val localizedType = getLocalizedObjectType(obj.objectType)
                                val details = buildList {
                                    add(localizedType)
                                    if (obj.designation.isNotBlank() && obj.displayName != obj.designation) {
                                        add(obj.designation)
                                    }
                                    if (obj.magnitude != null) {
                                        add(
                                            stringResource(
                                                Res.string.format_mag,
                                                String.format("%.1f", obj.magnitude)
                                            )
                                        )
                                    }
                                }.joinToString(" · ")
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
                                        text = details,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { previewObj = obj }) {
                                        Icon(
                                            imageVector = Default.Info,
                                            contentDescription = stringResource(Res.string.cd_info),
                                            tint = extColors.secondaryText
                                        )
                                    }
                                    IconButton(onClick = toggleMembership) {
                                        Icon(
                                            imageVector = if (isAdded) Default.Check else Default.Add,
                                            contentDescription = stringResource(
                                                if (isAdded) Res.string.cd_selected
                                                else Res.string.btn_add_objects
                                            ),
                                            tint = if (isAdded) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }

                    if (results.isEmpty()) {
                        item(key = "empty_search") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator()
                                } else {
                                    Text(stringResource(Res.string.status_no_objects))
                                }
                            }
                        }
                    }
                }
            }

            val keyboardPanel: @Composable (Boolean, Modifier) -> Unit =
                { isLandscape, kbModifier ->
                    NightVisionKeyboardDock(
                        isLandscape = isLandscape,
                        viewModel = viewModel,
                        onEdit = { transform -> queryValue = transform(queryValue) },
                        onClose = hideKeyboard,
                        modifier = kbModifier
                    )
                }

            NightVisionKeyboardSplitLayout(
                showKeyboard = showKeyboard,
                landscapeKeyboardOnRight = viewModel.landscapeKeyboardOnRight,
                content = resultsContent,
                keyboard = keyboardPanel
            )
        }
    }

    val preview = previewObj
    if (preview != null) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val lat = viewModel.currentLat
        val lon = viewModel.currentLon
        val altAz = if (viewModel.hasValidLocation && lat != null && lon != null) {
            AstrometryUtils.calculateAltAz(preview.ra, preview.dec, lat, lon, nowMs)
        } else {
            null
        }
        ObjectInfoPopup(
            coords = preview.ra to preview.dec,
            catalogObject = preview,
            viewModel = viewModel,
            timeMs = nowMs,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                previewObj = null
            },
            altAz = altAz,
            onCloseToMap = {
                @Suppress("AssignedValueIsNeverRead")
                previewObj = null
                onDismiss()
            }
        )
    }
}

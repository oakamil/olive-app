// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.Icons.Outlined
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.StringResource
import kotlin.math.cos
import kotlin.math.sin

private sealed interface SelectedObservingView {
    data class Manual(val listId: Long) : SelectedObservingView
    data class NightSession(val observingNight: String) : SelectedObservingView
}

private data class PendingUndoRemoval(
    val listId: Long,
    val target: TargetRef
)

private sealed interface ImportBanner {
    data class SingleList(val objectsAdded: Int) : ImportBanner
    data class FullLists(val listsTouched: Int, val objectsAdded: Int) : ImportBanner
    data class Observations(val added: Int, val duplicatesSkipped: Int) : ImportBanner
    data class Message(val res: StringResource) : ImportBanner
}

private data class TimedImportBanner(
    val id: Long,
    val banner: ImportBanner
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservingListsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onGoTo: (Double, Double, Double) -> Unit,
    onCenter: (Double, Double) -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val onUserInteraction = LocalOnUserInteraction.current
    val shareFile = rememberFileSharer()

    val manualLists = viewModel.manualLists
    val nightSessions = viewModel.nightSessions

    var showAddObjectsDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var nextBannerId by remember { mutableLongStateOf(0L) }
    var importBanner by remember { mutableStateOf<TimedImportBanner?>(null) }
    val showImportBanner: (ImportBanner) -> Unit = { banner ->
        nextBannerId++
        importBanner = TimedImportBanner(nextBannerId, banner)
    }
    val runExport: (suspend () -> Unit) -> Unit = { block ->
        if (!isExporting) {
            isExporting = true
            coroutineScope.launch {
                try {
                    block()
                } finally {
                    isExporting = false
                }
            }
        }
    }

    var selectedView by remember {
        mutableStateOf(
            when {
                viewModel.pendingOpenManualListId != null ->
                    SelectedObservingView.Manual(viewModel.pendingOpenManualListId!!)

                viewModel.pendingOpenNightSession != null ->
                    SelectedObservingView.NightSession(viewModel.pendingOpenNightSession!!)

                manualLists.any { it.id == viewModel.lastSelectedObservingListId } ->
                    SelectedObservingView.Manual(viewModel.lastSelectedObservingListId)

                manualLists.isNotEmpty() ->
                    SelectedObservingView.Manual(manualLists.first().id)

                nightSessions.isNotEmpty() ->
                    SelectedObservingView.NightSession(nightSessions.first().observingNight)

                else -> null
            }
        )
    }

    LaunchedEffect(viewModel.pendingOpenManualListId, viewModel.pendingOpenNightSession) {
        val targetListId = viewModel.pendingOpenManualListId
        val targetNight = viewModel.pendingOpenNightSession
        if (targetListId != null) {
            selectedView = SelectedObservingView.Manual(targetListId)
            showAddObjectsDialog = false
            viewModel.consumePendingObservingListNavigation()
        } else if (targetNight != null) {
            selectedView = SelectedObservingView.NightSession(targetNight)
            showAddObjectsDialog = false
            viewModel.consumePendingObservingListNavigation()
        }
    }

    // Keep selectedView valid if lists/sessions change (e.g. deletion or first creation)
    LaunchedEffect(manualLists, nightSessions) {
        val stillValid = when (val current = selectedView) {
            is SelectedObservingView.Manual -> manualLists.any { it.id == current.listId }
            is SelectedObservingView.NightSession ->
                nightSessions.any { it.observingNight == current.observingNight }

            null -> false
        }
        if (!stillValid) {
            selectedView = when {
                manualLists.any { it.id == viewModel.lastSelectedObservingListId } ->
                    SelectedObservingView.Manual(viewModel.lastSelectedObservingListId)

                manualLists.isNotEmpty() ->
                    SelectedObservingView.Manual(manualLists.first().id)

                nightSessions.isNotEmpty() ->
                    SelectedObservingView.NightSession(nightSessions.first().observingNight)

                else -> null
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }
    var isEditingItems by remember { mutableStateOf(false) }
    var isEditingDrawerLists by remember { mutableStateOf(false) }

    var showCreateListDialog by remember { mutableStateOf(false) }
    var renamingList by remember { mutableStateOf<ObservingListSummary?>(null) }
    var deletingList by remember { mutableStateOf<ObservingListSummary?>(null) }
    var deletingNightEntry by remember { mutableStateOf<ResolvedListEntry?>(null) }
    var selectedObject by remember { mutableStateOf<CatalogObject?>(null) }
    var selectedFromNightSession by remember { mutableStateOf(false) }
    var pendingUndo by remember { mutableStateOf<PendingUndoRemoval?>(null) }

    var sortBy by viewModel::listsSortBy
    var visibilityFilter by viewModel::listsVisibilityFilter
    var observedFilter by viewModel::listsObservedFilter

    val currentRaState =
        remember { mutableStateOf(if (viewModel.isEngineRunning) viewModel.engineStats.ra else null) }
    val currentDecState =
        remember { mutableStateOf(if (viewModel.isEngineRunning) viewModel.engineStats.dec else null) }
    val currentTimeMsState =
        remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }

    LaunchedEffect(Unit) {
        viewModel.refreshUserData()
        while (true) {
            currentRaState.value = if (viewModel.isEngineRunning) viewModel.engineStats.ra else null
            currentDecState.value =
                if (viewModel.isEngineRunning) viewModel.engineStats.dec else null
            currentTimeMsState.longValue = Clock.System.now().toEpochMilliseconds()
            delay(5000)
        }
    }

    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) {
            delay(4000)
            pendingUndo = null
        }
    }

    LaunchedEffect(importBanner) {
        if (importBanner != null) {
            delay(4000)
            importBanner = null
        }
    }

    var rawEntries by remember { mutableStateOf<List<ResolvedListEntry>>(emptyList()) }
    var loadedView by remember { mutableStateOf<SelectedObservingView?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(
        selectedView,
        viewModel.userDataVersion,
        viewModel.cometsRefreshVersion,
        viewModel.currentLat,
        viewModel.currentLon
    ) {
        val current = selectedView
        if (current == null) {
            rawEntries = emptyList()
            loadedView = null
            isLoading = false
            return@LaunchedEffect
        }
        if (loadedView != current) {
            rawEntries = emptyList()
        }
        isLoading = true
        rawEntries = when (current) {
            is SelectedObservingView.Manual ->
                viewModel.resolveManualListEntries(
                    listId = current.listId,
                    lat = viewModel.currentLat,
                    lon = viewModel.currentLon
                )

            is SelectedObservingView.NightSession ->
                viewModel.resolveNightSessionEntries(
                    observingNight = current.observingNight,
                    lat = viewModel.currentLat,
                    lon = viewModel.currentLon
                )
        }
        loadedView = current
        isLoading = false
    }

    LaunchedEffect(selectedView) {
        isEditingItems = false
        pendingUndo = null
    }

    LaunchedEffect(manualLists.isEmpty()) {
        if (manualLists.isEmpty()) {
            isEditingDrawerLists = false
        }
    }

    LaunchedEffect(rawEntries.isEmpty(), isLoading) {
        if (!isLoading && rawEntries.isEmpty()) {
            isEditingItems = false
        }
    }

    val isManualSelected = selectedView is SelectedObservingView.Manual
    val activeManualList = (selectedView as? SelectedObservingView.Manual)?.let { sel ->
        manualLists.firstOrNull { it.id == sel.listId }
    }
    val activeNightKey = (selectedView as? SelectedObservingView.NightSession)?.observingNight

    // DSS2 HQ bulk download planning (manual lists only)
    val isHqDownloading = viewModel.hqDownloadState is HiPSHqDownloadState.Downloading
    var manualListMissingHqKeys by remember(selectedView) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(
        selectedView,
        rawEntries,
        viewModel.isSkyPackDownloaded,
        isHqDownloading,
        if (isHqDownloading) 0 else viewModel.hqIndex.size
    ) {
        if (!isManualSelected || rawEntries.isEmpty()) {
            manualListMissingHqKeys = null
            return@LaunchedEffect
        }
        if (isHqDownloading && manualListMissingHqKeys != null) return@LaunchedEffect
        manualListMissingHqKeys = viewModel.planObjectsMissingHqKeys(
            rawEntries.map { it.catalogObject }
        )
    }

    val lat = viewModel.currentLat
    val lon = viewModel.currentLon
    val currentTimeMs = currentTimeMsState.longValue
    val currentRa = currentRaState.value
    val currentDec = currentDecState.value

    // In item Edit mode, show raw custom list order unfiltered so drag indices are unambiguous
    val displayedEntries = remember(
        rawEntries,
        isEditingItems,
        isManualSelected,
        searchQuery,
        sortBy,
        visibilityFilter,
        observedFilter,
        viewModel.observedKeys,
        viewModel.observedStaticCoLocatedKeys,
        lat,
        lon,
        currentRa,
        currentDec,
        currentTimeMs
    ) {
        if (isEditingItems && isManualSelected) {
            rawEntries
        } else {
            val query = searchQuery.trim()
            val hasValidCoords =
                MainViewModel.isValidCoordinate(lat, lon) && lat != null && lon != null
            val checkAboveHorizon = visibilityFilter == "Above Horizon" && hasValidCoords
            val lstRad = if (checkAboveHorizon) {
                Math.toRadians(AstrometryUtils.calculateLST(lon, currentTimeMs))
            } else {
                0.0
            }
            val sinLat = if (checkAboveHorizon) sin(Math.toRadians(lat)) else 0.0
            val cosLat = if (checkAboveHorizon) cos(Math.toRadians(lat)) else 0.0

            val filtered = rawEntries.filter { entry ->
                val obj = entry.catalogObject
                if (query.isNotEmpty() &&
                    !obj.displayName.contains(query, ignoreCase = true) &&
                    !obj.designation.contains(query, ignoreCase = true) &&
                    entry.observationNotePreview?.contains(query, ignoreCase = true) != true
                ) {
                    return@filter false
                }
                if (checkAboveHorizon) {
                    val decRad = obj.dec * Math.PI / 180.0
                    val altSin = sin(decRad) * sinLat +
                            cos(decRad) * cosLat * cos(lstRad - obj.ra * Math.PI / 180.0)
                    if (altSin <= 0.0) return@filter false
                }
                if (isManualSelected && observedFilter != "All") {
                    val observed = viewModel.isObjectObserved(obj)
                    if (observedFilter == "Observed" && !observed) return@filter false
                    if (observedFilter == "Unobserved" && observed) return@filter false
                }
                true
            }

            when (sortBy) {
                "list_order" -> filtered
                "distance" -> if (currentRa != null && currentDec != null) {
                    filtered.sortedBy {
                        AstrometryUtils.angularDistance(
                            currentRa,
                            currentDec,
                            it.catalogObject.ra,
                            it.catalogObject.dec
                        )
                    }
                } else {
                    filtered
                }

                "name" -> filtered.sortedWith(
                    compareBy { it.catalogObject.displayName.lowercase() }
                )

                "brightness" -> filtered.sortedWith(
                    compareBy<ResolvedListEntry> { it.catalogObject.magnitude == null }
                        .thenBy { it.catalogObject.magnitude }
                )

                "catalog ID" -> filtered.sortedWith(
                    compareBy<ResolvedListEntry> { getCatalogPriority(it.catalogObject) }
                        .thenBy { it.catalogObject.catalogEntry.toIntOrNull() ?: Int.MAX_VALUE }
                        .thenBy { it.catalogObject.catalogEntry }
                )

                else -> filtered
            }
        }
    }

    val canDirectReorderItems =
        isManualSelected &&
                (isEditingItems || (searchQuery.isBlank() && sortBy == "list_order")) &&
                displayedEntries.size == rawEntries.size

    BackHandler {
        if (showKeyboard) {
            showKeyboard = false
            focusManager.clearFocus()
        } else if (isEditingItems) {
            isEditingItems = false
        } else {
            onDismiss()
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val is24Hour = is24HourFormat()
    val amLabel = stringResource(Res.string.label_am)
    val pmLabel = stringResource(Res.string.label_pm)
    val importedListNamePattern = stringResource(Res.string.format_imported_list_name, "{ts}")
    val launchCsvPicker = rememberCsvFilePicker(
        onFilePicked = { csvContent ->
            if (isImporting) return@rememberCsvFilePicker
            isImporting = true
            coroutineScope.launch {
                try {
                    val parsed = withContext(Dispatchers.IO) {
                        ObservingListCsv.parse(csvContent)
                    }
                    if (parsed == null) {
                        showImportBanner(ImportBanner.Message(Res.string.msg_import_unsupported_format))
                        return@launch
                    }
                    if (parsed.rows.isEmpty()) {
                        showImportBanner(ImportBanner.Message(Res.string.msg_import_no_valid_rows))
                        return@launch
                    }
                    val timestampLabel = UserDatabase.formatObservationTimestamp(
                        timestampMs = Clock.System.now().toEpochMilliseconds(),
                        is24Hour = is24Hour,
                        amLabel = amLabel,
                        pmLabel = pmLabel
                    )
                    val singleListName = importedListNamePattern.replace("{ts}", timestampLabel)
                    val result = viewModel.importParsedCsv(
                        parsed = parsed,
                        singleListName = singleListName,
                        lat = lat,
                        lon = lon
                    )
                    when (result.format) {
                        CsvImportFormat.SINGLE_LIST -> {
                            val newId = result.primaryListId
                            if (newId != null) {
                                selectedView = SelectedObservingView.Manual(newId)
                                showImportBanner(ImportBanner.SingleList(result.objectsAdded))
                                drawerState.close()
                            } else {
                                showImportBanner(ImportBanner.Message(Res.string.msg_import_no_valid_rows))
                            }
                        }

                        CsvImportFormat.FULL_LISTS -> {
                            val openId = result.primaryListId
                            if (openId != null && result.listsTouched > 0) {
                                selectedView = SelectedObservingView.Manual(openId)
                                showImportBanner(
                                    ImportBanner.FullLists(
                                        result.listsTouched,
                                        result.objectsAdded
                                    )
                                )
                                drawerState.close()
                            } else if (result.duplicateObjectsSkipped > 0) {
                                showImportBanner(ImportBanner.Message(Res.string.msg_import_already_exists))
                            } else {
                                showImportBanner(ImportBanner.Message(Res.string.msg_import_no_valid_rows))
                            }
                        }

                        CsvImportFormat.OBSERVATIONS -> {
                            val latestNight = result.latestImportedNight
                            if (latestNight != null) {
                                selectedView = SelectedObservingView.NightSession(latestNight)
                                showImportBanner(
                                    ImportBanner.Observations(
                                        added = result.observationsAdded,
                                        duplicatesSkipped = result.duplicateObservationsSkipped
                                    )
                                )
                                drawerState.close()
                            } else if (result.duplicateObservationsSkipped > 0) {
                                showImportBanner(ImportBanner.Message(Res.string.msg_import_already_exists))
                            } else {
                                showImportBanner(ImportBanner.Message(Res.string.msg_import_no_valid_rows))
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("ObservingListsDialog", "Failed to import CSV: ${e.message}")
                    showImportBanner(ImportBanner.Message(Res.string.msg_import_read_failed))
                } finally {
                    isImporting = false
                }
            }
        },
        onError = { error ->
            showImportBanner(
                ImportBanner.Message(
                    when (error) {
                        FilePickError.TOO_LARGE -> Res.string.msg_import_file_too_large
                        FilePickError.READ_FAILED -> Res.string.msg_import_read_failed
                    }
                )
            )
        }
    )

    Dialog(
        onDismissRequest = {
            if (showKeyboard) {
                showKeyboard = false
                focusManager.clearFocus()
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .notifyUserInteraction(onUserInteraction),
            color = MaterialTheme.colorScheme.background
        ) {
            val drawerListState = rememberLazyListState()
            val drawerReorderState = rememberDragReorderState(
                listState = drawerListState,
                onMoveKeys = { fromKey, toKey ->
                    val fromId = (fromKey as? String)?.removePrefix("ml_")?.toLongOrNull()
                        ?: return@rememberDragReorderState false
                    val toId = (toKey as? String)?.removePrefix("ml_")?.toLongOrNull()
                        ?: return@rememberDragReorderState false
                    val currentLists = viewModel.manualLists
                    val fromIdx = currentLists.indexOfFirst { it.id == fromId }
                    val toIdx = currentLists.indexOfFirst { it.id == toId }
                    if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) {
                        return@rememberDragReorderState false
                    }
                    val mutable = currentLists.toMutableList()
                    val moved = mutable.removeAt(fromIdx)
                    mutable.add(toIdx, moved)
                    viewModel.reorderManualListsInMemory(mutable)
                    true
                },
                onDragEnd = {
                    viewModel.persistManualListOrderAsync(viewModel.manualLists.map { it.id })
                }
            )

            Box(modifier = Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = drawerReorderState.draggedKey == null,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = MaterialTheme.colorScheme.surface,
                            drawerContentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth(0.80f)
                                .widthIn(max = 300.dp)
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(Res.string.title_observing_lists),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { launchCsvPicker() },
                                        enabled = !isImporting,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Default.Upload,
                                            contentDescription = stringResource(Res.string.cd_import_csv),
                                            tint = MaterialTheme.colorScheme.primary.copy(
                                                alpha = if (isImporting) 0.38f else 1f
                                            )
                                        )
                                    }
                                    if (manualLists.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                isEditingDrawerLists = !isEditingDrawerLists
                                            },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isEditingDrawerLists) Default.Check else Outlined.Edit,
                                                contentDescription = stringResource(
                                                    if (isEditingDrawerLists) Res.string.btn_done else Res.string.btn_edit
                                                ),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { showCreateListDialog = true },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Default.Add,
                                            contentDescription = stringResource(Res.string.btn_new_list),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()

                            LazyColumn(
                                state = drawerListState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                item(key = "section_manual") {
                                    val canExportAllLists =
                                        !isEditingDrawerLists && manualLists.any { it.itemCount > 0 }
                                    DrawerSectionHeader(
                                        title = stringResource(Res.string.section_manual_lists),
                                        exportContentDescription = if (canExportAllLists) {
                                            stringResource(Res.string.cd_export_all_lists)
                                        } else {
                                            null
                                        },
                                        exportEnabled = !isExporting,
                                        onExport = if (canExportAllLists) {
                                            {
                                                runExport {
                                                    val lists =
                                                        viewModel.resolveAllManualListsForExport(
                                                            lat = lat,
                                                            lon = lon
                                                        )
                                                    if (lists.isNotEmpty()) {
                                                        val csv = withContext(Dispatchers.IO) {
                                                            UserDatabase.buildAllManualListsCsv(
                                                                lists
                                                            )
                                                        }
                                                        shareFile(
                                                            "all_observing_lists.csv",
                                                            "text/csv",
                                                            csv
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                }

                                if (manualLists.isEmpty()) {
                                    item(key = "empty_manual") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            TextButton(onClick = { showCreateListDialog = true }) {
                                                Icon(
                                                    Default.Add,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(Res.string.btn_new_list))
                                            }
                                        }
                                    }
                                } else {
                                    items(
                                        items = manualLists,
                                        key = { "ml_${it.id}" }
                                    ) { list ->
                                        val itemKey = "ml_${list.id}"
                                        val isSelected =
                                            selectedView == SelectedObservingView.Manual(list.id)
                                        NavigationDrawerItem(
                                            label = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    if (isEditingDrawerLists) {
                                                        Icon(
                                                            imageVector = Default.DragHandle,
                                                            contentDescription = stringResource(Res.string.cd_reorder),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier
                                                                .dragReorderHandle(
                                                                    drawerReorderState,
                                                                    itemKey
                                                                )
                                                                .padding(end = 8.dp)
                                                                .size(20.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = list.name,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isEditingDrawerLists) {
                                                        IconButton(
                                                            onClick = { renamingList = list },
                                                            modifier = Modifier.size(44.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Outlined.Edit,
                                                                contentDescription = stringResource(
                                                                    Res.string.cd_rename
                                                                ),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                        Spacer(Modifier.width(8.dp))
                                                        IconButton(
                                                            onClick = { deletingList = list },
                                                            modifier = Modifier.size(44.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Outlined.Delete,
                                                                contentDescription = stringResource(
                                                                    Res.string.cd_delete
                                                                ),
                                                                tint = extColors.ctaStop,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            text = "${list.itemCount}",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = extColors.secondaryText,
                                                            modifier = Modifier.padding(start = 8.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            selected = isSelected,
                                            onClick = {
                                                if (!isEditingDrawerLists) {
                                                    selectedView =
                                                        SelectedObservingView.Manual(list.id)
                                                    viewModel.lastSelectedObservingListId = list.id
                                                    coroutineScope.launch { drawerState.close() }
                                                }
                                            },
                                            modifier = Modifier
                                                .padding(NavigationDrawerItemDefaults.ItemPadding)
                                                .dragReorderItem(
                                                    state = drawerReorderState,
                                                    itemKey = itemKey,
                                                    enableLongPressDrag = manualLists.size > 1
                                                ),
                                            colors = NavigationDrawerItemDefaults.colors(
                                                selectedContainerColor = extColors.drawerSelectedContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                }

                                if (nightSessions.isNotEmpty()) {
                                    item(key = "divider_sessions") {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    }
                                    item(key = "section_sessions") {
                                        DrawerSectionHeader(
                                            title = stringResource(Res.string.section_past_sessions),
                                            exportContentDescription = stringResource(Res.string.cd_export_all_observations),
                                            exportEnabled = !isExporting,
                                            onExport = {
                                                runExport {
                                                    val allObs =
                                                        viewModel.getAllObservationsForExport()
                                                    if (allObs.isNotEmpty()) {
                                                        val csv = withContext(Dispatchers.IO) {
                                                            UserDatabase.buildObservationsCsv(allObs)
                                                        }
                                                        shareFile(
                                                            "all_observations.csv",
                                                            "text/csv",
                                                            csv
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    items(
                                        items = nightSessions,
                                        key = { "ns_${it.observingNight}" }
                                    ) { session ->
                                        val isSelected =
                                            selectedView == SelectedObservingView.NightSession(
                                                session.observingNight
                                            )
                                        NavigationDrawerItem(
                                            label = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = session.observingNight,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = "${session.objectCount}",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = extColors.secondaryText,
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    )
                                                }
                                            },
                                            selected = isSelected,
                                            onClick = {
                                                selectedView =
                                                    SelectedObservingView.NightSession(session.observingNight)
                                                coroutineScope.launch { drawerState.close() }
                                            },
                                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                            colors = NavigationDrawerItemDefaults.colors(
                                                selectedContainerColor = extColors.drawerSelectedContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isLandscape = maxWidth > maxHeight && maxWidth >= 480.dp

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(showKeyboard) {
                                    if (showKeyboard) {
                                        detectTapGestures {
                                            showKeyboard = false
                                            focusManager.clearFocus()
                                        }
                                    }
                                }
                        ) {
                            val topTitle = activeManualList?.name
                                ?: activeNightKey
                                ?: stringResource(Res.string.title_observing_lists)

                            TopAppBar(
                                title = {
                                    Text(
                                        text = topTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable {
                                            coroutineScope.launch { drawerState.open() }
                                        }
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                        Icon(
                                            Default.Menu,
                                            contentDescription = stringResource(Res.string.cd_open_lists_menu)
                                        )
                                    }
                                },
                                actions = {
                                    if (selectedView != null && loadedView == selectedView && !isLoading && rawEntries.isNotEmpty() && !isEditingItems) {
                                        IconButton(
                                            enabled = !isExporting,
                                            onClick = {
                                                if (activeManualList != null) {
                                                    val listName = activeManualList.name
                                                    val entriesSnapshot = rawEntries.toList()
                                                    runExport {
                                                        val csv = withContext(Dispatchers.IO) {
                                                            UserDatabase.buildManualListCsv(
                                                                entriesSnapshot
                                                            )
                                                        }
                                                        val fileName =
                                                            "${
                                                                UserDatabase.sanitizeExportFileName(
                                                                    listName
                                                                )
                                                            }.csv"
                                                        shareFile(fileName, "text/csv", csv)
                                                    }
                                                } else if (activeNightKey != null) {
                                                    runExport {
                                                        val obs =
                                                            viewModel.getObservationsForNight(
                                                                activeNightKey
                                                            )
                                                        if (obs.isNotEmpty()) {
                                                            val csv = withContext(Dispatchers.IO) {
                                                                UserDatabase.buildObservationsCsv(
                                                                    obs
                                                                )
                                                            }
                                                            shareFile(
                                                                "observations_$activeNightKey.csv",
                                                                "text/csv",
                                                                csv
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Default.Download,
                                                contentDescription = stringResource(Res.string.cd_export_csv),
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }

                                    if (selectedView != null && (rawEntries.isNotEmpty() || isEditingItems)) {
                                        IconButton(
                                            onClick = {
                                                showKeyboard = false
                                                isEditingItems = !isEditingItems
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (isEditingItems) Default.Check else Outlined.Edit,
                                                contentDescription = stringResource(
                                                    if (isEditingItems) Res.string.btn_done else Res.string.btn_edit
                                                ),
                                                tint = if (isEditingItems) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        }
                                    }

                                    if (selectedView != null && !isEditingItems) {
                                        Box {
                                            val hasActiveFilters =
                                                visibilityFilter != "All Objects" ||
                                                        (isManualSelected && observedFilter != "All")
                                            IconButton(onClick = { showSettingsMenu = true }) {
                                                FilterSettingsIcon(
                                                    hasActiveFilters = hasActiveFilters,
                                                    contentDescription = stringResource(Res.string.cd_settings)
                                                )
                                            }
                                            val sortOptions = listOf(
                                                "list_order" to if (isManualSelected) {
                                                    Res.string.sort_list_order
                                                } else {
                                                    Res.string.sort_observation_time
                                                },
                                                "catalog ID" to Res.string.sort_catalog_id,
                                                "name" to Res.string.sort_name,
                                                "brightness" to Res.string.sort_brightness,
                                                "distance" to Res.string.sort_distance
                                            )
                                            CatalogFilterSortDropdown(
                                                expanded = showSettingsMenu,
                                                onDismissRequest = { showSettingsMenu = false },
                                                sortOptions = sortOptions,
                                                sortBy = sortBy,
                                                onSortByChange = { sortBy = it },
                                                visibilityFilter = visibilityFilter,
                                                onVisibilityFilterChange = {
                                                    visibilityFilter = it
                                                },
                                                observedFilter = if (isManualSelected) observedFilter else null,
                                                onObservedFilterChange = if (isManualSelected) {
                                                    { observedFilter = it }
                                                } else {
                                                    null
                                                }
                                            )
                                        }
                                    }

                                    IconButton(onClick = onDismiss) {
                                        Icon(
                                            Default.Close,
                                            contentDescription = stringResource(Res.string.cd_close)
                                        )
                                    }
                                }
                            )

                            if (selectedView != null && !isEditingItems) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    NightVisionSearchBar(
                                        query = searchQuery,
                                        placeholder = stringResource(Res.string.placeholder_search),
                                        onTap = { showKeyboard = true },
                                        onClear = { searchQuery = "" },
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isManualSelected && activeManualList != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconBarItem(
                                            icon = Default.Add,
                                            label = stringResource(Res.string.btn_add_objects),
                                            iconTint = MaterialTheme.colorScheme.primary,
                                            labelColor = MaterialTheme.colorScheme.primary,
                                            onClick = {
                                                showKeyboard = false
                                                showAddObjectsDialog = true
                                            }
                                        )
                                    }
                                }
                            }

                            val listContent: @Composable (Modifier) -> Unit = { contentModifier ->
                                Box(modifier = contentModifier.fillMaxSize()) {
                                    if (selectedView == null) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(24.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = stringResource(Res.string.status_no_lists),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = stringResource(Res.string.hint_add_from_object_info),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = extColors.secondaryText
                                            )
                                            Spacer(Modifier.height(16.dp))
                                            Button(onClick = { showCreateListDialog = true }) {
                                                Icon(Default.Add, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(Res.string.btn_new_list))
                                            }
                                        }
                                    } else {
                                        val itemsListState = rememberLazyListState()
                                        val itemReorderState = rememberDragReorderState(
                                            listState = itemsListState,
                                            onMoveKeys = { fromKey, toKey ->
                                                val fromId =
                                                    (fromKey as? String)?.removePrefix("item_")
                                                        ?.toLongOrNull()
                                                        ?: return@rememberDragReorderState false
                                                val toId =
                                                    (toKey as? String)?.removePrefix("item_")
                                                        ?.toLongOrNull()
                                                        ?: return@rememberDragReorderState false
                                                val fromIdx =
                                                    rawEntries.indexOfFirst { it.listItemId == fromId }
                                                val toIdx =
                                                    rawEntries.indexOfFirst { it.listItemId == toId }
                                                if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) {
                                                    return@rememberDragReorderState false
                                                }
                                                val mutable = rawEntries.toMutableList()
                                                val moved = mutable.removeAt(fromIdx)
                                                mutable.add(toIdx, moved)
                                                rawEntries = mutable
                                                true
                                            },
                                            onDragEnd = {
                                                val listId = activeManualList?.id
                                                    ?: return@rememberDragReorderState
                                                val orderedIds =
                                                    rawEntries.mapNotNull { it.listItemId }
                                                viewModel.reorderListItemsAsync(listId, orderedIds)
                                            }
                                        )

                                        val hasValidCoords =
                                            MainViewModel.isValidCoordinate(
                                                lat,
                                                lon
                                            ) && lat != null && lon != null
                                        val telescopeAltAz =
                                            if (currentRa != null && currentDec != null && hasValidCoords) {
                                                AstrometryUtils.calculateAltAz(
                                                    currentRa,
                                                    currentDec,
                                                    lat,
                                                    lon,
                                                    currentTimeMs
                                                )
                                            } else {
                                                null
                                            }

                                        LazyColumn(
                                            state = itemsListState,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            if (isManualSelected && activeManualList != null && !isEditingItems) {
                                                val listTargetId = "list_${activeManualList.id}"
                                                val hqState = viewModel.hqDownloadState
                                                val isThisListDownloading =
                                                    hqState is HiPSHqDownloadState.Downloading &&
                                                            hqState.targetId == listTargetId
                                                val missing = manualListMissingHqKeys
                                                if (isThisListDownloading || (missing != null && missing.isNotEmpty())) {
                                                    item(key = "hq_header_$listTargetId") {
                                                        HqBulkDownloadHeader(
                                                            isDownloading = isThisListDownloading,
                                                            hqState = hqState,
                                                            missingKeys = missing,
                                                            onShowProgress = {
                                                                viewModel.showHqDownloadProgress =
                                                                    true
                                                            },
                                                            onRequestDownload = { keys ->
                                                                viewModel.requestListHqDownload(
                                                                    listId = activeManualList.id,
                                                                    listTitle = activeManualList.name,
                                                                    missingKeys = keys
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            items(
                                                items = displayedEntries,
                                                key = { entry ->
                                                    entry.listItemId?.let { "item_$it" }
                                                        ?: "obs_${entry.catalogObject.catalogLabel}_${entry.catalogObject.catalogEntry}"
                                                }
                                            ) { entry ->
                                                val obj = entry.catalogObject
                                                val rowKey = entry.listItemId?.let { "item_$it" }
                                                    ?: "obs_${obj.catalogLabel}_${obj.catalogEntry}"
                                                val isBelowHorizon =
                                                    visibilityFilter == "All Objects" &&
                                                            hasValidCoords &&
                                                            AstrometryUtils.calculateAltAz(
                                                                obj.ra,
                                                                obj.dec,
                                                                lat,
                                                                lon,
                                                                currentTimeMs
                                                            ).alt <= 0.0

                                                val notePreview = if (!isManualSelected) {
                                                    val timeLabel = entry.observationTimeMs?.let {
                                                        UserDatabase.formatObservationTimeOnly(
                                                            it,
                                                            is24Hour = is24Hour,
                                                            amLabel = amLabel,
                                                            pmLabel = pmLabel
                                                        )
                                                    }.orEmpty()
                                                    val countPrefix =
                                                        if (entry.observationCount > 1) {
                                                            stringResource(
                                                                Res.string.format_obs_time_count,
                                                                timeLabel,
                                                                entry.observationCount
                                                            )
                                                        } else {
                                                            timeLabel
                                                        }
                                                    val noteText = entry.observationNotePreview
                                                        ?.replace('\n', ' ')
                                                        ?.takeIf { it.isNotBlank() }
                                                        ?: stringResource(Res.string.status_logged_without_notes)
                                                    if (countPrefix.isNotEmpty()) "$countPrefix — $noteText" else noteText
                                                } else {
                                                    null
                                                }

                                                Column(
                                                    modifier = Modifier.dragReorderItem(
                                                        state = itemReorderState,
                                                        itemKey = rowKey,
                                                        enableLongPressDrag = canDirectReorderItems && rawEntries.size > 1
                                                    )
                                                ) {
                                                    CatalogObjectListRow(
                                                        obj = obj,
                                                        isBelowHorizon = isBelowHorizon,
                                                        isEngineRunning = viewModel.isEngineRunning,
                                                        isObserved = !isManualSelected || viewModel.isObjectObserved(
                                                            obj
                                                        ),
                                                        telescopeAltAz = telescopeAltAz,
                                                        currentRa = currentRa,
                                                        currentDec = currentDec,
                                                        lat = lat,
                                                        lon = lon,
                                                        currentTimeMs = currentTimeMs,
                                                        extraSubtext = notePreview,
                                                        isEditMode = isEditingItems,
                                                        showDragHandle = isManualSelected,
                                                        dragHandleModifier = if (isManualSelected) {
                                                            Modifier.dragReorderHandle(
                                                                state = itemReorderState,
                                                                itemKey = rowKey
                                                            )
                                                        } else {
                                                            Modifier
                                                        },
                                                        onRemoveClick = when {
                                                            isEditingItems && isManualSelected && activeManualList != null -> {
                                                                {
                                                                    val listId = activeManualList.id
                                                                    val ref =
                                                                        UserDatabase.targetRefFrom(
                                                                            catalogObject = obj,
                                                                            coords = obj.ra to obj.dec,
                                                                            fallbackTitle = obj.displayName
                                                                        )
                                                                    rawEntries =
                                                                        rawEntries.filterNot {
                                                                            it.listItemId == entry.listItemId
                                                                        }
                                                                    pendingUndo =
                                                                        PendingUndoRemoval(
                                                                            listId,
                                                                            ref
                                                                        )
                                                                    coroutineScope.launch {
                                                                        viewModel.removeObjectFromList(
                                                                            listId,
                                                                            obj.catalogLabel,
                                                                            obj.catalogEntry
                                                                        )
                                                                    }
                                                                }
                                                            }

                                                            isEditingItems && !isManualSelected && entry.observationIds.isNotEmpty() -> {
                                                                { deletingNightEntry = entry }
                                                            }

                                                            else -> null
                                                        },
                                                        onClick = {
                                                            selectedFromNightSession =
                                                                !isManualSelected
                                                            selectedObject = obj
                                                        },
                                                        onCenter = { onCenter(obj.ra, obj.dec) },
                                                        onGoTo = { onGoTo(obj.ra, obj.dec, 2000.0) }
                                                    )
                                                    HorizontalDivider()
                                                }
                                            }

                                            if (displayedEntries.isEmpty()) {
                                                item(key = "empty_state") {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(
                                                                vertical = 96.dp,
                                                                horizontal = 24.dp
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isLoading) {
                                                            CircularProgressIndicator()
                                                        } else if (rawEntries.isEmpty() && isManualSelected) {
                                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                                Text(
                                                                    text = stringResource(Res.string.status_empty_list),
                                                                    style = MaterialTheme.typography.bodyLarge
                                                                )
                                                                Spacer(Modifier.height(6.dp))
                                                                Text(
                                                                    text = stringResource(Res.string.hint_add_from_object_info),
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    color = extColors.secondaryText
                                                                )
                                                                Spacer(Modifier.height(14.dp))
                                                                Button(onClick = {
                                                                    showAddObjectsDialog = true
                                                                }) {
                                                                    Icon(
                                                                        Default.Add,
                                                                        contentDescription = null
                                                                    )
                                                                    Spacer(Modifier.width(6.dp))
                                                                    Text(stringResource(Res.string.btn_add_objects))
                                                                }
                                                            }
                                                        } else {
                                                            Text(stringResource(Res.string.status_no_objects))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val undo = pendingUndo
                                    if (undo != null && importBanner == null) {
                                        FloatingStatusBanner(
                                            modifier = Modifier.align(Alignment.BottomCenter)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    start = 16.dp,
                                                    end = 8.dp,
                                                    top = 6.dp,
                                                    bottom = 6.dp
                                                ),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        Res.string.msg_removed_from_list,
                                                        undo.target.name
                                                    ),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                TextButton(
                                                    onClick = {
                                                        pendingUndo = null
                                                        coroutineScope.launch {
                                                            viewModel.addObjectToList(
                                                                undo.listId,
                                                                undo.target
                                                            )
                                                        }
                                                    }
                                                ) {
                                                    Text(
                                                        text = stringResource(Res.string.btn_undo),
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val keyboardPanel: @Composable (Modifier) -> Unit = { kbModifier ->
                                SearchKeyboardDock(
                                    isLandscape = isLandscape,
                                    viewModel = viewModel,
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    onClose = {
                                        showKeyboard = false
                                        focusManager.clearFocus()
                                    },
                                    modifier = kbModifier
                                )
                            }

                            NightVisionKeyboardSplitLayout(
                                isLandscape = isLandscape,
                                showKeyboard = showKeyboard,
                                landscapeKeyboardOnRight = viewModel.landscapeKeyboardOnRight,
                                content = listContent,
                                keyboard = keyboardPanel
                            )
                        }
                    }
                }

                val activeBanner = importBanner?.banner
                if (activeBanner != null) {
                    val statusText = when (activeBanner) {
                        is ImportBanner.SingleList -> stringResource(
                            Res.string.msg_imported_single_list,
                            activeBanner.objectsAdded
                        )

                        is ImportBanner.FullLists -> stringResource(
                            Res.string.msg_imported_full_lists,
                            activeBanner.listsTouched,
                            activeBanner.objectsAdded
                        )

                        is ImportBanner.Observations -> if (activeBanner.duplicatesSkipped > 0) {
                            stringResource(
                                Res.string.msg_imported_observations_with_dups,
                                activeBanner.added,
                                activeBanner.duplicatesSkipped
                            )
                        } else {
                            stringResource(
                                Res.string.msg_imported_observations,
                                activeBanner.added
                            )
                        }

                        is ImportBanner.Message -> stringResource(activeBanner.res)
                    }
                    FloatingStatusBanner(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .clickable { importBanner = null }
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showCreateListDialog) {
        ListNameDialog(
            title = stringResource(Res.string.title_new_list),
            initialName = "",
            viewModel = viewModel,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showCreateListDialog = false
            },
            onConfirm = { name ->
                @Suppress("AssignedValueIsNeverRead")
                showCreateListDialog = false
                coroutineScope.launch {
                    val newId = viewModel.createManualList(name)
                    if (newId > 0L) {
                        selectedView = SelectedObservingView.Manual(newId)
                        drawerState.close()
                    }
                }
            }
        )
    }

    val toRename = renamingList
    if (toRename != null) {
        ListNameDialog(
            title = stringResource(Res.string.title_rename_list),
            initialName = toRename.name,
            viewModel = viewModel,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                renamingList = null
            },
            onConfirm = { newName ->
                val targetId = toRename.id
                @Suppress("AssignedValueIsNeverRead")
                renamingList = null
                coroutineScope.launch {
                    viewModel.renameManualList(targetId, newName)
                }
            }
        )
    }

    val toDelete = deletingList
    if (toDelete != null) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_delete_list),
            text = stringResource(Res.string.msg_delete_list, toDelete.name),
            confirmButtonText = stringResource(Res.string.btn_delete),
            onConfirm = {
                val targetId = toDelete.id
                @Suppress("AssignedValueIsNeverRead")
                deletingList = null
                coroutineScope.launch {
                    viewModel.deleteManualList(targetId)
                }
            },
            dismissButtonText = stringResource(Res.string.btn_cancel),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                deletingList = null
            }
        )
    }

    val nightEntryToDelete = deletingNightEntry
    if (nightEntryToDelete != null && activeNightKey != null) {
        OliveAlertDialog(
            title = stringResource(Res.string.title_delete_observation),
            text = stringResource(
                Res.string.msg_delete_night_observations,
                nightEntryToDelete.catalogObject.displayName,
                activeNightKey
            ),
            confirmButtonText = stringResource(Res.string.btn_delete),
            onConfirm = {
                val ids = nightEntryToDelete.observationIds
                @Suppress("AssignedValueIsNeverRead")
                deletingNightEntry = null
                coroutineScope.launch {
                    viewModel.deleteObservations(ids)
                }
            },
            dismissButtonText = stringResource(Res.string.btn_cancel),
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                deletingNightEntry = null
            }
        )
    }

    if (showAddObjectsDialog && activeManualList != null) {
        AddObjectsToListDialog(
            list = activeManualList,
            existingKeys = remember(rawEntries) {
                rawEntries.mapTo(HashSet()) {
                    UserDatabase.objectKey(
                        it.catalogObject.catalogLabel,
                        it.catalogObject.catalogEntry
                    )
                }
            },
            viewModel = viewModel,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showAddObjectsDialog = false
            }
        )
    }

    val obj = selectedObject
    if (obj != null) {
        val altAz = if (viewModel.hasValidLocation && lat != null && lon != null) {
            AstrometryUtils.calculateAltAz(obj.ra, obj.dec, lat, lon, currentTimeMs)
        } else {
            null
        }
        ObjectInfoPopup(
            coords = obj.ra to obj.dec,
            catalogObject = obj.takeUnless { it.catalogLabel == UserDatabase.CUSTOM_CATALOG_LABEL },
            viewModel = viewModel,
            timeMs = currentTimeMs,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                selectedObject = null
            },
            altAz = altAz,
            onCloseToMap = {
                @Suppress("AssignedValueIsNeverRead")
                selectedObject = null
                onDismiss()
            },
            initialObservationsExpanded = selectedFromNightSession
        )
    }
}

@Composable
private fun DrawerSectionHeader(
    title: String,
    exportContentDescription: String? = null,
    exportEnabled: Boolean = true,
    onExport: (() -> Unit)? = null
) {
    val extColors = LocalOliveExtendedColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = extColors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
        )
        if (onExport != null && exportContentDescription != null) {
            IconButton(
                onClick = onExport,
                enabled = exportEnabled,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Default.Download,
                    contentDescription = exportContentDescription,
                    tint = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (exportEnabled) 1f else 0.38f
                    ),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingStatusBanner(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(16.dp)
            .border(1.dp, extColors.subtleBorder, RoundedCornerShape(12.dp))
    ) {
        content()
    }
}


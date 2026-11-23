// Required Notice: Copyright (c) 2026 Omair Kamil
// See LICENSE file in root directory for license terms.

package com.oakamil.olive

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Full-screen astronomical catalog browser with category drawer, search/filter/sort controls,
 * bulk DSS2 HQ downloads, manual RA/Dec GoTo, and [NightVisionKeyboardSplitLayout].
 *
 * Combines paginated SQLite queries (300-row pages) with dynamically computed solar system
 * ephemerides (planets, asteroids, comets) merged on the first page (`offset == 0`). A 5-second
 * timer keeps telescope coordinates and horizon visibility fresh while open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyCatalogDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onGoTo: (Double, Double, Double) -> Unit,
    onCenter: (Double, Double) -> Unit
) {
    val extColors = LocalOliveExtendedColors.current
    val focusManager = LocalFocusManager.current
    var showKeyboard by remember { mutableStateOf(false) }
    var searchQuery by viewModel::catalogSearchQuery
    var queryValue by rememberSyncedTextFieldValue(searchQuery)
    val hideKeyboard = {
        showKeyboard = false
        focusManager.clearFocus()
    }
    var selectedCategory by viewModel::catalogSelectedCategory
    var sortBy by viewModel::catalogSortBy
    var magLimit by viewModel::catalogMagLimit
    var typeFilters by viewModel::catalogTypeFilters
    var visibilityFilter by viewModel::catalogVisibilityFilter

    val catalogTypeOptions = CATALOG_TYPE_OPTIONS

    var showSettingsMenu by remember { mutableStateOf(false) }
    var isRefreshingComets by remember { mutableStateOf(false) }
    var showCometsError by remember { mutableStateOf(false) }

    var searchResults by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var selectedCatalogObj by remember { mutableStateOf<CatalogObject?>(null) }
    var showGotoRaDecPopup by remember { mutableStateOf(false) }


    val categoryToShortCode = mapOf(
        "Messier" to "M",
        "Caldwell" to "C",
        "Collinder" to "Col",
        "NGC" to "NGC",
        "IC" to "IC",
        "Asteroids" to "Ast",
        "Comets" to "Com",
        "Herschel 400" to "H",
        "Barnard" to "B",
        "Sharpless" to "Sh2",
        "Abell" to "Abl",
        "Arp" to "Arp",
        "SAC Asterisms" to "SaA",
        "SAC Doubles" to "SaM",
        "SAC Red Stars" to "SaR",
        "TAAS 200" to "Ta2",
        "Harris" to "Har",
        "RASC Doubles" to "RDS",
        "EGC" to "EGC",
        "Bright Stars" to "Str"
    )

    val currentRaState =
        remember { mutableStateOf(if (viewModel.isEngineRunning) viewModel.engineStats.ra else null) }
    val currentDecState =
        remember { mutableStateOf(if (viewModel.isEngineRunning) viewModel.engineStats.dec else null) }
    val currentTimeMsState =
        remember { mutableLongStateOf(viewModel.effectiveSkyTimeMs()) }

    LaunchedEffect(viewModel.simulatedTimeMs) {
        while (true) {
            currentRaState.value = if (viewModel.isEngineRunning) viewModel.engineStats.ra else null
            currentDecState.value =
                if (viewModel.isEngineRunning) viewModel.engineStats.dec else null
            currentTimeMsState.longValue = viewModel.effectiveSkyTimeMs()
            delay(5000)
        }
    }

    val currentRa = currentRaState.value
    val currentDec = currentDecState.value

    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var activeListState by remember {
        mutableStateOf<LazyListState?>(
            null
        )
    }
    val catalogDescriptions = remember { viewModel.catalogDb.getCatalogDescriptions() }
    var showInfoForCategory by remember { mutableStateOf<String?>(null) }

    val performFetch: suspend (Int) -> List<CatalogObject> = { offset ->
        val query = searchQuery
        val category = selectedCategory
        val sort = sortBy
        val limitMag = magLimit
        val activeTypeFilters = typeFilters
        val activeVisibility = visibilityFilter
        val lat = viewModel.currentLat
        val lon = viewModel.currentLon
        withContext(Dispatchers.IO) {
            val timeMs = viewModel.effectiveSkyTimeMs()
            val checkVisibility =
                activeVisibility == "Above Horizon" && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
            val horizontalFrame = if (checkVisibility) {
                AstrometryUtils.horizontalFrame(lat, lon, timeMs)
            } else {
                null
            }

            val isAllTypes = activeTypeFilters.size >= catalogTypeOptions.size
            val allPlanets =
                if (category == "Solar System" || category == "All Objects" || category == "Nearest Objects") {
                    viewModel.getPlanets(lat, lon, timeMs)
                } else emptyList()
            val filteredPlanets = allPlanets.filter { p ->
                var passes = true
                if (horizontalFrame != null && !horizontalFrame.isAboveHorizon(p.ra, p.dec)) {
                    passes = false
                }
                if (!isAllTypes && "Planet" !in activeTypeFilters && p.objectType !in activeTypeFilters) {
                    passes = false
                }
                if (query.isNotBlank() && !p.displayName.contains(query, ignoreCase = true)) {
                    passes = false
                }
                if (limitMag != null && (p.magnitude == null || p.magnitude > limitMag)) {
                    passes = false
                }
                if (category == "Nearest Objects" && currentRa != null && currentDec != null) {
                    if (AstrometryUtils.angularDistance(
                            currentRa,
                            currentDec,
                            p.ra,
                            p.dec
                        ) > 10.0
                    ) {
                        passes = false
                    }
                }
                passes
            }

            val sqlResults =
                if (category == "Solar System" || category == "Asteroids" || category == "Comets") {
                    emptyList()
                } else {
                    val filter = when (category) {
                        "Nearest Objects" -> "Nearest Objects"
                        "All Objects" -> null
                        else -> categoryToShortCode[category]
                    }
                    viewModel.catalogDb.searchObjects(
                        searchQuery = query,
                        catalogFilter = filter,
                        sortBy = sort,
                        magLimit = limitMag,
                        typeFilters = if (isAllTypes) null else activeTypeFilters,
                        visibilityFilter = activeVisibility,
                        lat = lat,
                        lon = lon,
                        targetRa = currentRa,
                        targetDec = currentDec,
                        limit = 300,
                        offset = offset,
                        currentTimeMs = timeMs
                    )
                }

            if (offset == 0 && (category == "Solar System" || category == "Asteroids" || category == "Comets" || category == "All Objects" || category == "Nearest Objects")) {
                val allAsteroids =
                    if (category == "Asteroids" || category == "All Objects" || category == "Nearest Objects") {
                        viewModel.getAsteroids(lat, lon, timeMs)
                    } else emptyList()
                val filteredAsteroids = allAsteroids.filter { p ->
                    var passes = true
                    if (horizontalFrame != null && !horizontalFrame.isAboveHorizon(p.ra, p.dec)) {
                        passes = false
                    }
                    if (!isAllTypes && "Asteroid" !in activeTypeFilters && p.objectType !in activeTypeFilters) {
                        passes = false
                    }
                    if (query.isNotBlank() && !p.displayName.contains(query, ignoreCase = true)) {
                        passes = false
                    }
                    if (limitMag != null && (p.magnitude == null || p.magnitude > limitMag)) {
                        passes = false
                    }
                    if (category == "Nearest Objects" && currentRa != null && currentDec != null) {
                        if (AstrometryUtils.angularDistance(
                                currentRa,
                                currentDec,
                                p.ra,
                                p.dec
                            ) > 10.0
                        ) {
                            passes = false
                        }
                    }
                    passes
                }

                val allComets =
                    if (category == "Comets" || category == "All Objects" || category == "Nearest Objects") {
                        viewModel.getComets(lat, lon, timeMs)
                    } else emptyList()
                val filteredComets = allComets.filter { p ->
                    var passes = true
                    if (horizontalFrame != null && !horizontalFrame.isAboveHorizon(p.ra, p.dec)) {
                        passes = false
                    }
                    if (!isAllTypes && "Comet" !in activeTypeFilters && p.objectType !in activeTypeFilters) {
                        passes = false
                    }
                    if (query.isNotBlank() && !p.displayName.contains(query, ignoreCase = true)) {
                        passes = false
                    }
                    if (limitMag != null && (p.magnitude == null || p.magnitude > limitMag)) {
                        passes = false
                    }
                    if (category == "Nearest Objects" && currentRa != null && currentDec != null) {
                        if (AstrometryUtils.angularDistance(
                                currentRa,
                                currentDec,
                                p.ra,
                                p.dec
                            ) > 10.0
                        ) {
                            passes = false
                        }
                    }
                    passes
                }

                val combinedList =
                    (filteredPlanets + filteredAsteroids + filteredComets + sqlResults).toMutableList()
                when (sort) {
                    "distance" -> {
                        if (currentRa != null && currentDec != null) {
                            combinedList.sortBy {
                                AstrometryUtils.angularDistance(
                                    currentRa,
                                    currentDec,
                                    it.ra,
                                    it.dec
                                )
                            }
                        }
                    }

                    "name" -> {
                        combinedList.sortWith(compareBy<CatalogObject> { it.commonName.isNullOrEmpty() }.thenBy { it.commonName }
                            .thenBy { it.catalogEntry.toIntOrNull() ?: Int.MAX_VALUE })
                    }

                    "catalog ID" -> {
                        combinedList.sortWith(compareBy<CatalogObject> {
                            getCatalogPriority(
                                it
                            )
                        }
                            .thenBy { if (it.objectType == "Planet" || it.objectType == "Moon") it.id else 0 }
                            .thenBy { it.catalogEntry.toIntOrNull() ?: Int.MAX_VALUE }
                            .thenBy { it.catalogEntry })
                    }

                    "brightness" -> {
                        combinedList.sortWith(compareBy<CatalogObject> { it.magnitude == null }.thenBy { it.magnitude })
                    }
                }
                combinedList
            } else {
                sqlResults
            }
        }
    }

    val effectiveLat = if (visibilityFilter == "Above Horizon") viewModel.currentLat else null
    val effectiveLon = if (visibilityFilter == "Above Horizon") viewModel.currentLon else null
    val effectiveRa =
        if (selectedCategory == "Nearest Objects" || sortBy == "distance") currentRa else null
    val effectiveDec =
        if (selectedCategory == "Nearest Objects" || sortBy == "distance") currentDec else null

    val isHqDownloading = viewModel.hqDownloadState is HiPSHqDownloadState.Downloading
    var subCatalogMissingKeys by remember(selectedCategory) { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(
        selectedCategory,
        viewModel.isSkyPackDownloaded,
        isHqDownloading,
        if (isHqDownloading) 0 else viewModel.hqIndex.size
    ) {
        val shortCode = categoryToShortCode[selectedCategory]
        if (!HiPSObjectPlanner.isEligibleSubCatalog(shortCode) || shortCode == null) {
            subCatalogMissingKeys = null
            return@LaunchedEffect
        }
        if (isHqDownloading && subCatalogMissingKeys != null) return@LaunchedEffect
        subCatalogMissingKeys = viewModel.planCatalogMissingHqKeys(shortCode)
    }

    LaunchedEffect(
        searchQuery,
        selectedCategory,
        sortBy,
        magLimit,
        typeFilters,
        visibilityFilter,
        viewModel.cometsRefreshVersion,
        viewModel.simulatedTimeMs,
        effectiveLat,
        effectiveLon,
        effectiveRa,
        effectiveDec
    ) {
        isLoading = true
        searchResults = emptyList()
        val results = performFetch(0)
        searchResults = results
        if (results.isNotEmpty()) {
            activeListState?.scrollToItem(0)
        }
        hasMore = results.size == 300
        isLoading = false
    }

    val loadMore = {
        if (!isLoading && hasMore) {
            isLoading = true
            coroutineScope.launch {
                val results = performFetch(searchResults.size)
                searchResults = searchResults + results
                hasMore = results.size == 300
                isLoading = false
            }
        }
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
        val drawerState =
            rememberDrawerState(initialValue = DrawerValue.Closed)
        val coroutineScope = rememberCoroutineScope()
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .widthIn(max = 280.dp)
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(Res.string.title_sky_catalog),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    HorizontalDivider()
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(catalogCategories) { category ->
                            NavigationDrawerItem(
                                label = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(localizedCatalogCategory(category))
                                        val currentShortCode = categoryToShortCode[category]
                                        val currentDesc = catalogDescriptions[currentShortCode]
                                        if (currentDesc != null && currentDesc.isNotBlank()) {
                                            IconButton(onClick = {
                                                showInfoForCategory = category
                                            }) {
                                                Icon(
                                                    Default.Info,
                                                    contentDescription = stringResource(Res.string.cd_info)
                                                )
                                            }
                                        }
                                    }
                                },
                                selected = selectedCategory == category,
                                onClick = {
                                    selectedCategory = category
                                    coroutineScope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
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
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(showKeyboard) {
                        if (showKeyboard) {
                            detectTapGestures {
                                hideKeyboard()
                            }
                        }
                    }) {
                TopAppBar(
                    title = { Text(stringResource(Res.string.title_sky_catalog)) },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(
                                Default.Menu,
                                contentDescription = stringResource(Res.string.cd_open_catalogs_menu)
                            )
                        }
                    },
                    actions = {
                        Box {
                            val hasActiveFilters = visibilityFilter != "All Objects" ||
                                    magLimit != null ||
                                    typeFilters.size < CATALOG_TYPE_OPTIONS.size
                            IconButton(onClick = { showSettingsMenu = true }) {
                                FilterSettingsIcon(
                                    hasActiveFilters = hasActiveFilters,
                                    contentDescription = stringResource(Res.string.cd_settings)
                                )
                            }
                            val sortOptions = listOf(
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
                                onVisibilityFilterChange = { visibilityFilter = it },
                                magLimit = magLimit,
                                onMagLimitChange = { magLimit = it },
                                typeFilters = typeFilters,
                                onTypeFiltersChange = { typeFilters = it }
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Default.Close,
                                contentDescription = stringResource(Res.string.cd_close)
                            )
                        }
                    }
                )

                val activeSimulatedTimeMs = viewModel.simulatedTimeMs
                if (viewModel.isSimulatedTimeActive && activeSimulatedTimeMs != null) {
                    SimulatedTimeBanner(
                        epochMs = activeSimulatedTimeMs,
                        onResetNow = { viewModel.resetSimulatedTime() }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NightVisionSearchBar(
                        value = queryValue,
                        onValueChange = { updated ->
                            queryValue = updated
                            searchQuery = updated.text
                        },
                        placeholder = stringResource(Res.string.placeholder_search),
                        onFocus = { showKeyboard = true },
                        modifier = Modifier.weight(1f)
                    )

                    if (viewModel.isEngineRunning) {
                        Spacer(modifier = Modifier.width(8.dp))

                        IconBarItem(
                            icon = Default.GpsFixed,
                            label = stringResource(Res.string.label_ra_dec),
                            onClick = { showGotoRaDecPopup = true }
                        )
                    }
                }

                val listContent: @Composable (Modifier) -> Unit = { modifier ->
                    val pageListState = rememberLazyListState()
                    LaunchedEffect(pageListState, selectedCategory) {
                        activeListState = pageListState
                    }
                    LazyColumn(
                        modifier = modifier.fillMaxSize(),
                        state = pageListState
                    ) {
                        val currentCategory = selectedCategory
                        val currentShortCode = categoryToShortCode[currentCategory]
                        val missingHq = subCatalogMissingKeys
                        val hqState = viewModel.hqDownloadState
                        val catTargetId = currentShortCode?.let { "cat_$it" }

                        if (currentShortCode != null && HiPSObjectPlanner.isEligibleSubCatalog(
                                currentShortCode
                            )
                        ) {
                            val isThisCatDownloading =
                                hqState is HiPSHqDownloadState.Downloading && hqState.targetId == catTargetId
                            if (isThisCatDownloading || (missingHq != null && missingHq.isNotEmpty())) {
                                item(key = "hq_bulk_header_$currentShortCode") {
                                    val catTitle = localizedCatalogCategory(currentCategory)
                                    HqBulkDownloadHeader(
                                        isDownloading = isThisCatDownloading,
                                        hqState = hqState,
                                        missingKeys = missingHq,
                                        onShowProgress = {
                                            viewModel.showHqDownloadProgress = true
                                        },
                                        onRequestDownload = { keys ->
                                            viewModel.requestCatalogHqDownload(
                                                categoryTitle = catTitle,
                                                shortCode = currentShortCode,
                                                missingKeys = keys
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        if (currentCategory == "Comets") {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isRefreshingComets) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        Button(onClick = {
                                            isRefreshingComets = true
                                            viewModel.refreshComets(
                                                onComplete = {
                                                    isRefreshingComets = false
                                                },
                                                onNetworkError = {
                                                    isRefreshingComets = false
                                                    showCometsError = true
                                                }
                                            )
                                        }) {
                                            Text(stringResource(Res.string.refresh_comets_button))
                                        }
                                    }
                                }
                            }
                        }
                        val currentRa = currentRaState.value
                        val currentDec = currentDecState.value
                        val currentTimeMs = currentTimeMsState.longValue
                        val lat = viewModel.currentLat
                        val lon = viewModel.currentLon
                        val telescopeAltAz =
                            if (currentRa != null && currentDec != null && lat != null && lon != null) {
                                AstrometryUtils.calculateAltAz(
                                    currentRa,
                                    currentDec,
                                    lat,
                                    lon,
                                    currentTimeMs
                                )
                            } else null

                        items(
                            searchResults,
                            key = { "${it.catalogLabel}_${it.id}_${it.catalogEntry}" }
                        ) { obj ->
                            var isBelowHorizon = false
                            if (visibilityFilter == "All Objects" && lat != null && lon != null) {
                                val objAltAz = AstrometryUtils.calculateAltAz(
                                    obj.ra, obj.dec, lat, lon,
                                    currentTimeMs
                                )
                                if (objAltAz.alt <= 0) {
                                    isBelowHorizon = true
                                }
                            }

                            CatalogObjectListRow(
                                obj = obj,
                                isBelowHorizon = isBelowHorizon,
                                isEngineRunning = viewModel.isEngineRunning,
                                isObserved = viewModel.isObjectObserved(obj),
                                telescopeAltAz = telescopeAltAz,
                                currentRa = currentRa,
                                currentDec = currentDec,
                                lat = lat,
                                lon = lon,
                                currentTimeMs = currentTimeMs,
                                onClick = { selectedCatalogObj = obj },
                                onCenter = { onCenter(obj.ra, obj.dec) },
                                onGoTo = { onGoTo(obj.ra, obj.dec, 2000.0) }
                            )
                            HorizontalDivider()
                        }
                        if (searchResults.isEmpty()) {
                            if (isLoading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 128.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            } else {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 128.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(stringResource(Res.string.status_no_objects))
                                    }
                                }
                            }
                        } else if (hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoading) {
                                        CircularProgressIndicator()
                                    } else {
                                        Button(onClick = { loadMore() }) {
                                            Text(stringResource(Res.string.btn_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }


                val keyboardContent: @Composable (Boolean, Modifier) -> Unit =
                    { isLandscape, modifier ->
                        NightVisionKeyboardDock(
                            isLandscape = isLandscape,
                            viewModel = viewModel,
                            onEdit = { transform ->
                                val updated = transform(queryValue)
                                queryValue = updated
                                searchQuery = updated.text
                            },
                            onClose = hideKeyboard,
                            modifier = modifier
                        )
                    }

                NightVisionKeyboardSplitLayout(
                    showKeyboard = showKeyboard,
                    landscapeKeyboardOnRight = viewModel.landscapeKeyboardOnRight,
                    content = listContent,
                    keyboard = keyboardContent
                )
            }
        }
    }

    val currentCategoryInfo = showInfoForCategory
    if (currentCategoryInfo != null) {
        InfoPopup(
            title = currentCategoryInfo,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showInfoForCategory = null
            }
        ) {
            val shortCode = categoryToShortCode[currentCategoryInfo]
            val desc = catalogDescriptions[shortCode] ?: ""
            Text(desc)
        }
    }

    val currentCatalogObj = selectedCatalogObj
    if (currentCatalogObj != null) {
        val lat = viewModel.currentLat
        val lon = viewModel.currentLon
        val timeMs = currentTimeMsState.longValue
        val altAz = if (viewModel.hasValidLocation && lat != null && lon != null) {
            AstrometryUtils.calculateAltAz(
                currentCatalogObj.ra,
                currentCatalogObj.dec,
                lat,
                lon,
                timeMs
            )
        } else {
            null
        }
        ObjectInfoPopup(
            coords = currentCatalogObj.ra to currentCatalogObj.dec,
            catalogObject = currentCatalogObj,
            viewModel = viewModel,
            timeMs = timeMs,
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                selectedCatalogObj = null
            },
            altAz = altAz,
            onCloseToMap = {
                @Suppress("AssignedValueIsNeverRead")
                selectedCatalogObj = null
                onDismiss()
            },
        )
    }

    if (showGotoRaDecPopup) {
        GotoRaDecPopup(
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showGotoRaDecPopup = false
            },
            onGoTo = { ra, dec, epoch ->
                @Suppress("AssignedValueIsNeverRead")
                showGotoRaDecPopup = false
                onGoTo(ra, dec, epoch)
            }
        )
    }

    if (showCometsError) {
        NightVisionDialog(
            title = stringResource(Res.string.comets_catalog_title),
            text = stringResource(Res.string.error_comets_download),
            confirmButtonText = stringResource(Res.string.btn_confirm),
            onConfirm = {
                @Suppress("AssignedValueIsNeverRead")
                showCometsError = false
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showCometsError = false
            }
        )
    }
}


@Composable
private fun localizedCatalogCategory(category: String): String = when (category) {
    "Asteroids" -> stringResource(Res.string.asteroids_catalog_title)
    "Comets" -> stringResource(Res.string.comets_catalog_title)
    "Messier" -> stringResource(Res.string.cat_messier)
    "NGC" -> stringResource(Res.string.cat_ngc)
    "IC" -> stringResource(Res.string.cat_ic)
    "Barnard" -> stringResource(Res.string.cat_barnard)
    "Caldwell" -> stringResource(Res.string.cat_caldwell)
    "Collinder" -> stringResource(Res.string.cat_collinder)
    "RASC Doubles" -> stringResource(Res.string.cat_rasc_doubles)
    "SAC Asterisms" -> stringResource(Res.string.cat_sac_asterisms)
    "All Objects" -> stringResource(Res.string.cat_all_objects)
    "Nearest Objects" -> stringResource(Res.string.cat_nearest_objects)
    "Solar System" -> stringResource(Res.string.cat_solar_system)
    "Bright Stars" -> stringResource(Res.string.cat_bright_stars)
    else -> category
}

/**
 * Returns the screen-space bearing in degrees (0° = up / higher altitude, clockwise) from
 * `(az1, alt1)` to `(az2, alt2)`, foreshortening azimuth deltas by `cos(alt1)`.
 */
fun targetBearingAltAz(az1: Double, alt1: Double, az2: Double, alt2: Double): Double {
    var dAz = az2 - az1
    while (dAz > 180.0) dAz -= 360.0
    while (dAz < -180.0) dAz += 360.0

    val dAlt = alt2 - alt1
    val x = dAz * cos(Math.toRadians(alt1))

    var bearing = Math.toDegrees(atan2(x, dAlt))
    if (bearing < 0) bearing += 360.0
    return bearing
}

enum class CoordinateField {
    RA_H, RA_M, RA_S, DEC_D, DEC_M, DEC_S
}

/** Manual RA (HMS) / Dec (DMS) coordinate entry dialog supporting J2000 and current-epoch ("Now") input. */
@Composable
fun GotoRaDecPopup(
    onDismiss: () -> Unit,
    onGoTo: (Double, Double, Double) -> Unit
) {
    var epochIsJ2000 by remember { mutableStateOf(true) }

    var raH by remember { mutableStateOf("") }
    var raM by remember { mutableStateOf("") }
    var raS by remember { mutableStateOf("") }

    var decSign by remember { mutableStateOf("+") }
    var decD by remember { mutableStateOf("") }
    var decM by remember { mutableStateOf("") }
    var decS by remember { mutableStateOf("") }

    var focusedField by remember { mutableStateOf(CoordinateField.RA_H) }

    InfoPopup(
        title = stringResource(Res.string.title_goto_ra_dec),
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Epoch Toggle
            Text(
                stringResource(Res.string.label_epoch),
                style = MaterialTheme.typography.labelLarge
            )
            val extColors = LocalOliveExtendedColors.current
            Row(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 12.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .clickable { epochIsJ2000 = true }
                        .background(if (epochIsJ2000) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(Res.string.label_j2000),
                        color = if (epochIsJ2000) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable { epochIsJ2000 = false }
                        .background(if (!epochIsJ2000) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(Res.string.label_now),
                        color = if (!epochIsJ2000) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // RA Input
            Text(stringResource(Res.string.label_ra), style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoordinateBox(
                    value = raH,
                    isFocused = focusedField == CoordinateField.RA_H,
                    suffix = "h",
                    onClick = { focusedField = CoordinateField.RA_H })
                CoordinateBox(
                    value = raM,
                    isFocused = focusedField == CoordinateField.RA_M,
                    suffix = "m",
                    onClick = { focusedField = CoordinateField.RA_M })
                CoordinateBox(
                    value = raS,
                    isFocused = focusedField == CoordinateField.RA_S,
                    suffix = "s",
                    onClick = { focusedField = CoordinateField.RA_S })
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dec Input
            Text(stringResource(Res.string.label_dec), style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clickable { decSign = if (decSign == "+") "-" else "+" }
                        .border(1.dp, extColors.numpadKeyBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        decSign,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                CoordinateBox(
                    value = decD,
                    isFocused = focusedField == CoordinateField.DEC_D,
                    suffix = "°",
                    onClick = { focusedField = CoordinateField.DEC_D })
                CoordinateBox(
                    value = decM,
                    isFocused = focusedField == CoordinateField.DEC_M,
                    suffix = "'",
                    onClick = { focusedField = CoordinateField.DEC_M })
                CoordinateBox(
                    value = decS,
                    isFocused = focusedField == CoordinateField.DEC_S,
                    suffix = "\"",
                    onClick = { focusedField = CoordinateField.DEC_S })
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Numpad
            NightVisionNumpad(
                onKeyClick = { char ->
                    val validateAndAppend = { current: String, max: Int ->
                        val next = if (current == "0") char.toString() else current + char
                        if (next.length <= 2 && (next.toIntOrNull() ?: 0) <= max) next else current
                    }
                    when (focusedField) {
                        CoordinateField.RA_H -> raH = validateAndAppend(raH, 23)
                        CoordinateField.RA_M -> raM = validateAndAppend(raM, 59)
                        CoordinateField.RA_S -> raS = validateAndAppend(raS, 59)
                        CoordinateField.DEC_D -> {
                            decD = validateAndAppend(decD, 90)
                            if ((decD.toIntOrNull() ?: 0) == 90) {
                                decM = "0"
                                decS = "0"
                            }
                        }

                        CoordinateField.DEC_M -> if ((decD.toIntOrNull() ?: 0) != 90) decM =
                            validateAndAppend(decM, 59)

                        CoordinateField.DEC_S -> if ((decD.toIntOrNull() ?: 0) != 90) decS =
                            validateAndAppend(decS, 59)
                    }
                },
                onBackspace = {
                    when (focusedField) {
                        CoordinateField.RA_H -> if (raH.isNotEmpty()) raH = raH.dropLast(1)
                        CoordinateField.RA_M -> if (raM.isNotEmpty()) raM = raM.dropLast(1)
                        CoordinateField.RA_S -> if (raS.isNotEmpty()) raS = raS.dropLast(1)
                        CoordinateField.DEC_D -> if (decD.isNotEmpty()) decD = decD.dropLast(1)
                        CoordinateField.DEC_M -> if (decM.isNotEmpty()) decM = decM.dropLast(1)
                        CoordinateField.DEC_S -> if (decS.isNotEmpty()) decS = decS.dropLast(1)
                    }
                },
                onNext = {
                    focusedField = when (focusedField) {
                        CoordinateField.RA_H -> CoordinateField.RA_M
                        CoordinateField.RA_M -> CoordinateField.RA_S
                        CoordinateField.RA_S -> CoordinateField.DEC_D
                        CoordinateField.DEC_D -> CoordinateField.DEC_M
                        CoordinateField.DEC_M -> CoordinateField.DEC_S
                        CoordinateField.DEC_S -> CoordinateField.RA_H
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val h = raH.toDoubleOrNull() ?: 0.0
                    val m = raM.toDoubleOrNull() ?: 0.0
                    val s = raS.toDoubleOrNull() ?: 0.0
                    val raDeg = (h + m / 60.0 + s / 3600.0) * 15.0

                    val dd = decD.toDoubleOrNull() ?: 0.0
                    val dm = decM.toDoubleOrNull() ?: 0.0
                    val ds = decS.toDoubleOrNull() ?: 0.0
                    var decDeg = dd + dm / 60.0 + ds / 3600.0
                    if (decSign == "-") {
                        decDeg = -decDeg
                    }

                    val epoch = if (epochIsJ2000) {
                        2000.0
                    } else {
                        val now =
                            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                        val year = now.year
                        val dayOfYear = now.dayOfYear
                        val isLeapYear = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
                        val daysInYear = if (isLeapYear) 366 else 365
                        val epochDecimal = year + (dayOfYear.toDouble() / daysInYear.toDouble())
                        (epochDecimal * 10.0).roundToInt() / 10.0
                    }

                    onGoTo(raDeg, decDeg, epoch)
                }
            ) {
                Icon(Default.GpsFixed, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.btn_goto))
            }
        }
    }


}

package com.oakamil.olive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons.AutoMirrored.Filled
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import kotlin.math.sin

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
    var selectedCategory by viewModel::catalogSelectedCategory
    var sortBy by viewModel::catalogSortBy
    var magLimit by viewModel::catalogMagLimit
    var typeFilters by viewModel::catalogTypeFilters
    var visibilityFilter by viewModel::catalogVisibilityFilter

    val catalogTypeOptions = listOf(
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
        remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentRaState.value = if (viewModel.isEngineRunning) viewModel.engineStats.ra else null
            currentDecState.value =
                if (viewModel.isEngineRunning) viewModel.engineStats.dec else null
            currentTimeMsState.longValue = Clock.System.now().toEpochMilliseconds()
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
            val timeMs = Clock.System.now().toEpochMilliseconds()
            val checkVisibility =
                activeVisibility == "Above Horizon" && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0

            var lstRad = 0.0
            var sinLat = 0.0
            var cosLat = 0.0
            if (lat != null && lon != null) {
                val daysSinceJ2000 = (timeMs - 946728000000.0) / 86400000.0
                var gmst = (280.46061837 + 360.98564736629 * daysSinceJ2000) % 360.0
                if (gmst < 0) gmst += 360.0
                var lst = (gmst + lon) % 360.0
                if (lst < 0) lst += 360.0
                lstRad = Math.toRadians(lst)
                val latRad = Math.toRadians(lat)
                sinLat = sin(latRad)
                cosLat = cos(latRad)
            }

            val isAllTypes = activeTypeFilters.size >= catalogTypeOptions.size
            val allPlanets =
                if (category == "Solar System" || category == "All Objects" || category == "Nearest Objects") {
                    viewModel.getPlanets(lat, lon)
                } else emptyList()
            val filteredPlanets = allPlanets.filter { p ->
                var passes = true
                if (checkVisibility) {
                    val altCondition =
                        sin(p.dec * Math.PI / 180.0) * sinLat + cos(
                            p.dec * Math.PI / 180.0
                        ) * cosLat * cos(lstRad - p.ra * Math.PI / 180.0)
                    if (altCondition <= 0) passes = false
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
                        offset = offset
                    )
                }

            if (offset == 0 && (category == "Solar System" || category == "Asteroids" || category == "Comets" || category == "All Objects" || category == "Nearest Objects")) {
                val allAsteroids =
                    if (category == "Asteroids" || category == "All Objects" || category == "Nearest Objects") {
                        viewModel.getAsteroids(lat, lon)
                    } else emptyList()
                val filteredAsteroids = allAsteroids.filter { p ->
                    var passes = true
                    if (checkVisibility) {
                        val altCondition =
                            sin(p.dec * Math.PI / 180.0) * sinLat + cos(
                                p.dec * Math.PI / 180.0
                            ) * cosLat * cos(lstRad - p.ra * Math.PI / 180.0)
                        if (altCondition <= 0) passes = false
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
                        viewModel.getComets(lat, lon)
                    } else emptyList()
                val filteredComets = allComets.filter { p ->
                    var passes = true
                    if (checkVisibility) {
                        val altCondition =
                            sin(p.dec * Math.PI / 180.0) * sinLat + cos(
                                p.dec * Math.PI / 180.0
                            ) * cosLat * cos(lstRad - p.ra * Math.PI / 180.0)
                        if (altCondition <= 0) passes = false
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
            showKeyboard = false
            focusManager.clearFocus()
        } else {
            onDismiss()
        }
    }

    val onUserInteraction = LocalOnUserInteraction.current

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
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            onUserInteraction()
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background
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
                val windowController = LocalPlatformWindowController.current
                val isLandscape = windowController.isLandscape

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
                                IconButton(onClick = { showSettingsMenu = true }) {
                                    Icon(
                                        Default.Settings,
                                        contentDescription = stringResource(Res.string.cd_settings)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSettingsMenu,
                                    onDismissRequest = { showSettingsMenu = false }
                                ) {
                                    Text(
                                        text = stringResource(Res.string.filter_sort_by),
                                        modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val sortOptions = listOf(
                                        "catalog ID" to Res.string.sort_catalog_id,
                                        "name" to Res.string.sort_name,
                                        "brightness" to Res.string.sort_brightness,
                                        "distance" to Res.string.sort_distance
                                    )
                                    sortOptions.forEach { (key, labelRes) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(labelRes)) },
                                            onClick = {
                                                sortBy = key
                                            },
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
                                            onClick = {
                                                visibilityFilter = key
                                            },
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
                                            onClick = {
                                                magLimit = value
                                            },
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
                                    HorizontalDivider()
                                    Text(
                                        text = stringResource(Res.string.filter_object_type),
                                        modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val allTypesSelected =
                                        typeFilters.size >= catalogTypeOptions.size
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
                                            typeFilters = if (allTypesSelected) {
                                                emptySet()
                                            } else {
                                                catalogTypeOptions.toSet()
                                            }
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
                                    catalogTypeOptions.forEach { type ->
                                        val isSelected = type in typeFilters
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                val (iVec, iCol) = getIconAndColorForType(
                                                    type,
                                                    extColors
                                                )
                                                Icon(
                                                    iVec,
                                                    contentDescription = null,
                                                    tint = iCol
                                                )
                                            },
                                            text = {
                                                Text(getLocalizedObjectType(type))
                                            },
                                            onClick = {
                                                typeFilters = if (isSelected) {
                                                    typeFilters - type
                                                } else {
                                                    typeFilters + type
                                                }
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
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Default.Close,
                                    contentDescription = stringResource(Res.string.cd_close)
                                )
                            }
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {},
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(Res.string.placeholder_search)) },
                                leadingIcon = {
                                    Icon(
                                        Default.Search,
                                        contentDescription = null
                                    )
                                },
                                singleLine = true,
                                readOnly = true
                            )
                            Spacer(
                                modifier = Modifier
                                    .matchParentSize()
                                    .padding(end = 48.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        showKeyboard = true
                                    }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 4.dp)
                                ) {
                                    Icon(
                                        Default.Close,
                                        contentDescription = stringResource(Res.string.cd_clear_search),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

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
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isThisCatDownloading) {
                                                val pct = ((hqState.completedTiles * 100) /
                                                        hqState.totalTiles.coerceAtLeast(1)).coerceIn(
                                                    0,
                                                    100
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    CircularProgressIndicator(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = stringResource(
                                                            Res.string.hips_downloading_inline,
                                                            pct
                                                        ),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    ListHeaderTextButton(
                                                        label = stringResource(Res.string.hips_action_show),
                                                        onClick = {
                                                            viewModel.showHqDownloadProgress = true
                                                        }
                                                    )
                                                }
                                            } else if (missingHq != null) {
                                                val estMb = HiPSDownloadManager.formatMbValue(
                                                    HiPSDownloadManager.estimatedTilesBytes(
                                                        missingHq
                                                    )
                                                )
                                                Button(
                                                    onClick = {
                                                        viewModel.requestCatalogHqDownload(
                                                            categoryTitle = catTitle,
                                                            shortCode = currentShortCode,
                                                            missingKeys = missingHq
                                                        )
                                                    }
                                                ) {
                                                    Text(
                                                        stringResource(
                                                            Res.string.btn_download_catalog_images,
                                                            estMb
                                                        )
                                                    )
                                                }
                                            }
                                        }
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

                                ListItem(
                                    modifier = Modifier.clickable { selectedCatalogObj = obj },
                                    colors = if (isBelowHorizon) ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ) else ListItemDefaults.colors(),
                                    headlineContent = { Text(obj.displayName) },
                                    supportingContent = {
                                        val localizedType =
                                            getLocalizedObjectType(obj.objectType)
                                        val magText = when {
                                            obj.magnitude != null -> stringResource(
                                                Res.string.format_mag,
                                                String.format("%.1f", obj.magnitude)
                                            )

                                            obj.catalogLabel == "Planet" -> ""
                                            else -> stringResource(
                                                Res.string.format_mag,
                                                stringResource(Res.string.value_not_available)
                                            )
                                        }
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
                                            if (obj.designation.isNotBlank() && obj.displayName != obj.designation) {
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
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isBelowHorizon) {
                                                Text(
                                                    stringResource(Res.string.status_below_horizon),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .padding(end = 8.dp, start = 4.dp)
                                                )
                                            } else {
                                                val hasSolve =
                                                    viewModel.isEngineRunning && telescopeAltAz != null && currentRa != null && currentDec != null && lat != null && lon != null
                                                if (hasSolve) {
                                                    val objAltAz =
                                                        AstrometryUtils.calculateAltAz(
                                                            obj.ra,
                                                            obj.dec,
                                                            lat,
                                                            lon,
                                                            currentTimeMs
                                                        )

                                                    val distance = AstrometryUtils.angularDistance(
                                                        currentRa,
                                                        currentDec, obj.ra, obj.dec
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
                                                            bottom = 4.dp,
                                                        )
                                                    ) {
                                                        Icon(
                                                            imageVector = Default.ArrowUpward,
                                                            contentDescription = stringResource(
                                                                Res.string.cd_direction
                                                            ),
                                                            modifier = Modifier
                                                                .rotate(bearing.toFloat())
                                                                .size(24.dp)
                                                        )
                                                        Text(
                                                            text = stringResource(
                                                                Res.string.format_degree,
                                                                String.format("%.1f", distance),
                                                            ),
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    }
                                                }
                                            }

                                            IconBarItem(
                                                icon = Default.CenterFocusStrong,
                                                label = stringResource(Res.string.crop_center),
                                                onClick = {
                                                    onCenter(obj.ra, obj.dec)
                                                }
                                            )

                                            if (!isBelowHorizon && viewModel.isEngineRunning) {
                                                IconBarItem(
                                                    icon = Default.GpsFixed,
                                                    label = stringResource(Res.string.btn_goto),
                                                    onClick = {
                                                        onGoTo(
                                                            obj.ra,
                                                            obj.dec,
                                                            2000.0
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
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


                    val keyboardContent: @Composable (Modifier) -> Unit = { modifier ->
                        val sideButtons = @Composable {
                            Column(
                                modifier = Modifier.fillMaxHeight(),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                IconButton(onClick = {
                                    showKeyboard = false
                                    focusManager.clearFocus()
                                }) {
                                    Icon(
                                        Default.Close,
                                        contentDescription = stringResource(Res.string.cd_close_keyboard),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.landscapeKeyboardOnRight =
                                        !viewModel.landscapeKeyboardOnRight
                                }) {
                                    Icon(
                                        imageVector = if (viewModel.landscapeKeyboardOnRight) Filled.KeyboardArrowLeft else Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(Res.string.cd_swap_keyboard_side),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        if (isLandscape) {
                            Row(modifier = modifier) {
                                if (viewModel.landscapeKeyboardOnRight) {
                                    sideButtons()
                                }
                                NightVisionKeyboard(
                                    isLandscape = true,
                                    modifier = Modifier.weight(1f),
                                    onKeyClick = { char -> searchQuery += char },
                                    onSpace = { searchQuery += " " },
                                    onBackspace = {
                                        if (searchQuery.isNotEmpty()) searchQuery =
                                            searchQuery.dropLast(1)
                                    },
                                )
                                if (!viewModel.landscapeKeyboardOnRight) {
                                    sideButtons()
                                }
                            }
                        } else {
                            Column(modifier = modifier) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    IconButton(onClick = {
                                        showKeyboard = false
                                        focusManager.clearFocus()
                                    }) {
                                        Icon(
                                            Default.Close,
                                            contentDescription = stringResource(Res.string.cd_close_keyboard),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                NightVisionKeyboard(
                                    isLandscape = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    onKeyClick = { char -> searchQuery += char },
                                    onSpace = { searchQuery += " " },
                                    onBackspace = {
                                        if (searchQuery.isNotEmpty()) searchQuery =
                                            searchQuery.dropLast(1)
                                    },
                                )
                            }
                        }
                    }

                    if (isLandscape && showKeyboard) {
                        Row(modifier = Modifier.weight(1f)) {
                            if (viewModel.landscapeKeyboardOnRight) {
                                listContent(Modifier.weight(1f))
                                keyboardContent(Modifier.weight(1f))
                            } else {
                                keyboardContent(Modifier.weight(1f))
                                listContent(Modifier.weight(1f))
                            }
                        }
                    } else {
                        listContent(Modifier.weight(1f))
                        if (showKeyboard) {
                            keyboardContent(Modifier.fillMaxWidth())
                        }
                    }
                }
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

@Composable
private fun ListHeaderTextButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

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

@Composable
fun NightVisionKeyboard(
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    onKeyClick: (Char) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
) {
    val keys = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("␣", "z", "x", "c", "v", "b", "n", "m", "⌫")
    )

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .pointerInput(Unit) {
                // Consume taps inside keyboard so they don't propagate to the parent and dismiss it
                detectTapGestures { }
            }
    ) {
        keys.forEach { row ->
            val rowModifier = if (isLandscape) Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .weight(1f) else Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.Center
            ) {
                val paddingWeight = (10 - row.size) / 2f
                if (paddingWeight > 0f) {
                    Spacer(modifier = Modifier.weight(paddingWeight))
                }

                row.forEach { key ->
                    NightVisionKey(
                        text = key,
                        isLandscape = isLandscape,
                        onClick = {
                            when (key) {
                                "␣" -> onSpace()
                                "⌫" -> onBackspace()
                                else -> onKeyClick(key[0])
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
    }
}

@Composable
fun NightVisionKey(
    modifier: Modifier = Modifier,
    text: String,
    isLandscape: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressedState by interactionSource.collectIsPressedAsState()

    val bgColor =
        if (isPressedState) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val textColor =
        if (isPressedState) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

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


enum class CoordinateField {
    RA_H, RA_M, RA_S, DEC_D, DEC_M, DEC_S
}

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

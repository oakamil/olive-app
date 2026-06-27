package com.oakamil.olive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.AutoMirrored.Filled
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyCatalogDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onGoTo: (Double, Double) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var showKeyboard by remember { mutableStateOf(false) }
    var searchQuery by viewModel::catalogSearchQuery
    var selectedCategory by viewModel::catalogSelectedCategory
    var sortBy by viewModel::catalogSortBy
    var magLimit by viewModel::catalogMagLimit
    var typeFilter by viewModel::catalogTypeFilter
    var visibilityFilter by viewModel::catalogVisibilityFilter

    var showSettingsMenu by remember { mutableStateOf(false) }

    var searchResults by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }

    val categories = listOf(
        "Nearest Objects", "All Objects", "Solar System", "Bright Stars",
        "Messier", "Caldwell", "Collinder", "NGC", "IC",
        "Herschel 400", "Barnard", "Sharpless", "Abell", "Arp",
        "SAC Asterisms", "SAC Doubles", "SAC Red Stars", "TAAS 200",
        "Harris", "RASC Doubles", "EGC"
    )

    val categoryToShortCode = mapOf(
        "Messier" to "M",
        "Caldwell" to "C",
        "Collinder" to "Col",
        "NGC" to "NGC",
        "IC" to "IC",
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

    val currentRa = remember { viewModel.engineStats.ra }
    val currentDec = remember { viewModel.engineStats.dec }

    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val catalogDescriptions = remember { viewModel.catalogDb.getCatalogDescriptions() }
    var showInfoForCategory by remember { mutableStateOf<String?>(null) }

    val fetchResults = { offset: Int ->
        if (!isLoading) {
            if (offset == 0) {
                searchResults = emptyList()
            }
            isLoading = true
            coroutineScope.launch {
                val results = withContext(Dispatchers.IO) {
                    val lat = viewModel.currentLat
                    val lon = viewModel.currentLon
                    val timeMs = System.currentTimeMillis()
                    val checkVisibility =
                        visibilityFilter == "Above Horizon" && lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0

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
                        sinLat = kotlin.math.sin(latRad)
                        cosLat = kotlin.math.cos(latRad)
                    }

                    val allPlanets = viewModel.getPlanets()
                    val filteredPlanets = allPlanets.filter { p ->
                        var passes = true
                        if (checkVisibility) {
                            val altCondition =
                                kotlin.math.sin(p.dec * Math.PI / 180.0) * sinLat + kotlin.math.cos(
                                    p.dec * Math.PI / 180.0
                                ) * cosLat * kotlin.math.cos(lstRad - p.ra * Math.PI / 180.0)
                            if (altCondition <= 0) passes = false
                        }
                        if (typeFilter != "All Types" && typeFilter != "Planet") passes = false
                        if (searchQuery.isNotBlank() && !p.displayName.contains(
                                searchQuery,
                                ignoreCase = true
                            )
                        ) passes = false
                        val limit = magLimit
                        if (limit != null && p.magnitude != null && p.magnitude > limit) passes =
                            false
                        if (selectedCategory == "Nearest Objects" && currentRa != null && currentDec != null) {
                            if (angularDistance(currentRa, currentDec, p.ra, p.dec) > 10.0) passes =
                                false
                        }
                        passes
                    }

                    val sqlResults = if (selectedCategory == "Solar System") {
                        emptyList()
                    } else {
                        val filter = when (selectedCategory) {
                            "Nearest Objects" -> "Nearest Objects"
                            "All Objects" -> null
                            else -> categoryToShortCode[selectedCategory]
                        }
                        viewModel.catalogDb.searchObjects(
                            searchQuery = searchQuery,
                            catalogFilter = filter,
                            sortBy = sortBy,
                            magLimit = magLimit,
                            typeFilter = typeFilter,
                            visibilityFilter = visibilityFilter,
                            lat = viewModel.currentLat,
                            lon = viewModel.currentLon,
                            targetRa = currentRa,
                            targetDec = currentDec,
                            limit = 300,
                            offset = offset
                        )
                    }

                    if (offset == 0 && (selectedCategory == "Solar System" || selectedCategory == "All Objects" || selectedCategory == "Nearest Objects")) {
                        val combinedList = (filteredPlanets + sqlResults).toMutableList()
                        when (sortBy) {
                            "distance" -> {
                                if (currentRa != null && currentDec != null) {
                                    combinedList.sortBy {
                                        angularDistance(
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

                            "brightness" -> {
                                combinedList.sortWith(compareBy<CatalogObject> { it.magnitude == null }.thenBy { it.magnitude })
                            }
                        }
                        combinedList
                    } else {
                        sqlResults
                    }
                }

                if (offset == 0) {
                    searchResults = results
                    if (results.isNotEmpty()) {
                        listState.scrollToItem(0)
                    }
                } else {
                    searchResults = searchResults + results
                }
                hasMore = results.size == 300
                isLoading = false
            }
        }
    }

    LaunchedEffect(searchQuery, selectedCategory, sortBy, magLimit, typeFilter, visibilityFilter) {
        // Cancel ongoing coroutine calls if needed by checking a token, or let it override. 
        // We will just call fetchResults(0) whenever a filter changes.
        fetchResults(0)
    }

    BackHandler {
        if (showKeyboard) {
            showKeyboard = false
            focusManager.clearFocus()
        } else {
            onDismiss()
        }
    }

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
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
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
                    title = { Text(stringResource(R.string.title_sky_catalog)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_close)
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showSettingsMenu = true }) {
                                Icon(
                                    Default.Settings,
                                    contentDescription = stringResource(R.string.cd_settings)
                                )
                            }
                            DropdownMenu(
                                expanded = showSettingsMenu,
                                onDismissRequest = { showSettingsMenu = false }
                            ) {
                                Text(
                                    text = stringResource(R.string.filter_sort_by),
                                    modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                val sortOptions =
                                    listOf("catalog ID", "name", "brightness", "distance")
                                sortOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.replaceFirstChar { it.uppercase() }) },
                                        onClick = {
                                            sortBy = option
                                        },
                                        trailingIcon = {
                                            if (sortBy == option) {
                                                Icon(
                                                    Default.Check,
                                                    contentDescription = "Selected",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    text = stringResource(R.string.filter_visibility),
                                    modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                val visibilityOptions = listOf("Above Horizon", "All Objects")
                                visibilityOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            visibilityFilter = option
                                        },
                                        trailingIcon = {
                                            if (visibilityFilter == option) {
                                                Icon(
                                                    Default.Check,
                                                    contentDescription = "Selected",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    text = stringResource(R.string.filter_magnitude_limit),
                                    modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                val magOptions = listOf(
                                    "No Limit" to null,
                                    "Naked Eye (< 6)" to 6.0,
                                    "Binoculars (< 9)" to 9.0,
                                    "Small Scope (< 12)" to 12.0,
                                    "Large Scope (< 15)" to 15.0
                                )
                                magOptions.forEach { (label, value) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            magLimit = value
                                        },
                                        trailingIcon = {
                                            if (magLimit == value) {
                                                Icon(
                                                    Default.Check,
                                                    contentDescription = "Selected",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    text = stringResource(R.string.filter_object_type),
                                    modifier = Modifier.padding(12.dp, 8.dp, 12.dp, 4.dp),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                val typeOptions = listOf(
                                    "All Types",
                                    "Asterism",
                                    "Cluster + Nebula",
                                    "Dark Nebula",
                                    "Double Star",
                                    "Galaxy",
                                    "Globular Cluster",
                                    "Nebula",
                                    "Open Cluster",
                                    "Planetary Nebula",
                                    "Star"
                                )
                                typeOptions.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type) },
                                        onClick = {
                                            typeFilter = type
                                        },
                                        trailingIcon = {
                                            if (typeFilter == type) {
                                                Icon(
                                                    Default.Check,
                                                    contentDescription = "Selected",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.placeholder_search)) },
                        leadingIcon = { Icon(Default.Search, contentDescription = null) },
                        singleLine = true,
                        readOnly = true
                    )
                    Spacer(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                showKeyboard = true
                            }
                    )
                }

                ScrollableTabRow(
                    selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0),
                    edgePadding = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    categories.forEach { category ->
                        Tab(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(category)
                                    val shortCode = categoryToShortCode[category]
                                    val desc = catalogDescriptions[shortCode]
                                    if (desc != null && desc.isNotBlank()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Default.Info,
                                            contentDescription = "Info",
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable { showInfoForCategory = category }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }


                val pagerState = androidx.compose.foundation.pager.rememberPagerState(
                    initialPage = categories.indexOf(selectedCategory).coerceAtLeast(0),
                    pageCount = { categories.size }
                )

                LaunchedEffect(pagerState.currentPage) {
                    val targetCategory = categories.getOrNull(pagerState.currentPage)
                    if (targetCategory != null && targetCategory != selectedCategory) {
                        selectedCategory = targetCategory
                    }
                }

                LaunchedEffect(selectedCategory) {
                    val targetIndex = categories.indexOf(selectedCategory)
                    if (targetIndex >= 0 && pagerState.currentPage != targetIndex) {
                        pagerState.animateScrollToPage(targetIndex)
                    }
                }

                val isLandscape =
                    androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

                val listContent: @Composable (Modifier) -> Unit = { modifier ->
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = pagerState,
                        modifier = modifier,
                        verticalAlignment = Alignment.Top
                    ) { page ->
                        val pageListState =
                            if (page == categories.indexOf(selectedCategory)) listState else androidx.compose.foundation.lazy.rememberLazyListState()
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = pageListState
                        ) {
                            items(
                                searchResults,
                                key = { it.id.toString() + it.catalogEntry }) { obj ->
                                val lat = viewModel.currentLat
                                val lon = viewModel.currentLon
                                val timeMs = System.currentTimeMillis()
                                var isBelowHorizon = false
                                if (visibilityFilter == "All Objects" && lat != null && lon != null) {
                                    val objAltAz = raDecToAltAz(obj.ra, obj.dec, lat, lon, timeMs)
                                    if (objAltAz.alt <= 0) {
                                        isBelowHorizon = true
                                    }
                                }

                                ListItem(
                                    colors = if (isBelowHorizon) ListItemDefaults.colors(
                                        containerColor = Color(
                                            0xFF4A1010
                                        )
                                    ) else ListItemDefaults.colors(),
                                    headlineContent = { Text(obj.displayName) },
                                    supportingContent = {
                                        val magText = if (obj.magnitude != null) "Mag: ${
                                            String.format(
                                                Locale.getDefault(),
                                                "%.1f",
                                                obj.magnitude
                                            )
                                        }" else if (obj.catalogLabel == "Planet") "" else "Mag: N/A"
                                        val text = listOfNotNull(
                                            obj.designation.takeIf { it.isNotBlank() },
                                            obj.objectType,
                                            magText.takeIf { it.isNotBlank() }).joinToString(" | ")
                                        Text(text)
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isBelowHorizon) {
                                                Text(
                                                    stringResource(R.string.status_below_horizon),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.Red,
                                                    modifier = Modifier.padding(end = 16.dp)
                                                )
                                            } else {
                                                if (currentRa != null && currentDec != null && lat != null && lon != null) {
                                                    val telescopeAltAz = raDecToAltAz(
                                                        currentRa,
                                                        currentDec, lat, lon, timeMs
                                                    )
                                                    val objAltAz =
                                                        raDecToAltAz(
                                                            obj.ra,
                                                            obj.dec,
                                                            lat,
                                                            lon,
                                                            timeMs
                                                        )

                                                    val distance = angularDistance(
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
                                                        modifier = Modifier.padding(end = 8.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Default.ArrowUpward,
                                                            contentDescription = stringResource(R.string.cd_direction),
                                                            modifier = Modifier
                                                                .rotate(bearing.toFloat())
                                                                .size(24.dp)
                                                        )
                                                        Text(
                                                            String.format(
                                                                Locale.getDefault(),
                                                                "%.1f°",
                                                                distance
                                                            ),
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
                                                }
                                                Button(onClick = { onGoTo(obj.ra, obj.dec) }) {
                                                    Text(stringResource(R.string.btn_goto))
                                                }
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
                                            modifier = Modifier.fillParentMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                } else {
                                    item {
                                        Box(
                                            modifier = Modifier.fillParentMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(stringResource(R.string.status_no_objects))
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
                                            Button(onClick = { fetchResults(searchResults.size) }) {
                                                Text(stringResource(R.string.btn_load_more))
                                            }
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
                                    contentDescription = "Close Keyboard",
                                    tint = Color.Red
                                )
                            }
                            IconButton(onClick = {
                                viewModel.landscapeKeyboardOnRight =
                                    !viewModel.landscapeKeyboardOnRight
                            }) {
                                Icon(
                                    imageVector = if (viewModel.landscapeKeyboardOnRight) Filled.KeyboardArrowLeft else Filled.KeyboardArrowRight,
                                    contentDescription = "Swap keyboard side",
                                    tint = Color.Red
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
                                        contentDescription = "Close Keyboard",
                                        tint = Color.Red
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

    if (showInfoForCategory != null) {
        val titleText = showInfoForCategory!!
        InfoPopup(
            title = titleText,
            onDismiss = { showInfoForCategory = null }
        ) {
            val shortCode = categoryToShortCode[titleText]
            val desc = catalogDescriptions[shortCode] ?: ""
            Text(desc)
        }
    }
}

data class AltAz(val alt: Double, val az: Double)

fun raDecToAltAz(ra: Double, dec: Double, lat: Double, lon: Double, timeMs: Long): AltAz {
    val daysSinceJ2000 = (timeMs - 946728000000.0) / 86400000.0
    var gmst = (280.46061837 + 360.98564736629 * daysSinceJ2000) % 360.0
    if (gmst < 0) gmst += 360.0
    var lst = (gmst + lon) % 360.0
    if (lst < 0) lst += 360.0

    val ha = Math.toRadians((lst - ra) % 360.0)
    val decRad = Math.toRadians(dec)
    val latRad = Math.toRadians(lat)

    val sinAlt =
        kotlin.math.sin(decRad) * kotlin.math.sin(latRad) + kotlin.math.cos(decRad) * kotlin.math.cos(
            latRad
        ) * kotlin.math.cos(ha)
    val alt = Math.toDegrees(kotlin.math.asin(sinAlt.coerceIn(-1.0, 1.0)))

    val y = -kotlin.math.sin(ha) * kotlin.math.cos(decRad)
    val x =
        kotlin.math.cos(latRad) * kotlin.math.sin(decRad) - kotlin.math.sin(latRad) * kotlin.math.cos(
            decRad
        ) * kotlin.math.cos(ha)
    var az = Math.toDegrees(kotlin.math.atan2(y, x))
    if (az < 0) az += 360.0

    return AltAz(alt, az)
}

fun angularDistance(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
    val d1 = Math.toRadians(dec1)
    val d2 = Math.toRadians(dec2)
    val dRa = Math.toRadians(ra2 - ra1)
    val c =
        kotlin.math.sin(d1) * kotlin.math.sin(d2) + kotlin.math.cos(d1) * kotlin.math.cos(d2) * kotlin.math.cos(
            dRa
        )
    return Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
}

fun targetBearingAltAz(az1: Double, alt1: Double, az2: Double, alt2: Double): Double {
    val a1 = Math.toRadians(alt1)
    val a2 = Math.toRadians(alt2)
    val dAz = Math.toRadians(az2 - az1)

    val y = kotlin.math.sin(dAz) * kotlin.math.cos(a2)
    val x =
        kotlin.math.cos(a1) * kotlin.math.sin(a2) - kotlin.math.sin(a1) * kotlin.math.cos(a2) * kotlin.math.cos(
            dAz
        )

    var bearing = Math.toDegrees(kotlin.math.atan2(y, x))
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
            .background(Color.Black)
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

    val bgColor = if (isPressedState) Color.Red else Color.Black
    val textColor = if (isPressedState) Color.Black else Color.Red

    val boxModifier = if (isLandscape) modifier.fillMaxHeight() else modifier.height(36.dp)
    Box(
        modifier = boxModifier
            .border(1.dp, Color.Red, RoundedCornerShape(4.dp))
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

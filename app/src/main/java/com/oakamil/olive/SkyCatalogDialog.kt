package com.oakamil.olive

import android.content.res.Configuration
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift.Companion.Superscript
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyCatalogDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onGoTo: (Double, Double, Double) -> Unit,
) {
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
        "Double Star",
        "Galaxy",
        "Globular Cluster",
        "Nebula",
        "Open Cluster",
        "Planet",
        "Planetary Nebula",
        "Star"
    )

    var showSettingsMenu by remember { mutableStateOf(false) }
    var cometsRefreshTrigger by remember { mutableIntStateOf(0) }
    var isRefreshingComets by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var searchResults by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var showRaDecForObj by remember { mutableStateOf<CatalogObject?>(null) }
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

    val currentRaState = remember { mutableStateOf(viewModel.engineStats.ra) }
    val currentDecState = remember { mutableStateOf(viewModel.engineStats.dec) }
    val currentTimeMsState = remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            val newRa = viewModel.engineStats.ra
            val newDec = viewModel.engineStats.dec
            if (newRa != null && newDec != null) {
                currentRaState.value = newRa
                currentDecState.value = newDec
            }
            currentTimeMsState.longValue = System.currentTimeMillis()
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
                        sinLat = sin(latRad)
                        cosLat = cos(latRad)
                    }

                    val isAllTypes = typeFilters.size >= catalogTypeOptions.size
                    val allPlanets =
                        if (selectedCategory == "Solar System" || selectedCategory == "All Objects" || selectedCategory == "Nearest Objects") viewModel.getPlanets() else emptyList()
                    val filteredPlanets = allPlanets.filter { p ->
                        var passes = true
                        if (checkVisibility) {
                            val altCondition =
                                sin(p.dec * Math.PI / 180.0) * sinLat + cos(
                                    p.dec * Math.PI / 180.0
                                ) * cosLat * cos(lstRad - p.ra * Math.PI / 180.0)
                            if (altCondition <= 0) passes = false
                        }
                        if (!isAllTypes && "Planet" !in typeFilters && p.objectType !in typeFilters) passes =
                            false
                        if (searchQuery.isNotBlank() && !p.displayName.contains(
                                searchQuery,
                                ignoreCase = true
                            )
                        ) passes = false
                        val limit = magLimit
                        if (limit != null && (p.magnitude == null || p.magnitude > limit)) passes =
                            false
                        if (selectedCategory == "Nearest Objects" && currentRa != null && currentDec != null) {
                            if (angularDistance(currentRa, currentDec, p.ra, p.dec) > 10.0) passes =
                                false
                        }
                        passes
                    }

                    val sqlResults =
                        if (selectedCategory == "Solar System" || selectedCategory == "Asteroids" || selectedCategory == "Comets") {
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
                                typeFilters = if (isAllTypes) null else typeFilters,
                                visibilityFilter = visibilityFilter,
                                lat = viewModel.currentLat,
                                lon = viewModel.currentLon,
                                targetRa = currentRa,
                                targetDec = currentDec,
                                limit = 300,
                                offset = offset
                            )
                        }

                    if (offset == 0 && (selectedCategory == "Solar System" || selectedCategory == "Asteroids" || selectedCategory == "Comets" || selectedCategory == "All Objects" || selectedCategory == "Nearest Objects")) {
                        val allAsteroids =
                            if (selectedCategory == "Asteroids" || selectedCategory == "All Objects" || selectedCategory == "Nearest Objects") viewModel.getAsteroids() else emptyList()
                        val filteredAsteroids = allAsteroids.filter { p ->
                            var passes = true
                            if (checkVisibility) {
                                val altCondition =
                                    sin(p.dec * Math.PI / 180.0) * sinLat + cos(
                                        p.dec * Math.PI / 180.0
                                    ) * cosLat * cos(lstRad - p.ra * Math.PI / 180.0)
                                if (altCondition <= 0) passes = false
                            }
                            if (!isAllTypes && "Asteroid" !in typeFilters && p.objectType !in typeFilters) passes =
                                false
                            if (searchQuery.isNotBlank() && !p.displayName.contains(
                                    searchQuery,
                                    ignoreCase = true
                                )
                            ) passes = false
                            val limit = magLimit
                            if (limit != null && (p.magnitude == null || p.magnitude > limit)) passes =
                                false
                            if (selectedCategory == "Nearest Objects" && currentRa != null && currentDec != null) {
                                if (angularDistance(
                                        currentRa,
                                        currentDec,
                                        p.ra,
                                        p.dec
                                    ) > 10.0
                                ) passes =
                                    false
                            }
                            passes
                        }

                        val allComets =
                            if (selectedCategory == "Comets" || selectedCategory == "All Objects" || selectedCategory == "Nearest Objects") viewModel.getComets() else emptyList()
                        val filteredComets = allComets.filter { p ->
                            var passes = true
                            if (checkVisibility) {
                                val altCondition =
                                    sin(p.dec * Math.PI / 180.0) * sinLat + cos(
                                        p.dec * Math.PI / 180.0
                                    ) * cosLat * cos(lstRad - p.ra * Math.PI / 180.0)
                                if (altCondition <= 0) passes = false
                            }
                            if (!isAllTypes && "Comet" !in typeFilters && p.objectType !in typeFilters) passes =
                                false
                            if (searchQuery.isNotBlank() && !p.displayName.contains(
                                    searchQuery,
                                    ignoreCase = true
                                )
                            ) passes = false
                            val limit = magLimit
                            if (limit != null && (p.magnitude == null || p.magnitude > limit)) passes =
                                false
                            if (selectedCategory == "Nearest Objects" && currentRa != null && currentDec != null) {
                                if (angularDistance(
                                        currentRa,
                                        currentDec,
                                        p.ra,
                                        p.dec
                                    ) > 10.0
                                ) passes =
                                    false
                            }
                            passes
                        }

                        val combinedList =
                            (filteredPlanets + filteredAsteroids + filteredComets + sqlResults).toMutableList()
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

                if (offset == 0) {
                    searchResults = results
                    if (results.isNotEmpty()) {
                        activeListState?.scrollToItem(0)
                    }
                } else {
                    searchResults = searchResults + results
                }
                hasMore = results.size == 300
                isLoading = false
            }
        }
    }

    LaunchedEffect(
        searchQuery,
        selectedCategory,
        sortBy,
        magLimit,
        typeFilters,
        visibilityFilter,
        cometsRefreshTrigger
    ) {
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
                            stringResource(R.string.title_sky_catalog),
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
                                            Text(
                                                when (category) {
                                                    "Asteroids" -> stringResource(R.string.asteroids_catalog_title)
                                                    "Comets" -> stringResource(R.string.comets_catalog_title)
                                                    "Messier" -> stringResource(R.string.cat_messier)
                                                    "NGC" -> stringResource(R.string.cat_ngc)
                                                    "IC" -> stringResource(R.string.cat_ic)
                                                    "Barnard" -> stringResource(R.string.cat_barnard)
                                                    "Caldwell" -> stringResource(R.string.cat_caldwell)
                                                    "Collinder" -> stringResource(R.string.cat_collinder)
                                                    "RASC Doubles" -> stringResource(R.string.cat_rasc_doubles)
                                                    "SAC Asterisms" -> stringResource(R.string.cat_sac_asterisms)
                                                    "All Objects" -> stringResource(R.string.cat_all_objects)
                                                    "Nearest Objects" -> stringResource(R.string.cat_nearest_objects)
                                                    "Solar System" -> stringResource(R.string.cat_solar_system)
                                                    "Bright Stars" -> stringResource(R.string.cat_bright_stars)
                                                    else -> category
                                                }
                                            )
                                            val currentShortCode = categoryToShortCode[category]
                                            val currentDesc = catalogDescriptions[currentShortCode]
                                            if (currentDesc != null && currentDesc.isNotBlank()) {
                                                IconButton(onClick = {
                                                    showInfoForCategory = category
                                                }) {
                                                    Icon(
                                                        Default.Info,
                                                        contentDescription = stringResource(R.string.cd_info)
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
                                        selectedContainerColor = Color(0xFF4A1010),
                                        selectedTextColor = Color.Red,
                                        unselectedTextColor = Color.Red,
                                        selectedIconColor = Color.Red,
                                        unselectedIconColor = Color.Red
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
                                    showKeyboard = false
                                    focusManager.clearFocus()
                                }
                            }
                        }) {
                    TopAppBar(
                        title = { Text(stringResource(R.string.title_sky_catalog)) },
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(
                                    Default.Menu,
                                    contentDescription = stringResource(R.string.cd_open_catalogs_menu)
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
                                                        Default.RadioButtonChecked,
                                                        contentDescription = stringResource(R.string.cd_selected),
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
                                                        Default.RadioButtonChecked,
                                                        contentDescription = stringResource(R.string.cd_selected),
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
                                                        Default.RadioButtonChecked,
                                                        contentDescription = stringResource(R.string.cd_selected),
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
                                    val allTypesSelected =
                                        typeFilters.size >= catalogTypeOptions.size
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.type_all_types)) },
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
                                                    contentDescription = stringResource(R.string.cd_selected),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                    catalogTypeOptions.forEach { type ->
                                        val isSelected = type in typeFilters
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                val (iVec, iCol) = getIconAndColorForType(type)
                                                Icon(iVec, contentDescription = null, tint = iCol)
                                            },
                                            text = {
                                                Text(
                                                    when (type) {
                                                        "Star" -> stringResource(R.string.type_star)
                                                        "Galaxy" -> stringResource(R.string.type_galaxy)
                                                        "Nebula" -> stringResource(R.string.type_nebula)
                                                        "Cluster" -> stringResource(R.string.type_cluster)
                                                        "Planet" -> stringResource(R.string.type_planet)
                                                        "Asterism" -> stringResource(R.string.type_asterism)
                                                        "Cluster + Nebula" -> stringResource(R.string.type_cluster_nebula)
                                                        "Dark Nebula" -> stringResource(R.string.type_dark_nebula)
                                                        "Double Star" -> stringResource(R.string.type_double_star)
                                                        "Globular Cluster" -> stringResource(R.string.type_globular_cluster)
                                                        "Open Cluster" -> stringResource(R.string.type_open_cluster)
                                                        "Planetary Nebula" -> stringResource(R.string.type_planetary_nebula)
                                                        "Asteroid" -> stringResource(R.string.type_asteroid)
                                                        "Comet" -> stringResource(R.string.type_comet)
                                                        else -> type
                                                    }
                                                )
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
                                                        contentDescription = stringResource(R.string.cd_selected),
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
                                    contentDescription = stringResource(R.string.cd_close)
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
                                placeholder = { Text(stringResource(R.string.placeholder_search)) },
                                leadingIcon = { Icon(Default.Search, contentDescription = null) },
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
                                        contentDescription = stringResource(R.string.cd_clear_search),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconBarItem(
                            icon = Default.GpsFixed,
                            label = stringResource(R.string.label_ra_dec),
                            onClick = { showGotoRaDecPopup = true }
                        )
                    }

                    val isLandscape =
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
                                                color = Color(0xFFFF5252),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        } else {
                                            Button(onClick = {
                                                isRefreshingComets = true
                                                viewModel.refreshComets(
                                                    onComplete = {
                                                        isRefreshingComets = false
                                                        cometsRefreshTrigger++
                                                    },
                                                    onNetworkError = {
                                                        isRefreshingComets = false
                                                        Toast.makeText(
                                                            context,
                                                            R.string.error_comets_download,
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                )
                                            }) {
                                                Text(stringResource(R.string.refresh_comets_button))
                                            }
                                        }
                                    }
                                }
                            }
                            items(
                                searchResults,
                                key = { it.id.toString() + it.catalogEntry }) { obj ->
                                val currentRa = currentRaState.value
                                val currentDec = currentDecState.value
                                val currentTimeMs = currentTimeMsState.longValue
                                val lat = viewModel.currentLat
                                val lon = viewModel.currentLon
                                var isBelowHorizon = false
                                if (visibilityFilter == "All Objects" && lat != null && lon != null) {
                                    val objAltAz = raDecToAltAz(
                                        obj.ra, obj.dec, lat, lon,
                                        currentTimeMs
                                    )
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
                                        val localizedType = when (obj.objectType) {
                                            "Star" -> stringResource(R.string.type_star)
                                            "Galaxy" -> stringResource(R.string.type_galaxy)
                                            "Nebula" -> stringResource(R.string.type_nebula)
                                            "Cluster" -> stringResource(R.string.type_cluster)
                                            "Planet" -> stringResource(R.string.type_planet)
                                            "Asterism" -> stringResource(R.string.type_asterism)
                                            "Cluster + Nebula" -> stringResource(R.string.type_cluster_nebula)
                                            "Dark Nebula" -> stringResource(R.string.type_dark_nebula)
                                            "Double Star" -> stringResource(R.string.type_double_star)
                                            "Globular Cluster" -> stringResource(R.string.type_globular_cluster)
                                            "Open Cluster" -> stringResource(R.string.type_open_cluster)
                                            "Planetary Nebula" -> stringResource(R.string.type_planetary_nebula)
                                            "Asteroid" -> stringResource(R.string.type_asteroid)
                                            "Comet" -> stringResource(R.string.type_comet)
                                            else -> obj.objectType
                                        }
                                        val magText = if (obj.magnitude != null) "Mag: ${
                                            String.format(
                                                Locale.getDefault(),
                                                "%.1f",
                                                obj.magnitude
                                            )
                                        }" else if (obj.catalogLabel == "Planet") "" else "Mag: N/A"
                                        @OptIn(ExperimentalLayoutApi::class)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val (iVec, iCol) = obj.getIconAndColor()
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
                                            if (obj.designation.isNotBlank()) {
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
                                                    stringResource(R.string.status_below_horizon),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color.Red,
                                                    modifier = Modifier
                                                        .clickable { showRaDecForObj = obj }
                                                        .padding(4.dp)
                                                )
                                            } else {
                                                if (currentRa != null && currentDec != null && lat != null && lon != null) {
                                                    val telescopeAltAz = raDecToAltAz(
                                                        currentRa,
                                                        currentDec, lat, lon, currentTimeMs
                                                    )
                                                    val objAltAz =
                                                        raDecToAltAz(
                                                            obj.ra,
                                                            obj.dec,
                                                            lat,
                                                            lon,
                                                            currentTimeMs
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
                                                        modifier = Modifier
                                                            .padding(end = 8.dp)
                                                            .clickable { showRaDecForObj = obj }
                                                            .padding(4.dp)
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
                                                    IconBarItem(
                                                        icon = Default.GpsFixed,
                                                        label = stringResource(R.string.btn_goto),
                                                        onClick = {
                                                            onGoTo(
                                                                obj.ra,
                                                                obj.dec,
                                                                2000.0
                                                            )
                                                        }
                                                    )
                                                } else {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier
                                                            .padding(end = 16.dp)
                                                            .clickable { showRaDecForObj = obj }
                                                            .padding(4.dp)
                                                    ) {
                                                        Text(
                                                            formatRa(obj.ra),
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                        Text(
                                                            formatDec(obj.dec),
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    }
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
                                        contentDescription = stringResource(R.string.cd_close_keyboard),
                                        tint = Color.Red
                                    )
                                }
                                IconButton(onClick = {
                                    viewModel.landscapeKeyboardOnRight =
                                        !viewModel.landscapeKeyboardOnRight
                                }) {
                                    Icon(
                                        imageVector = if (viewModel.landscapeKeyboardOnRight) Filled.KeyboardArrowLeft else Filled.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.cd_swap_keyboard_side),
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
                                            contentDescription = stringResource(R.string.cd_close_keyboard),
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

    if (showRaDecForObj != null) {
        val obj = showRaDecForObj!!
        InfoPopup(
            title = obj.displayName,
            onDismiss = { showRaDecForObj = null }
        ) {
            Column {
                Row {
                    Text(stringResource(R.string.ra_j2000_label), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(formatRa(obj.ra))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(stringResource(R.string.dec_j2000_label), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(formatDec(obj.dec))
                }
            }
        }
    }

    if (showGotoRaDecPopup) {
        GotoRaDecPopup(
            onDismiss = { showGotoRaDecPopup = false },
            onGoTo = { ra, dec, epoch ->
                showGotoRaDecPopup = false
                onGoTo(ra, dec, epoch)
            }
        )
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
        sin(decRad) * sin(latRad) + cos(decRad) * cos(
            latRad
        ) * cos(ha)
    val alt = Math.toDegrees(asin(sinAlt.coerceIn(-1.0, 1.0)))

    val y = -sin(ha) * cos(decRad)
    val x =
        cos(latRad) * sin(decRad) - sin(latRad) * cos(
            decRad
        ) * cos(ha)
    var az = Math.toDegrees(atan2(y, x))
    if (az < 0) az += 360.0

    return AltAz(alt, az)
}

fun angularDistance(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
    val d1 = Math.toRadians(dec1)
    val d2 = Math.toRadians(dec2)
    val dRa = Math.toRadians(ra2 - ra1)
    val c =
        sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(
            dRa
        )
    return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
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

fun formatRa(raDegrees: Double): String {
    val raHours = raDegrees / 15.0
    var h = floor(raHours).toInt()
    val remainder = raHours - h
    var m = floor(remainder * 60.0).toInt()
    var s = round((remainder * 60.0 - m) * 60.0).toInt()
    if (s == 60) {
        s = 0
        m += 1
    }
    if (m == 60) {
        m = 0
        h += 1
    }
    return String.format(Locale.US, "%02dh %02dm %02ds", h, m, s)
}

fun formatDec(decDegrees: Double): String {
    val sign = if (decDegrees < 0) "-" else "+"
    val absDec = abs(decDegrees)
    var d = floor(absDec).toInt()
    val remainder = absDec - d
    var m = floor(remainder * 60.0).toInt()
    var s = round((remainder * 60.0 - m) * 60.0).toInt()
    if (s == 60) {
        s = 0
        m += 1
    }
    if (m == 60) {
        m = 0
        d += 1
    }
    return String.format(Locale.US, "%s%02d° %02d' %02d\"", sign, d, m, s)
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
        title = stringResource(R.string.title_goto_ra_dec),
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Epoch Toggle
            Text(stringResource(R.string.label_epoch), style = MaterialTheme.typography.labelLarge)
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
                        stringResource(R.string.label_j2000),
                        color = if (epochIsJ2000) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
                Box(
                    modifier = Modifier
                        .clickable { epochIsJ2000 = false }
                        .background(if (!epochIsJ2000) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.label_now),
                        color = if (!epochIsJ2000) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
            }

            // RA Input
            Text(stringResource(R.string.label_ra), style = MaterialTheme.typography.labelLarge)
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
            Text(stringResource(R.string.label_dec), style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clickable { decSign = if (decSign == "+") "-" else "+" }
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(decSign, style = MaterialTheme.typography.bodyLarge)
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
                        val cal = Calendar.getInstance()
                        val year = cal.get(Calendar.YEAR)
                        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
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
                Text(stringResource(R.string.btn_goto))
            }
        }
    }
}

@Composable
fun CoordinateBox(
    value: String,
    isFocused: Boolean,
    suffix: String,
    placeholder: String = "00",
    onClick: () -> Unit
) {
    val borderColor =
        if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(
            alpha = 0.5f
        )
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Row(
        modifier = Modifier
            .clickable { onClick() }
            .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isDeclination = suffix == "°" || suffix == "'" || suffix == "\""
        Text(
            text = value.ifEmpty { placeholder },
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isEmpty()) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.alignByBaseline()
        )
        Text(
            text = suffix,
            style = if (isDeclination) {
                MaterialTheme.typography.bodyLarge.copy(baselineShift = Superscript)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier
                .padding(start = 2.dp)
                .alignByBaseline()
        )
    }
}

@Composable
fun NightVisionNumpad(
    onKeyClick: (Char) -> Unit,
    onBackspace: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("⌫", "0", "→")
    )

    Column(
        modifier = modifier
            .background(Color.Black)
            .padding(2.dp)
            .width(200.dp)
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                when (key) {
                                    "⌫" -> onBackspace()
                                    "→" -> onNext()
                                    else -> onKeyClick(key[0])
                                }
                            }
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (key == "→") {
                            Icon(
                                imageVector = Filled.ArrowForward,
                                contentDescription = stringResource(R.string.cd_next),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = key,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

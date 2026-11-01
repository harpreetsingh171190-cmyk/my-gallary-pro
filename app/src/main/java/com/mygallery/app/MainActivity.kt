package com.mygallery.app

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mygallery.app.pro.BackupScreen
import com.mygallery.app.pro.DocumentScannerScreen

private val FONT_SCALES = floatArrayOf(1f, 1.15f, 1.3f)

class MainActivity : ComponentActivity() {
    private val lockPrefs get() = getSharedPreferences("settings", MODE_PRIVATE)
    private val appLockState = mutableStateOf(false)
    private var stoppedAt = 0L
    /** Short trips out of the app (share sheet, picker) do not re-lock it; only longer absences do. */
    private val appLockGraceMs = 30_000L
    private val unlockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) appLockState.value = false
    }

    private fun isDeviceSecure(): Boolean =
        getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

    fun launchAppUnlock() {
        val intent = getSystemService(android.app.KeyguardManager::class.java)
            ?.createConfirmDeviceCredentialIntent(getString(R.string.app_name), getString(R.string.s_confirm_device_lock))
        // No screen lock any more: do not trap the user on the lock screen.
        if (intent == null) appLockState.value = false else unlockLauncher.launch(intent)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && lockPrefs.getBoolean("app_lock", false) &&
            System.currentTimeMillis() - stoppedAt > appLockGraceMs) appLockState.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        appLockState.value = prefs.getBoolean("app_lock", false)
        setContent {
            var themeMode by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
            var appLockOn by remember { mutableStateOf(prefs.getBoolean("app_lock", false)) }
            var fontScaleIndex by remember { mutableStateOf(prefs.getInt("font_scale", 0)) }
            var accentIndex by remember { mutableStateOf(prefs.getInt("accent", 0)) }
            val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            // targetSdk 35 forces edge-to-edge; system-bar icon colour follows the active theme.
            DisposableEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                this@MainActivity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            val darkPrimary = listOf(Color(0xFF8AB4F8), Color(0xFF81C995), Color(0xFFC58AF9))[accentIndex]
            val darkOnPrimary = listOf(Color(0xFF062E6F), Color(0xFF0B3D1F), Color(0xFF3A0A5C))[accentIndex]
            val scheme = if (dark) darkColorScheme(primary = darkPrimary, onPrimary = darkOnPrimary, background = Color(0xFF0F1115), surface = Color(0xFF1A1C21))
                else lightColorScheme(primary = listOf(Color(0xFF2764D8), Color(0xFF1E8E3E), Color(0xFF8E24AA))[accentIndex], background = Color(0xFFF8F9FD), surface = Color.White)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, LocalDensity.current.fontScale * FONT_SCALES[fontScaleIndex]),
                    ) {
                    Box(Modifier.fillMaxSize()) {
                        GalleryApp(
                            themeMode,
                            { mode -> themeMode = mode; prefs.edit().putString("theme", mode).apply() },
                            appLockOn,
                            fontScaleIndex,
                            { i -> fontScaleIndex = i; prefs.edit().putInt("font_scale", i).apply() },
                            accentIndex,
                            { i -> accentIndex = i; prefs.edit().putInt("accent", i).apply() },
                        ) { on ->
                            if (on && !isDeviceSecure()) {
                                Toast.makeText(this@MainActivity, getString(R.string.s_set_screen_lock_first), Toast.LENGTH_LONG).show()
                            } else {
                                appLockOn = on
                                prefs.edit().putBoolean("app_lock", on).apply()
                            }
                        }
                        if (appLockState.value) AppLockScreen(onUnlock = { launchAppUnlock() })
                    }
                    }
                }
            }
        }
    }
}

@Composable
fun GalleryApp(themeMode: String, onThemeMode: (String) -> Unit, appLockOn: Boolean = false, fontScaleIndex: Int = 0, onFontScale: (Int) -> Unit = {}, accentIndex: Int = 0, onAccent: (Int) -> Unit = {}, onAppLock: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    val favoritePrefs = remember { context.getSharedPreferences("favorites", android.content.Context.MODE_PRIVATE) }
    var favoriteUris by remember { mutableStateOf(favoritePrefs.getStringSet("uris", emptySet())!!.toSet()) }
    val toggleFavorite: (GalleryMedia) -> Unit = { item ->
        val uri = item.uri.toString()
        favoriteUris = if (uri in favoriteUris) favoriteUris - uri else favoriteUris + uri
        favoritePrefs.edit().putStringSet("uris", favoriteUris).apply()
    }
    var media by remember { mutableStateOf(emptyList<GalleryMedia>()) }
    var viewer by remember { mutableStateOf<Pair<List<GalleryMedia>, Int>?>(null) }
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    var menuItem by remember { mutableStateOf<GalleryMedia?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameOne by remember { mutableStateOf<GalleryMedia?>(null) }
    var detailsFor by remember { mutableStateOf<GalleryMedia?>(null) }
    var trashed by remember { mutableStateOf<List<GalleryMedia>>(emptyList()) }
    var pendingRename by remember { mutableStateOf<List<Pair<GalleryMedia, String>>>(emptyList()) }
    val selectionMode = selectedUris.isNotEmpty()
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            selectedUris = emptySet(); viewer = null
            scope.launch { media = withContext(Dispatchers.IO) { MediaRepository.load(context) } }
        }
    }
    val requestTrash: (List<GalleryMedia>) -> Unit = { items ->
        if (items.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val pending = MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true)
                    trashLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } catch (_: Exception) { Toast.makeText(context, "Unable to request trash", Toast.LENGTH_SHORT).show() }
            } else Toast.makeText(context, "Trash needs Android 11 or newer", Toast.LENGTH_SHORT).show()
        }
    }
    val renameLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val plan = pendingRename
        pendingRename = emptyList()
        if (result.resultCode == android.app.Activity.RESULT_OK && plan.isNotEmpty()) {
            scope.launch {
                val renamed = withContext(Dispatchers.IO) {
                    plan.count { (item, name) ->
                        runCatching {
                            context.contentResolver.update(
                                item.uri,
                                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) },
                                null, null,
                            ) > 0
                        }.getOrDefault(false)
                    }
                }
                Toast.makeText(context, context.getString(R.string.s_renamed_count, renamed), Toast.LENGTH_SHORT).show()
                selectedUris = emptySet()
                media = withContext(Dispatchers.IO) { MediaRepository.load(context) }
            }
        }
    }
    val requestRename: (List<Pair<GalleryMedia, String>>) -> Unit = { plan ->
        if (plan.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    pendingRename = plan
                    val pending = MediaStore.createWriteRequest(context.contentResolver, plan.map { it.first.uri })
                    renameLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.s_unable_rename), Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, context.getString(R.string.s_rename_needs_11), Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(tab) {
        if (tab == 14) trashed = withContext(Dispatchers.IO) { loadTrashed(context) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            scope.launch {
                trashed = withContext(Dispatchers.IO) { loadTrashed(context) }
                media = withContext(Dispatchers.IO) { MediaRepository.load(context) }
            }
        }
    }
    val requestRestore: (List<GalleryMedia>) -> Unit = { items ->
        if (items.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            try {
                val pending = MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, false)
                restoreLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.s_unable_restore), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val setFavorites: (List<GalleryMedia>, Boolean) -> Unit = { items, add ->
        val uris = items.map { it.uri.toString() }.toSet()
        favoriteUris = if (add) favoriteUris + uris else favoriteUris - uris
        favoritePrefs.edit().putStringSet("uris", favoriteUris).apply()
    }
    val toggleSelect: (GalleryMedia) -> Unit = { item ->
        val key = item.uri.toString()
        selectedUris = if (key in selectedUris) selectedUris - key else selectedUris + key
    }
    var manageAlbums by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var galleryFilters by remember {
        mutableStateOf(
            GalleryFilterState(
                sort = runCatching {
                    GallerySortOption.valueOf(
                        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).getString("sort_option", "") ?: "",
                    )
                }.getOrNull(),
            ),
        )
    }
    LaunchedEffect(galleryFilters.sort) {
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .putString("sort_option", galleryFilters.sort?.name ?: "").apply()
    }
    var customAlbums by remember { mutableStateOf(CustomAlbums.list(context)) }
    var activeCustomAlbum by remember { mutableStateOf<CustomAlbum?>(null) }
    var customAlbumItems by remember { mutableStateOf(emptyList<GalleryMedia>()) }
    BackHandler(enabled = manageAlbums) { manageAlbums = false }
    BackHandler(enabled = selectionMode && viewer == null && !manageAlbums) { selectedUris = emptySet() }
    var editItem by remember { mutableStateOf<GalleryMedia?>(null) }
    var trimItem by remember { mutableStateOf<GalleryMedia?>(null) }
    val settings = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var timelineMode by remember { mutableStateOf(settings.getInt("timelineMode", 0)) }
    var search by rememberSaveable { mutableStateOf("") }
    var recentSearches by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getString("recent_searches", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }
    LaunchedEffect(search) {
        delay(1_500)
        val q = search.trim()
        if (q.length >= 2 && recentSearches.firstOrNull() != q) {
            recentSearches = (listOf(q) + recentSearches.filter { it != q }).take(5)
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
                .putString("recent_searches", recentSearches.joinToString("\n")).apply()
        }
    }
    var albumFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf(emptyList<List<GalleryMedia>>()) }
    var busy by remember { mutableStateOf(false) }
    var vaultFiles by remember { mutableStateOf(Vault.files(context)) }
    var vaultPreview by remember { mutableStateOf<java.io.File?>(null) }
    var vaultUnlocked by remember { mutableStateOf(false) }
    val loadMedia: () -> Unit = {
        scope.launch { media = withContext(Dispatchers.IO) { MediaRepository.load(context) } }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadMedia() }
    val vaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Vault.import(context, uri) }
            vaultFiles = Vault.files(context)
            Toast.makeText(context, if (ok) "Encrypted copy imported" else "Import failed", Toast.LENGTH_SHORT).show()
        }
    }
    val biometric = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) vaultUnlocked = true
    }
    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val granted = { p: String -> context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        // Android 14+: partial ("selected photos") access is also valid; don't re-prompt on every launch.
        val hasAccess = when {
            Build.VERSION.SDK_INT >= 34 -> (granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else -> permissions.all(granted)
        }
        if (hasAccess) loadMedia() else permission.launch(permissions)
    }
    var videoLength by rememberSaveable { mutableStateOf(0) }
    var videoDurations by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(tab) {
        if (tab == 4) videoDurations = withContext(Dispatchers.IO) { loadVideoDurations(context) }
    }
    var widgetMinutes by remember { mutableStateOf(WidgetSettings.intervalMinutes(context)) }
    val tabMedia = when {
        activeCustomAlbum != null && tab == 0 -> customAlbumItems
        tab == 4 -> media.filter {
            it.isVideo && when (videoLength) {
                1 -> (videoDurations[it.id] ?: Long.MAX_VALUE) < 60_000L
                2 -> (videoDurations[it.id] ?: 0L) >= 600_000L
                else -> true
            }
        }
        else -> media.filter { tab != 2 || it.uri.toString() in favoriteUris }
    }
    val currentList = filterGalleryMedia(tabMedia, galleryFilters, favoriteUris)
        .filter { (albumFilter == null || it.folder == albumFilter) && (search.isBlank() || it.name.contains(search, true) || it.folder.contains(search, true)) }
    val selectedItems = media.filter { it.uri.toString() in selectedUris }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        if (selectionMode) SelectionBar(
            count = selectedItems.size,
            onClose = { selectedUris = emptySet() },
            onSelectAll = { selectedUris = currentList.map { it.uri.toString() }.toSet() },
            onFavorite = { setFavorites(selectedItems, !selectedItems.all { it.uri.toString() in favoriteUris }); selectedUris = emptySet() },
            onShare = { shareMedia(context, selectedItems) },
            onTrash = { requestTrash(selectedItems) },
            onAlbums = { manageAlbums = true },
            onRename = { renameOpen = true }
        ) else Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 22.dp, bottom = 8.dp)) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.s_my_gallery), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(listOf(stringResource(R.string.s_pictures), stringResource(R.string.s_albums), "${stringResource(R.string.s_favorites)} (${favoriteUris.size})", stringResource(R.string.s_photo_editor), stringResource(R.string.s_videos), stringResource(R.string.s_private_vault_tab), stringResource(R.string.s_organize), stringResource(R.string.s_pro_tools), stringResource(R.string.s_document_scanner), stringResource(R.string.s_backup_and_sync), stringResource(R.string.s_smart_collections), stringResource(R.string.s_screenshots), stringResource(R.string.s_memories), stringResource(R.string.s_people), stringResource(R.string.s_recently_deleted), stringResource(R.string.s_large_files))[tab], style = MaterialTheme.typography.headlineLarge)
            }
            IconButton(onClick = { loadMedia() }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.s_refresh_gallery)) }
          }
          Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onAccent((accentIndex + 1) % 3) }) { Text(stringResource(R.string.s_accent)) }
            TextButton(onClick = { onFontScale((fontScaleIndex + 1) % 3) }) { Text(stringResource(R.string.s_text_size)) }
            TextButton(onClick = { onAppLock(!appLockOn) }) {
                Text(stringResource(R.string.s_lock), color = if (appLockOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onThemeMode(when (themeMode) { "system" -> "light"; "light" -> "dark"; else -> "system" }) }) {
                Text(when (themeMode) { "light" -> "Light"; "dark" -> "Dark"; else -> "Auto" })
            }
          }
        }
        renameOne?.let { item ->
            RenameOneDialog(
                item = item,
                onConfirm = { newName -> renameOne = null; requestRename(listOf(item to newName)) },
                onDismiss = { renameOne = null },
            )
        }
        if (renameOpen) {
            BatchRenameDialog(
                count = selectedItems.size,
                onConfirm = { pattern, start ->
                    renameOpen = false
                    requestRename(planRenames(selectedItems, pattern, start))
                },
                onDismiss = { renameOpen = false },
            )
        }
        detailsFor?.let { item -> InfoSheet(item) { detailsFor = null } }
        menuItem?.let { item ->
            MediaActionsDialog(
                item = item,
                onSelect = { toggleSelect(item) },
                onSetAs = { setAsMedia(context, item) },
                onTrash = { requestTrash(listOf(item)) },
                onDetails = { detailsFor = item },
                onRename = { renameOne = item },
                onMoveEarlier = activeCustomAlbum?.let { album ->
                    { customAlbumItems = AlbumOrder.move(context, album.id, customAlbumItems, item, -1) }
                },
                onMoveLater = activeCustomAlbum?.let { album ->
                    { customAlbumItems = AlbumOrder.move(context, album.id, customAlbumItems, item, 1) }
                },
                onRotate = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveRotatedCopy(context, item) }
                        if (ok) {
                            Toast.makeText(context, context.getString(R.string.s_rotated_copy_saved), Toast.LENGTH_SHORT).show()
                            media = withContext(Dispatchers.IO) { MediaRepository.load(context) }
                        }
                    }
                },
                onSetCover = activeCustomAlbum?.let { album ->
                    {
                        AlbumCovers.set(context, album.id, item.uri.toString())
                        Toast.makeText(context, context.getString(R.string.s_album_cover_set), Toast.LENGTH_SHORT).show()
                    }
                },
                onMap = {
                    scope.launch {
                        val ll = withContext(Dispatchers.IO) { photoLatLong(context, item) }
                        if (ll == null) {
                            Toast.makeText(context, context.getString(R.string.s_no_location), Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching { context.startActivity(mapIntent(ll[0], ll[1])) }
                        }
                    }
                },
                onSmallerCopy = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { saveSmallerCopy(context, item) }
                        if (ok) {
                            Toast.makeText(context, context.getString(R.string.s_smaller_copy_saved), Toast.LENGTH_SHORT).show()
                            media = withContext(Dispatchers.IO) { MediaRepository.load(context) }
                        }
                    }
                },
                onDismiss = { menuItem = null },
            )
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, divider = {}) {
            listOf(stringResource(R.string.s_pictures), stringResource(R.string.s_albums), "${stringResource(R.string.s_favorites)} (${favoriteUris.size})", stringResource(R.string.s_edit), stringResource(R.string.s_videos), stringResource(R.string.s_vault), stringResource(R.string.s_organize), stringResource(R.string.s_tools), stringResource(R.string.s_scanner), stringResource(R.string.s_backup), stringResource(R.string.s_collections), stringResource(R.string.s_screenshots), stringResource(R.string.s_memories), stringResource(R.string.s_people), stringResource(R.string.s_recently_deleted), stringResource(R.string.s_large_files)).forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = {
                    if (i != 5) { vaultUnlocked = false; vaultPreview = null }
                    tab = i; viewer = null; selectedUris = emptySet(); search = ""; albumFilter = null; activeCustomAlbum = null
                }, text = { Text(title) })
            }
        }
        when (tab) {
            0, 1, 2 -> {
                OutlinedTextField(search, { search = it }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.s_search)) }, placeholder = { Text(stringResource(R.string.s_search_your_pictures_and_albums)) }, singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
                if (search.isBlank() && recentSearches.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recentSearches.forEach { r -> AssistChip(onClick = { search = r }, label = { Text(r) }) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = showFilters, onClick = { showFilters = !showFilters }, label = { Text(if (showFilters) "Hide filters" else "Filters") })
                    if (galleryFilters.mediaType != GalleryMediaTypeFilter.ALL || galleryFilters.dateRange != null || galleryFilters.folder != null || galleryFilters.favoritesOnly || galleryFilters.sort != null) {
                        Text(stringResource(R.string.s_filters_active), style = MaterialTheme.typography.labelSmall)
                    }
                    if (tab == 1) OutlinedButton(onClick = { manageAlbums = true }) { Text(stringResource(R.string.s_manage_custom_albums)) }
                }
                if (showFilters) SearchFilterControls(
                    state = galleryFilters,
                    folders = galleryFolders(media),
                    onStateChange = { galleryFilters = it },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (tab == 2 || (tab == 0 && activeCustomAlbum != null)) {
                    val widgetAlbum = activeCustomAlbum
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            WidgetSettings.set(
                                context,
                                if (tab == 2 || widgetAlbum == null) WidgetSettings.FAVORITES else "album:${widgetAlbum.id}",
                            )
                            Toast.makeText(context, context.getString(R.string.s_widget_source_set), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.s_use_for_widget)) }
                        TextButton(onClick = {
                            WidgetSettings.set(context, WidgetSettings.ALL)
                            Toast.makeText(context, context.getString(R.string.s_widget_source_set), Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.s_widget_all)) }
                        TextButton(onClick = {
                            val next = WidgetSettings.INTERVALS[(WidgetSettings.INTERVALS.indexOf(widgetMinutes) + 1) % WidgetSettings.INTERVALS.size]
                            widgetMinutes = next
                            WidgetSettings.setInterval(context, next)
                        }) { Text(stringResource(R.string.s_widget_every, widgetMinutes)) }
                    }
                }
                if (activeCustomAlbum != null && tab == 0) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Custom album: ${activeCustomAlbum?.name}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { activeCustomAlbum = null; customAlbumItems = emptyList() }) { Text(stringResource(R.string.s_show_all)) }
                    }
                }
                val filtered = currentList
                if (albumFilter != null && tab != 1) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Album: $albumFilter", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { albumFilter = null }) { Text(stringResource(R.string.s_show_all)) }
                    }
                }
                if (tab == 1) {
                    val folders = filtered.groupBy { it.folder }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (customAlbums.isNotEmpty()) {
                            Text(stringResource(R.string.s_custom_albums), modifier = Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleMedium)
                            customAlbums.forEach { album ->
                                val cover = AlbumCovers.get(context, album.id)?.let { c -> media.firstOrNull { it.uri.toString() == c } }
                                TextButton(onClick = {
                                    scope.launch {
                                        val contents = withContext(Dispatchers.IO) { loadAlbumMedia(context, album) }
                                        activeCustomAlbum = album
                                        customAlbumItems = AlbumOrder.apply(context, album.id, contents)
                                        tab = 0
                                    }
                                }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (cover != null) MediaThumb(cover, Modifier.padding(end = 8.dp))
                                        Text(album.name)
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.s_device_folders), modifier = Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.titleMedium)
                        folders.forEach { (folder, files) ->
                            TextButton(onClick = { albumFilter = folder; search = ""; tab = 0 }) { Text("$folder (${files.size})") }
                        }
                    }
                } else if (filtered.isEmpty()) {
                    Text(if (media.isEmpty()) "No photos or videos found. Allow media access, then tap Refresh." else "No matching items.", Modifier.padding(16.dp))
                } else {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Days", "Months", "Grid").forEachIndexed { i, label ->
                            FilterChip(selected = timelineMode == i, onClick = { timelineMode = i; settings.edit().putInt("timelineMode", i).apply() }, label = { Text(label) })
                        }
                    }
                    TimelineGrid(
                        items = filtered, mode = timelineMode, selectionMode = selectionMode, selectedUris = selectedUris,
                        onToggleSelect = toggleSelect,
                        onSelectGroup = { group, select ->
                            val keys = group.map { it.uri.toString() }.toSet()
                            selectedUris = if (select) selectedUris + keys else selectedUris - keys
                        },
                        onOpen = { item, list -> viewer = list to list.indexOf(item) }
                    )
                }
            }
            3 -> {
                Text(stringResource(R.string.s_select_an_image_in_photos_then_tap_edit), Modifier.padding(16.dp))
                editItem?.takeIf { !it.isVideo }?.let { PhotoEditScreen(it) { loadMedia() } }
            }
            4 -> {
                OutlinedTextField(search, { search = it }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.s_search)) }, placeholder = { Text(stringResource(R.string.s_search_videos)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                if (tab == 4) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to R.string.s_any_length, 1 to R.string.s_short_videos, 2 to R.string.s_long_videos).forEach { (k, label) ->
                            FilterChip(selected = videoLength == k, onClick = { videoLength = k }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = showFilters, onClick = { showFilters = !showFilters }, label = { Text(if (showFilters) "Hide filters" else "Filters") })
                }
                if (showFilters) SearchFilterControls(state = galleryFilters, folders = galleryFolders(media), onStateChange = { galleryFilters = it }, modifier = Modifier.padding(horizontal = 16.dp))
                val videos = currentList
                LazyVerticalGrid(GridCells.Adaptive(120.dp)) {
                    items(videos, key = { it.uri.toString() }) { item ->
                        MediaTile(item, selectionMode, item.uri.toString() in selectedUris, onLongClick = { if (!selectionMode) menuItem = item }) {
                            if (selectionMode) toggleSelect(item) else viewer = videos to videos.indexOf(item)
                        }
                    }
                }
            }
            5 -> {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.s_private_vault), style = MaterialTheme.typography.titleLarge)
                    if (!vaultUnlocked) {
                        Text(stringResource(R.string.s_encrypted_copies_are_stored_privately_on_this_device_importing_does_not_remove_the_source_file))
                        Button(onClick = {
                            val manager = context.getSystemService(android.app.KeyguardManager::class.java)
                            val intent = manager?.createConfirmDeviceCredentialIntent("Unlock Vault", "Confirm your device PIN, pattern or password")
                            if (intent != null) biometric.launch(intent) else Toast.makeText(context, "Set a device screen lock first", Toast.LENGTH_LONG).show()
                        }) { Text(stringResource(R.string.s_unlock_with_device_credentials)) }
                    } else {
                        Text("Encrypted files: ${vaultFiles.size}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vaultPicker.launch("*/*") }) { Text(stringResource(R.string.s_import_photo_or_video)) }
                            OutlinedButton(onClick = { vaultUnlocked = false; vaultPreview = null }) { Text(stringResource(R.string.s_lock)) }
                        }
                        if (vaultFiles.isEmpty()) Text(stringResource(R.string.s_the_vault_is_empty_import_a_photo_or_video_to_store_an_encrypted_copy))
                        else VaultOrganizer(
                            files = vaultFiles,
                            onOpen = { vaultPreview = it },
                            onRefresh = { vaultFiles = Vault.files(context) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            7 -> ProToolsScreen(media)
            8 -> DocumentScannerScreen()
            9 -> BackupScreen(onOperationFinished = { result ->
                if (result.success) {
                    customAlbums = CustomAlbums.list(context)
                    timelineMode = settings.getInt("timelineMode", 0)
                    val restoredTheme = settings.getString("theme", "system") ?: "system"
                    onThemeMode(if (restoredTheme in setOf("system", "light", "dark")) restoredTheme else "system")
                }
            })
            6 -> {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.s_smart_organization), style = MaterialTheme.typography.titleLarge)
                    Text("Photos: ${media.count { !it.isVideo }} • Videos: ${media.count { it.isVideo }}")
                    Text("Folders: ${media.map { it.folder }.distinct().size}")
                    Text("Total visible media: ${media.sumOf { it.size } / (1024 * 1024)} MB")
                    Button(enabled = !busy, onClick = {
                        scope.launch { busy = true; duplicates = withContext(Dispatchers.IO) { MediaRepository.duplicates(context, media) }; busy = false }
                    }) { Text(if (busy) "Scanning…" else "Find exact duplicates (SHA-256)") }
                    Text("Duplicate groups: ${duplicates.size}")
                    duplicates.take(20).forEach { group ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.take(4).forEach { MediaThumb(it) }
                            Text("${group.size} copies: ${group.first().name}")
                        }
                    }
                    Text(stringResource(R.string.s_no_automatic_deletion_review_your_files_before_removing_them))
                }
            }
            11 -> ScreenshotCleanupUi(media, requestTrash)
            12 -> MemoriesUi(media)
            15 -> ScreenshotCleanupUi(
                media, requestTrash,
                titleRes = R.string.s_large_files,
                hintRes = R.string.s_large_files_hint,
                predicate = ::isLargeFile,
                showAgeFilter = false,
                bySize = true,
            )
            14 -> RecentlyDeletedUi(trashed, onRestore = { requestRestore(it) })
            13 -> FacesUi(media, onOpen = { list, idx -> viewer = list to idx })
            10 -> SmartCollectionsUi(
                items = media,
                favoriteUris = favoriteUris,
                onMediaClick = { item -> viewer = media to media.indexOf(item).coerceAtLeast(0) }
            )
        }
    }
    if (manageAlbums) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            AlbumManagementUi(
                selectedMedia = selectedItems,
                onDismiss = { manageAlbums = false; customAlbums = CustomAlbums.list(context) },
                onOperationComplete = { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    selectedUris = emptySet()
                    loadMedia()
                    customAlbums = CustomAlbums.list(context)
                }
            )
        }
    }
    viewer?.let { (items, start) ->
        FullScreenViewer(
            items = items, startIndex = start, favoriteUris = favoriteUris,
            onToggleFavorite = toggleFavorite,
            onShare = { shareMedia(context, listOf(it)) },
            onEdit = { editItem = it; tab = 3; viewer = null },
            onTrim = { trimItem = it; viewer = null },
            onTrash = { requestTrash(listOf(it)) },
            onClose = { viewer = null }
        )
    }
    trimItem?.let { item ->
        VideoTrimScreen(item, onClose = { trimItem = null }, onSaved = { trimItem = null; loadMedia() })
    }
    vaultPreview?.takeIf { vaultUnlocked }?.let { file ->
        VaultPreview(
            file = file,
            onClose = { vaultPreview = null },
            onExport = {
                scope.launch {
                    val uri = withContext(Dispatchers.IO) { Vault.export(context, file) }
                    val isVideo = uri?.let { context.contentResolver.getType(it)?.startsWith("video/") == true } == true
                    Toast.makeText(context, if (uri != null) "Decrypted copy saved to ${if (isVideo) "Movies" else "Pictures"}/MyGallery" else "Export failed", Toast.LENGTH_LONG).show()
                    if (uri != null) loadMedia()
                }
            },
            onDelete = {
                val ok = Vault.delete(file)
                vaultFiles = Vault.files(context)
                vaultPreview = null
                Toast.makeText(context, if (ok) "Deleted from vault" else "Delete failed", Toast.LENGTH_SHORT).show()
            }
        )
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTile(item: GalleryMedia, selectionMode: Boolean = false, selected: Boolean = false, onLongClick: () -> Unit = {}, click: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val cacheKey = "${item.uri}#240"
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(ThumbCache.get(cacheKey)) }
    LaunchedEffect(item.uri) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                try { context.contentResolver.loadThumbnail(item.uri, android.util.Size(240, 240), null) } catch (_: Exception) { null }
            }
            bitmap = loaded
            loaded?.let { ThumbCache.put(cacheKey, it) }
        }
    }
    Box(Modifier.padding(2.dp).combinedClickable(onClick = click, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })) {
        Column(Modifier.padding(if (selected) 8.dp else 0.dp)) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = item.name,
                modifier = Modifier.fillMaxWidth().height(126.dp), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxWidth().height(126.dp), contentAlignment = Alignment.Center) { Text(if (item.isVideo) "▶" else "Image") }
            Text(item.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
        }
        if (item.isVideo && bitmap != null) Text("▶", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 22.dp), style = MaterialTheme.typography.labelLarge)
        if (selectionMode) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x66000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) { if (selected) Icon(Icons.Default.Check, contentDescription = stringResource(R.string.s_selected), tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
fun SelectionBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onFavorite: () -> Unit, onShare: () -> Unit, onTrash: () -> Unit, onAlbums: () -> Unit, onRename: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.s_cancel_selection)) }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, maxLines = 1)
        TextButton(onClick = onSelectAll) { Text(stringResource(R.string.s_all)) }
        TextButton(onClick = onAlbums) { Text(stringResource(R.string.s_albums)) }
        TextButton(onClick = onRename) { Text(stringResource(R.string.s_rename)) }
        IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, contentDescription = stringResource(R.string.s_favorite)) }
        IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.s_share)) }
        IconButton(onClick = onTrash) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.s_move_to_trash)) }
    }
}

fun shareMedia(context: android.content.Context, items: List<GalleryMedia>) {
    if (items.isEmpty()) return
    val intent = if (items.size == 1) Intent(Intent.ACTION_SEND).apply {
        type = items[0].mime
        putExtra(Intent.EXTRA_STREAM, items[0].uri)
    } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = when { items.all { it.isVideo } -> "video/*"; items.none { it.isVideo } -> "image/*"; else -> "*/*" }
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
    }
    val clip = android.content.ClipData.newUri(context.contentResolver, "media", items[0].uri)
    items.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it.uri)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(intent, "Share")) } catch (_: Exception) { Toast.makeText(context, "Nothing can handle this share", Toast.LENGTH_SHORT).show() }
}

@Composable
fun PhotoPreview(item: GalleryMedia) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.uri) { bitmap = withContext(Dispatchers.IO) { try { context.contentResolver.loadThumbnail(item.uri, android.util.Size(600, 600), null) } catch (_: Exception) { null } } }
    bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit) }
}

@Composable
fun VideoPlayer(item: GalleryMedia, modifier: Modifier = Modifier.fillMaxWidth().height(230.dp)) {
    val context = LocalContext.current
    val player = remember(item.uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(item.uri)); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { ctx -> val exo = player; PlayerView(ctx).apply { this.player = exo } }, modifier = modifier)
}

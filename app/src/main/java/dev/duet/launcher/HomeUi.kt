@file:OptIn(ExperimentalFoundationApi::class)

package dev.duet.launcher

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.exp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

@Composable
fun LauncherRoot(activity: MainActivity) {
    val app = DuetApp.instance
    val prefs = app.prefs
    val repo = app.apps
    val ui = activity.ui
    val mode = rememberScreenMode(activity)

    val visible by remember { derivedStateOf { val hidden = prefs.hidden; repo.all.filter { it.key !in hidden } } }
    val favorites by remember {
        derivedStateOf { val byKey = visible.associateBy { it.key }; prefs.favorites.mapNotNull { byKey[it] } }
    }

    val clockAnchor = remember { ClockAnchor() }

    LaunchedEffect(Unit) { activity.homePressed.collect { ui.reset() } }
    // Home screen: Back closes overlays and otherwise does nothing.
    BackHandler {
        when {
            ui.editWidgets -> ui.editWidgets = false
            ui.listOpen || ui.query.isNotEmpty() -> { ui.listOpen = false; ui.query = "" }
        }
    }

    // Tell the coverage tracker where the two screens are while we're spanned.
    val view = LocalView.current
    DisposableEffect(mode, view.width, view.height) {
        ScreenCoverage.geometry = (mode as? ScreenMode.Dual)?.let {
            ScreenCoverage.Geometry(it.hinge, it.sideBySide, view.width, view.height)
        }
        onDispose { }
    }
    val covered = if (prefs.followUncoveredScreen) ScreenCoverage.covered else Covered.NONE
    val single: @Composable () -> Unit = {
        AppsPane(activity, visible, favorites, alwaysList = false, showHeader = true, showWidgets = prefs.widgetsInSingle)
    }

    // When the arrangement changes (one screen <-> two, which screen, layout choice), let the
    // clock glide to its new spot instead of jumping.
    val layoutKey = listOf(mode, covered, prefs.dualLayout, prefs.swapScreens)
    remember(layoutKey) { clockAnchor.glideForAWhile(); 0 }
    // ...and ease the rest of the new layout in, so panes don't pop.
    val contentAlpha = remember { Animatable(1f) }
    var firstLayout by remember { mutableStateOf(true) }
    LaunchedEffect(layoutKey) {
        if (firstLayout) { firstLayout = false; return@LaunchedEffect }
        contentAlpha.snapTo(0.55f)
        contentAlpha.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }

    CompositionLocalProvider(LocalClockAnchor provides clockAnchor) {
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha.value }) {
        when {
            mode == ScreenMode.Single -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) { single() }

            // An app is open on one screen: become a single-screen launcher on the other one.
            mode is ScreenMode.Dual && covered != Covered.NONE ->
                OneScreenOf(mode, useFirst = covered == Covered.SECOND, content = single)

            mode is ScreenMode.Dual -> {
                val home: @Composable () -> Unit
                val other: @Composable () -> Unit
                when (prefs.dualLayout) {
                    DualLayout.HOME_AND_LIST -> {
                        home = { HomeColumn(activity, favorites, showHeader = true, showWidgets = true) }
                        other = { AppsPane(activity, visible, favorites, alwaysList = true, showHeader = false, showWidgets = false) }
                    }
                    DualLayout.WIDGETS_AND_HOME -> {
                        home = { HomeColumn(activity, emptyList(), showHeader = true, showWidgets = true) }
                        other = { AppsPane(activity, visible, favorites, alwaysList = false, showHeader = false, showWidgets = false) }
                    }
                }
                if (prefs.swapScreens) DualSplit(mode, other, home) else DualSplit(mode, home, other)
            }
        }
      }
        FloatingClock(activity, clockAnchor)
        if (ui.pickerOpen) WidgetPicker(activity) { ui.pickerOpen = false }
    }
    }
}

/** Favorites (or the full list) plus the alphabet bar along one edge. */
@Composable
private fun AppsPane(
    activity: MainActivity,
    visible: List<AppInfo>,
    favorites: List<AppInfo>,
    alwaysList: Boolean,
    showHeader: Boolean,
    showWidgets: Boolean,
) {
    val prefs = DuetApp.instance.prefs
    val ui = activity.ui
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeLetter by remember { mutableStateOf<Char?>(null) }
    val listOpen = alwaysList || ui.listOpen

    val filtered = remember(visible, ui.query) {
        if (ui.query.isBlank()) visible else visible.filter { it.label.contains(ui.query.trim(), ignoreCase = true) }
    }
    val available = remember(visible) { visible.map { it.letter }.toSet() }

    val metrics = rememberRowMetrics()
    val rows = remember(filtered) { buildRows(filtered) }
    // Rail geometry always uses the unfiltered list: touching the rail clears the search.
    val railGeometry = remember(visible, metrics) { ListGeometry(buildRows(visible), metrics) }
    val follower = remember(listState) { RailFollower(listState) }

    val railLeft = !prefs.railOnRight
    // hidden = the invisible strip on the other edge: same touch area, the visible bar shows the letter.
    val rail: @Composable (hidden: Boolean) -> Unit = { hidden ->
        AlphabetRail(
            onLeft = if (hidden) !railLeft else railLeft,
            hidden = hidden,
            available = available,
            active = activeLetter.takeUnless { hidden },
            onPress = {
                val wasOpen = listOpen
                ui.listOpen = true
                if (ui.query.isNotEmpty()) ui.query = ""
                follower.dragging = true
                follower.geometry = railGeometry
                follower.start(scope, snapFirst = !wasOpen)
            },
            onDrag = { fraction ->
                activeLetter = LETTERS[letterSlot(fraction)]
                follower.fraction = fraction
            },
            onRelease = {
                activeLetter = null
                follower.dragging = false
            },
        )
    }

    val homeAlpha by animateFloatAsState(if (listOpen) 0f else 1f, tween(if (listOpen) 140 else 260), label = "home")

    Row(Modifier.fillMaxSize()) {
        if (railLeft) rail(false)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            // Home stays composed (just invisible) so hosted widgets aren't torn down each time the list opens.
            if (!alwaysList) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = homeAlpha }) {
                    HomeColumn(activity, favorites, showHeader, showWidgets, clockAlpha = homeAlpha)
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = listOpen,
                enter = fadeIn(tween(220)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 10 },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(200)) { it / 14 },
            ) {
                AppList(
                    apps = filtered,
                    rows = rows,
                    metrics = metrics,
                    favorites = favorites,
                    state = listState,
                    query = ui.query,
                    onQuery = { ui.query = it },
                    onLaunched = { if (!alwaysList) ui.listOpen = false; ui.query = "" },
                    scrim = !alwaysList,
                    searchRequest = ui.searchRequest,
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = activeLetter != null,
                // Beside the bar rather than in the middle, where the letter's apps now land.
                modifier = Modifier.align(if (railLeft) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 20.dp),
                enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.7f),
                exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f),
            ) {
                // Keep showing the last letter while fading out.
                var shown by remember { mutableStateOf('A') }
                activeLetter?.let { shown = it }
                LetterBubble(shown, Modifier)
            }
            if (prefs.railBothSides) {
                Box(Modifier.align(if (railLeft) Alignment.CenterEnd else Alignment.CenterStart).fillMaxHeight()) { rail(true) }
            }
            if (prefs.actionButton) {
                DuetActionButton(activity, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 20.dp).glide("action"))
            }
        }
        if (!railLeft) rail(false)
    }
}

/** Fixed row heights (px) so any scroll position in the list can be computed exactly. */
private data class RowMetrics(val searchPx: Int, val headerPx: Int, val itemPx: Int, val itemDp: Dp, val headerDp: Dp, val searchDp: Dp)

@Composable
private fun rememberRowMetrics(): RowMetrics {
    val prefs = DuetApp.instance.prefs
    val density = LocalDensity.current
    val icon = if (prefs.showIcons) (prefs.iconSizeDp * 0.85f) else 0f
    val line = with(density) { (prefs.labelSizeSp * 0.8f * 1.45f).sp.toDp().value }
    val itemDp = (maxOf(icon, line) + 16f).dp
    val headerDp = 44.dp
    val searchDp = 88.dp
    return remember(density, itemDp) {
        with(density) { RowMetrics(searchDp.roundToPx(), headerDp.roundToPx(), itemDp.roundToPx(), itemDp, headerDp, searchDp) }
    }
}

private fun letterSlot(fraction: Float): Int = (fraction * LETTERS.length).toInt().coerceIn(0, LETTERS.lastIndex)

/** Pixel offsets of every lazy-list item, and the mapping from rail position to scroll position. */
private class ListGeometry(rows: List<ListRow>, m: RowMetrics) {
    /** offsets[i] = top of lazy item i (item 0 is the search field). */
    val offsets = IntArray(rows.size + 2)
    private val sectionLetters = ArrayList<Int>()
    private val sectionStarts = ArrayList<Int>()
    private val total: Int

    init {
        offsets[0] = 0
        var y = m.searchPx
        rows.forEachIndexed { i, row ->
            offsets[i + 1] = y
            if (row is ListRow.Header) { sectionLetters += LETTERS.indexOf(row.letter); sectionStarts += y }
            y += if (row is ListRow.Header) m.headerPx else m.itemPx
        }
        offsets[rows.size + 1] = y
        total = y
    }

    /** Continuous: moves through each letter's section as the finger moves through that letter's slot. */
    /**
     * Scroll position that centres the letter under the finger: its whole group of apps sits
     * in the vertical middle of a [viewport]-tall list. A group too tall to fit scrolls through
     * as the finger moves down that letter.
     */
    fun centredScroll(fraction: Float, viewport: Float): Float {
        if (sectionStarts.isEmpty() || viewport <= 0f) return 0f
        val slot = (fraction.coerceIn(0f, 0.9999f) * LETTERS.length)
        val li = slot.toInt()
        var k = sectionLetters.indexOfFirst { it >= li }
        if (k < 0) k = sectionStarts.lastIndex
        val frac = if (sectionLetters[k] == li) slot - li else 0f
        val start = sectionStarts[k].toFloat()
        val end = (sectionStarts.getOrNull(k + 1) ?: total).toFloat()
        val len = end - start
        val margin = viewport * 0.08f
        val y = if (len <= viewport - 2 * margin) start + len / 2f - viewport / 2f
        else start - margin + frac * (len - viewport + 2 * margin)
        return y.coerceAtLeast(0f)
    }

    fun position(index: Int, offset: Int): Float = offsets[index.coerceIn(0, offsets.lastIndex)] + offset.toFloat()

    fun locate(px: Float): Pair<Int, Int> {
        var i = offsets.binarySearch(px.toInt())
        if (i < 0) i = (-i - 2).coerceAtLeast(0)
        return i to (px.toInt() - offsets[i])
    }
}

/**
 * Chases the scroll position for [fraction] every frame with exponential smoothing, so the list glides fluidly
 * under the finger instead of restarting an animation at each letter.
 */
private class RailFollower(private val state: LazyListState) {
    /** Finger position along the bar, 0 (top) to 1 (bottom). */
    var fraction by mutableFloatStateOf(0f)
    var dragging = false
    var geometry: ListGeometry? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope, snapFirst: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val g = geometry ?: return@launch
            if (snapFirst) {
                // List is only just appearing: jump to where the finger is, then follow.
                withFrameNanos { }
                val (i, o) = g.locate(scrollFor(g))
                state.scrollToItem(i, o)
            }
            state.scroll(MutatePriority.UserInput) {
                var last = 0L
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 48f)
                    last = now
                    val geo = geometry ?: break
                    if (state.layoutInfo.totalItemsCount == 0) continue
                    val current = geo.position(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                    val error = scrollFor(geo) - current
                    if (!dragging && abs(error) < 1f) break
                    val consumed = scrollBy(error * (1f - exp(-dt / FOLLOW_TAU_MS)))
                    if (!dragging && abs(consumed) < 0.5f) break // hit the end of the list
                }
            }
        }
    }

    private fun scrollFor(g: ListGeometry): Float =
        g.centredScroll(fraction, state.layoutInfo.viewportSize.height.toFloat())

    private companion object { const val FOLLOW_TAU_MS = 55f }
}

/** Clock, widgets and the favorites list — the "home" half of the launcher. */
@Composable
private fun HomeColumn(
    activity: MainActivity,
    favorites: List<AppInfo>,
    showHeader: Boolean,
    showWidgets: Boolean,
    clockAlpha: Float = 1f,
) {
    val prefs = DuetApp.instance.prefs
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                if (showHeader) ClockSlot(clockAlpha)
                if (showWidgets && (prefs.widgets.isNotEmpty() || activity.ui.editWidgets)) {
                    WidgetColumn(activity, Modifier.glide("widgets").padding(bottom = 16.dp))
                }
            }
            Column(Modifier.then(if (favorites.isNotEmpty()) Modifier.glide("favorites") else Modifier).padding(vertical = 32.dp)) {
                favorites.forEachIndexed { i, app ->
                    AppRow(
                        app, isFavorite = true, large = true,
                        canMoveUp = i > 0, canMoveDown = i < favorites.lastIndex,
                    )
                }
                if (favorites.isEmpty() && !showWidgets) {
                    Text(
                        "Long-press any app in the list to add it to favorites.",
                        style = OnWallpaper, fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

private sealed interface ListRow {
    data class Header(val letter: Char) : ListRow
    data class Item(val app: AppInfo) : ListRow
}

private fun buildRows(apps: List<AppInfo>): List<ListRow> {
    val rows = ArrayList<ListRow>(apps.size + 27)
    var last: Char? = null
    for (a in apps) {
        if (a.letter != last) { rows += ListRow.Header(a.letter); last = a.letter }
        rows += ListRow.Item(a)
    }
    return rows
}

@Composable
private fun AppList(
    apps: List<AppInfo>,
    rows: List<ListRow>,
    metrics: RowMetrics,
    favorites: List<AppInfo>,
    state: LazyListState,
    query: String,
    onQuery: (String) -> Unit,
    onLaunched: () -> Unit,
    scrim: Boolean,
    searchRequest: Int,
) {
    val repo = DuetApp.instance.apps
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchRequest) {
        if (searchRequest == 0) return@LaunchedEffect
        state.scrollToItem(0)
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val favKeys = remember(favorites) { favorites.map { it.key }.toSet() }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize()
            .then(if (scrim) Modifier.background(Color.Black.copy(alpha = 0.55f)) else Modifier)
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        item(key = "search") {
          Box(Modifier.height(metrics.searchDp), contentAlignment = Alignment.TopStart) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search apps") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    apps.firstOrNull()?.let { repo.launch(it); onLaunched() }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.7f),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                    .glass(RoundedCornerShape(28.dp)).focusRequester(focus),
            )
          }
        }
        items(rows, key = { row ->
            when (row) {
                is ListRow.Header -> "h:${row.letter}"
                is ListRow.Item -> row.app.key
            }
        }) { row ->
            when (row) {
                is ListRow.Header -> Text(
                    row.letter.toString(),
                    style = OnWallpaper,
                    color = Color.White.copy(alpha = 0.55f),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.animateItem().height(metrics.headerDp).wrapContentHeight(Alignment.Bottom)
                        .padding(bottom = 4.dp),
                )
                is ListRow.Item -> Box(Modifier.animateItem().height(metrics.itemDp), contentAlignment = Alignment.CenterStart) {
                    AppRow(row.app, isFavorite = row.app.key in favKeys, large = false, onLaunched = onLaunched)
                }
            }
        }
        if (apps.isEmpty() && query.isNotBlank()) {
            item { Text("No apps match \"$query\"", style = OnWallpaper, modifier = Modifier.padding(16.dp)) }
        }
        // Room to bring the last letters up to the middle of the screen from the alphabet bar.
        item { Spacer(Modifier.fillParentMaxHeight(0.5f)) }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isFavorite: Boolean,
    large: Boolean,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onLaunched: () -> Unit = {},
) {
    val prefs = DuetApp.instance.prefs
    val repo = DuetApp.instance.apps
    var menu by remember { mutableStateOf(false) }
    val labelSize = if (large) prefs.labelSizeSp else (prefs.labelSizeSp * 0.8f).toInt()
    val iconSize = if (large) prefs.iconSizeDp else (prefs.iconSizeDp * 0.85f).toInt()
    val swipe = rememberScreenSwipe(enabled = prefs.swipeToPickScreen) { target ->
        repo.launchOn(app, target)
        onLaunched()
    }

    Box {
        swipe.Hint(Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth()
                .graphicsLayer { translationX = swipe.offset }
                .then(swipe.modifier)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = { repo.launch(app); onLaunched() }, onLongClick = { menu = true })
                .padding(vertical = if (large) 10.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (prefs.showIcons) {
                AppIcon(app, iconSize)
                Spacer(Modifier.width(16.dp))
            }
            Text(app.label, style = OnWallpaper, fontSize = labelSize.sp, maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove from favorites" else "Add to favorites") },
                onClick = { menu = false; prefs.toggleFavorite(app.key) },
            )
            if (isFavorite && large) {
                if (canMoveUp) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; prefs.moveFavorite(app.key, -1) })
                if (canMoveDown) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; prefs.moveFavorite(app.key, 1) })
            }
            DropdownMenuItem(text = { Text("Hide") }, onClick = {
                menu = false
                prefs.hidden = prefs.hidden + app.key
                prefs.favorites = prefs.favorites - app.key
            })
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; repo.openInfo(app) })
            DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menu = false; repo.uninstall(app) })
        }
    }
}

@Composable
fun AppIcon(app: AppInfo, sizeDp: Int) {
    val prefs = DuetApp.instance.prefs
    val repo = DuetApp.instance.apps
    val px = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val pack = prefs.iconPack
    val bmp by produceState(repo.peekIcon(app, pack, px), app.key, pack, px) {
        if (value == null) value = repo.icon(app, pack, px)
    }
    Box(Modifier.size(sizeDp.dp)) { bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) } }
}

/** Vertical A–Z strip: touch or drag to scroll; letters with no apps are dimmed. */
@Composable
private fun AlphabetRail(
    onLeft: Boolean,
    hidden: Boolean,
    available: Set<Char>,
    active: Char?,
    onPress: () -> Unit,
    onDrag: (fraction: Float) -> Unit,
    onRelease: () -> Unit,
) {
    val compact = DuetApp.instance.prefs.compactRail
    // zIndex: the active letter pops out over the list beside it.
    val placement = if (hidden) Modifier.alpha(0f) else Modifier.zIndex(1f).glide("rail")
    Box(placement.fillMaxHeight().width(if (compact) 34.dp else 36.dp).padding(bottom = 16.dp, top = 16.dp), contentAlignment = Alignment.BottomCenter) {
    Box(
        Modifier.fillMaxHeight(if (compact) 0.75f else 1f).width(if (compact) 26.dp else 28.dp),
    ) {
    // Glass behind the letters (separate so the active letter can pop out sideways unclipped).
    Box(Modifier.matchParentSize().glass(RoundedCornerShape(14.dp), strength = 0.8f))
    Column(
        Modifier.matchParentSize().padding(vertical = 8.dp)
            .pointerInput(Unit) {
                fun fraction(y: Float) = (y / size.height).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onDrag(fraction(down.position.y))
                    onPress()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        onDrag(fraction(change.position.y))
                    }
                    onRelease()
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LETTERS.forEach { c ->
            val highlight by animateFloatAsState(if (c == active) 1f else 0f, tween(120), label = "rail")
            Text(
                c.toString(),
                style = OnWallpaper,
                fontSize = if (compact) 10.5.sp else 11.sp,
                fontWeight = if (c == active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.graphicsLayer {
                    val scale = 1f + 0.5f * highlight
                    scaleX = scale; scaleY = scale
                    // Pop out towards the middle of the screen.
                    translationX = (if (onLeft) 22.dp else (-22).dp).toPx() * highlight
                },
                color = when {
                    c == active -> accent
                    c in available -> Color.White.copy(alpha = 0.85f)
                    else -> Color.White.copy(alpha = 0.25f)
                },
            )
        }
    }
    }
    }
}

@Composable
private fun LetterBubble(letter: Char, modifier: Modifier) {
    Box(
        modifier.size(88.dp).clip(CircleShape).background(Color(0x991B1C20)).glass(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.toString(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Medium)
    }
}

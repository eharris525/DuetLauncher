package dev.duet.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.MutableSharedFlow

/** Transient home-screen UI state; kept on the activity so it survives spanning/unspanning. */
class UiState {
    var listOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var editWidgets by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
    /** The Claude voice chat panel is open over the home screen. */
    var voiceChat by mutableStateOf(false)
    /** Id of the built-in widget whose settings are open, or null. */
    var configureWidget by mutableStateOf<Int?>(null)
    /** Bumped to ask the app list to focus its search field and show the keyboard. */
    var searchRequest by mutableIntStateOf(0)

    fun reset() {
        listOpen = false
        query = ""
        editWidgets = false
        pickerOpen = false
        voiceChat = false
    }
}

class MainActivity : ComponentActivity() {
    lateinit var widgetHost: AppWidgetHost
        private set
    lateinit var widgetManager: AppWidgetManager
        private set

    val ui = UiState()
    /** Fires when the user presses Home while already on the launcher. */
    val homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val prefs get() = DuetApp.instance.prefs

    /**
     * Hosted widget views, reused when the layout switches between one and two screens so the
     * widget doesn't have to be inflated again (that caused dropped frames mid-animation).
     */
    private val widgetViews = HashMap<Int, AppWidgetHostView>()
    /** Last size (dp) reported to each widget, so we only ask it to re-render on a real change. */
    val sentWidgetSizes = HashMap<Int, Pair<Int, Int>>()

    fun widgetView(id: Int, info: AppWidgetProviderInfo): AppWidgetHostView {
        val view = widgetViews.getOrPut(id) { widgetHost.createView(this, id, info) }
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        return view
    }
    private var pendingWidgetId = -1

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) configureOrAdd(pendingWidgetId) else cancelPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingWidgetId = savedInstanceState?.getInt(KEY_PENDING, -1) ?: -1
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(applicationContext, HOST_ID)
        accessibility = getSystemService(AccessibilityManager::class.java)
        accessibility.addAccessibilityStateChangeListener(a11yListener)
        refreshSemanticsMode()
        if (savedInstanceState == null && !prefs.setupDone) {
            // Once laid out: on a spanned Duo, open the guide on one screen rather than across both.
            window.decorView.post {
                runCatching { startActivity(
                    // Its own task: inside the home task it would be cleared away by the Home press.
                    Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ScreenCoverage.launchOptions(Covered.FIRST),
                ) }
            }
        }
        setContent {
            DuetTheme {
                Box(Modifier.fillMaxSize().then(if (hideSemantics) Modifier.clearAndSetSemantics { } else Modifier)) {
                    LauncherRoot(this@MainActivity)
                }
            }
        }
    }

    // --- Accessibility cost ------------------------------------------------------------

    private lateinit var accessibility: AccessibilityManager
    private val a11yListener = AccessibilityManager.AccessibilityStateChangeListener { refreshSemanticsMode() }

    /**
     * With *any* accessibility service on, Compose re-checks this screen's whole accessibility
     * tree about every 100 ms while anything moves — measured on the Duo 2 as ~4x more janky
     * frames during layout transitions. When the only service running is our own screen
     * watcher (which never reads app contents), nobody consumes that tree, so we stop
     * publishing it. Any other service (TalkBack, Switch Access, …) restores it.
     */
    private var hideSemantics by mutableStateOf(false)

    private fun refreshSemanticsMode() {
        val others = accessibility.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .filter { it.resolveInfo?.serviceInfo?.packageName != packageName }
        hideSemantics = accessibility.isEnabled && others.isEmpty()
    }

    override fun onResume() {
        super.onResume()
        refreshSemanticsMode()
    }

    override fun onDestroy() {
        accessibility.removeAccessibilityStateChangeListener(a11yListener)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING, pendingWidgetId)
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgetHost.stopListening() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            // Home clears apps off both screens on the Duo.
            ScreenCoverage.clear()
            homePressed.tryEmit(Unit)
        }
    }

    // --- Screen coverage heuristic (see ScreenCoverage) -------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) ScreenCoverage.onTouchDown(ev.x, ev.y)
        return super.dispatchTouchEvent(ev)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        ScreenCoverage.noteLaunch()
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ScreenCoverage.noteLaunch()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) {
            window.decorView.postDelayed({ ScreenCoverage.onRegainedFocus() }, 300)
        } else {
            ScreenCoverage.onLostFocusToApp()
        }
    }

    fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    // --- Widgets -----------------------------------------------------------------------

    fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrAdd(id)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            )
        }
    }

    private fun configureOrAdd(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id) ?: return cancelPending()
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
            } catch (e: Exception) {
                commitWidget(id)
            }
        } else {
            commitWidget(id)
        }
    }

    @Deprecated("Needed for AppWidgetHost.startAppWidgetConfigureActivityForResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIGURE) {
            if (resultCode == RESULT_OK) commitWidget(pendingWidgetId) else cancelPending()
        }
    }

    private fun commitWidget(id: Int) {
        if (id == -1) return
        val info = widgetManager.getAppWidgetInfo(id)
        val density = resources.displayMetrics.density
        val minDp = info?.let { (it.minHeight / density).toInt() } ?: 0
        val height = (minDp + 16).coerceIn(96, 420)
        prefs.widgets = prefs.widgets + WidgetEntry(id, height)
        pendingWidgetId = -1
        ui.editWidgets = true // let the user size/position it right away
    }

    private fun cancelPending() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = -1
    }

    fun removeWidget(id: Int) {
        if (id < 0) {
            prefs.builtins = prefs.builtins.filterNot { it.id == id }
        } else {
            widgetViews.remove(id)
            sentWidgetSizes.remove(id)
            widgetHost.deleteAppWidgetId(id)
        }
        prefs.widgets = prefs.widgets.filterNot { it.id == id }
    }

    /** Adds one of the launcher's own widgets (negative id), and asks for calendar access if it needs it. */
    fun addBuiltIn(kind: BuiltInKind) {
        val id = minOf(0, prefs.widgets.minOfOrNull { it.id } ?: 0, prefs.builtins.minOfOrNull { it.id } ?: 0) - 1
        prefs.builtins = prefs.builtins + BuiltInConfig(id, kind)
        prefs.widgets = prefs.widgets + WidgetEntry(id, kind.defaultHeightDp)
        if ((kind == BuiltInKind.CALENDAR || kind == BuiltInKind.AGENDA) && !CalendarData.hasPermission(this)) requestCalendarAccess()
        ui.editWidgets = true
        if (kind == BuiltInKind.WEATHER) ui.configureWidget = id // needs a location first
    }

    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CalendarPermission.grants++
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ui.voiceChat = true
    }

    /** The AI button: Claude voice chat on the home screen (asks for the microphone first). */
    fun startVoiceChat() {
        if (prefs.claudeKey.isBlank()) { openSettings(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) ui.voiceChat = true
        else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    fun requestCalendarAccess() = calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)

    fun resizeWidget(id: Int, deltaDp: Int) {
        prefs.widgets = prefs.widgets.map {
            if (it.id == id) it.copy(heightDp = (it.heightDp + deltaDp).coerceIn(64, 640)) else it
        }
    }

    fun moveWidget(id: Int, delta: Int) {
        val list = prefs.widgets.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        prefs.widgets = list
    }

    companion object {
        private const val HOST_ID = 0x0D0E7
        private const val REQ_CONFIGURE = 41
        private const val KEY_PENDING = "pending_widget"
    }
}

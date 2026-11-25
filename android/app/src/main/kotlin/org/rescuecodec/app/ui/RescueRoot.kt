// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.app.Application
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.R
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.ui.components.SosBar
import org.rescuecodec.app.ui.components.StatusDot
import org.rescuecodec.app.ui.screens.ActionsScreen
import org.rescuecodec.app.ui.screens.ConnectScreen
import org.rescuecodec.app.ui.screens.FeedScreen
import org.rescuecodec.app.ui.screens.MaydayBanner
import org.rescuecodec.app.ui.screens.ReportScreen
import org.rescuecodec.app.ui.screens.SettingsScreen
import org.rescuecodec.app.ui.screens.StatsScreen
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.ui.theme.RescueTheme
import org.rescuecodec.app.ui.theme.ThemeMode
import java.util.Locale

/** The places in the app. Settings is reached from the top bar. */
enum class Screen(val labelRes: Int, val iconRes: Int, val tag: String) {
    REPORT(R.string.nav_report, R.drawable.ic_mic, "tab_report"),
    ACTIONS(R.string.nav_actions, R.drawable.ic_assignment_turned_in, "tab_actions"),
    FEED(R.string.nav_feed, R.drawable.ic_rss_feed, "tab_feed"),
    STATS(R.string.nav_stats, R.drawable.ic_bar_chart, "tab_stats"),
    RADIO(R.string.nav_radio, R.drawable.ic_cell_tower, "tab_radio"),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings, "tab_settings"),
}

private val TABS = listOf(Screen.REPORT, Screen.ACTIONS, Screen.FEED, Screen.STATS, Screen.RADIO)

private class LocalizedContext(base: android.content.Context, private val res: android.content.res.Resources) : android.content.ContextWrapper(base) {
    override fun getResources(): android.content.res.Resources = res
}

/** Makes `stringResource` use the language chosen in the app, not the phone's language. */
@Composable
fun WithAppLanguage(lang: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    // Keyed on the live configuration, so day/night and font size changes still reach the screens.
    val live = LocalConfiguration.current
    val localized = remember(base, live, lang) {
        val cfg = Configuration(live)
        cfg.setLocale(Locale.forLanguageTag(lang))
        // A wrapper, not a new context: it must still lead to the Activity (permission and result launchers look for it).
        LocalizedContext(base, base.createConfigurationContext(cfg).resources)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) { content() }
}

/** The whole app: theme, language, top bar, SOS strip, navigation and the six screens. */
@Composable
fun RescueRoot(graph: AppGraph, initial: Screen = Screen.REPORT, navRequest: Pair<Int, Screen>? = null) {
    val app = LocalContext.current.applicationContext as Application
    val factory = remember(graph) { AppViewModelFactory(app, graph) }
    val settings: SettingsViewModel = viewModel(factory = factory)
    val lang by settings.language.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()
    WithAppLanguage(lang) {
        RescueTheme(ThemeMode.of(theme)) {
            // Test tags become resource ids, so uiautomator and the screenshot script can find buttons.
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
                RescueScaffold(factory, settings, lang, initial, navRequest)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RescueScaffold(factory: AppViewModelFactory, settings: SettingsViewModel, lang: String, initial: Screen, navRequest: Pair<Int, Screen>?) {
    val connection: ConnectionViewModel = viewModel(factory = factory)
    val tasksVm: TasksViewModel = viewModel(factory = factory)
    val messages: MessagesViewModel = viewModel(factory = factory)
    val stats: StatsViewModel = viewModel(factory = factory)
    val report: ReportViewModel = viewModel(factory = factory)

    var screen by rememberSaveable { mutableStateOf(initial.name) }
    val current = Screen.valueOf(screen)
    LaunchedEffect(navRequest) { navRequest?.let { screen = it.second.name } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val link by connection.state.collectAsStateWithLifecycle()
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()
    val waiting by messages.waiting.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // With the keyboard up the bottom bar goes away and the SOS strip rides on top of the keyboard.
    val imeUp = WindowInsets.isImeVisible
    val openCount = tasks.count { !it.local && it.state == "open" }

    // Back goes to Report first, then leaves the app.
    BackHandler(enabled = current != Screen.REPORT) { screen = Screen.REPORT.name }

    val sosQueued = stringResource(R.string.sos_queued)
    val sosQueuedNoPos = stringResource(R.string.sos_queued_no_position)
    val sosFailed = stringResource(R.string.sos_failed)
    val onSos: () -> Unit = {
        messages.sendSos { r ->
            val text = when (r) {
                is SosResult.Queued -> if (r.withPosition) sosQueued else sosQueuedNoPos
                is SosResult.Failed -> sosFailed.format(r.message)
            }
            scope.launch { snackbar.showSnackbar(text, duration = SnackbarDuration.Long) }
        }
    }

    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            MaydayBanner(tasksVm, tasks, waiting, lang) { screen = Screen.ACTIONS.name }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (current) {
                    Screen.REPORT -> ReportScreen(report, snackbar, lang)
                    Screen.ACTIONS -> ActionsScreen(tasksVm, tasks, snackbar, lang)
                    Screen.FEED -> FeedScreen(messages, snackbar, lang)
                    Screen.STATS -> StatsScreen(stats, lang)
                    Screen.RADIO -> ConnectScreen(connection, snackbar)
                    Screen.SETTINGS -> SettingsScreen(settings, connection, lang)
                }
            }
            SosBar(onSos)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 8.dp)) },
        topBar = {
            TopBar(
                title = stringResource(current.labelRes),
                link = link,
                lang = lang,
                inSettings = current == Screen.SETTINGS,
                onLanguage = settings::toggleLanguage,
                onSettings = { screen = if (current == Screen.SETTINGS) Screen.REPORT.name else Screen.SETTINGS.name },
                onStatus = { screen = Screen.RADIO.name },
            )
        },
        bottomBar = {
            if (!wide && !imeUp) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                    modifier = Modifier.testTag("nav_bar"),
                ) {
                    TABS.forEach { s ->
                        NavigationBarItem(
                            selected = current == s,
                            onClick = { screen = s.name },
                            modifier = Modifier.testTag(s.tag),
                            icon = {
                                BadgedBox(badge = {
                                    if (s == Screen.ACTIONS && openCount > 0) Badge(containerColor = Rc.colors.openSolid, contentColor = androidx.compose.ui.graphics.Color.Black) { Text(openCount.toString()) }
                                }) { RcIcon(s.iconRes, null, tint = LocalContentColorFor(current == s)) }
                            },
                            label = { Text(stringResource(s.labelRes), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Clip) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (wide) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxHeight()) {
                    TABS.forEach { s ->
                        NavigationRailItem(
                            selected = current == s,
                            onClick = { screen = s.name },
                            modifier = Modifier.testTag(s.tag),
                            icon = { RcIcon(s.iconRes, null, tint = LocalContentColorFor(current == s)) },
                            label = { Text(stringResource(s.labelRes), style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)).imePadding())
            }
        } else {
            content(Modifier.padding(padding).fillMaxSize().imePadding())
        }
    }
}

@Composable
private fun LocalContentColorFor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun TopBar(
    title: String,
    link: LinkState,
    lang: String,
    inSettings: Boolean,
    onLanguage: () -> Unit,
    onSettings: () -> Unit,
    onStatus: () -> Unit,
) {
    val c = Rc.colors
    val (dot, text) = when (link) {
        is LinkState.Connected -> c.doneSolid to stringResource(R.string.link_connected, link.nodeName ?: link.target.label)
        is LinkState.Connecting -> c.openSolid to stringResource(R.string.link_connecting)
        is LinkState.Scanning -> c.openSolid to stringResource(R.string.link_scanning)
        is LinkState.Error -> c.mayday to stringResource(R.string.link_error)
        LinkState.Disconnected -> MaterialTheme.colorScheme.outline to stringResource(R.string.link_offline)
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, modifier = Modifier.semantics { contentDescription = title })
                Row(
                    Modifier.clickable(onClick = onStatus).heightIn(min = 24.dp).testTag("status_pill"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(dot, size = 10.dp)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // One tap switches English and German.
            Surface(
                onClick = onLanguage,
                modifier = Modifier.heightIn(min = 48.dp).testTag("language_toggle"),
                shape = MaterialTheme.shapes.medium,
                color = Color0,
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    LangWord("EN", lang == "en")
                    Text("/", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.titleMedium)
                    LangWord("DE", lang == "de")
                }
            }
            Surface(
                onClick = onSettings,
                modifier = Modifier.heightIn(min = 48.dp).width(48.dp).testTag("settings_button"),
                shape = MaterialTheme.shapes.medium,
                color = if (inSettings) MaterialTheme.colorScheme.primary else Color0,
            ) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    RcIcon(R.drawable.ic_settings, stringResource(R.string.nav_settings), tint = if (inSettings) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
private fun LangWord(code: String, active: Boolean) {
    Text(
        code,
        style = MaterialTheme.typography.titleMedium,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        modifier = if (active) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

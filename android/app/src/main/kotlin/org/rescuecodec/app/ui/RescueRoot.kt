// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.app.Application
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.rescuecodec.app.AppGraph
import org.rescuecodec.app.R
import org.rescuecodec.app.radio.LinkState
import org.rescuecodec.app.ui.components.RcIcon
import org.rescuecodec.app.core.ChannelChoice
import org.rescuecodec.app.ui.chat.ChatScreen
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.SosDock
import org.rescuecodec.app.ui.components.StatusDot
import org.rescuecodec.app.ui.screens.ConnectScreen
import org.rescuecodec.app.ui.screens.MaydayBanner
import org.rescuecodec.app.ui.screens.TasksScreen
import org.rescuecodec.app.ui.screens.SettingsScreen
import org.rescuecodec.app.ui.screens.StatsScreen
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.ui.theme.RescueTheme
import org.rescuecodec.app.ui.theme.ThemeMode
import java.util.Locale

/**
 * The places in the app. Two tabs (Team, Tasks); Radio, Airtime and savings, and Settings open from the top bar
 * menu, with a back arrow.
 */
enum class Screen(val labelRes: Int, val iconRes: Int, val tag: String) {
    TEAM(R.string.nav_team, R.drawable.ic_forum, "tab_team"),
    TASKS(R.string.nav_tasks, R.drawable.ic_assignment_turned_in, "tab_tasks"),
    STATS(R.string.nav_stats, R.drawable.ic_bar_chart, "menu_stats"),
    RADIO(R.string.nav_radio, R.drawable.ic_cell_tower, "menu_radio"),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings, "menu_settings"),
    ;

    val isTab: Boolean get() = this == TEAM || this == TASKS

    companion object {
        /** The `--es screen X` launch extra. Old names still work: REPORT and FEED open Team, ACTIONS opens Tasks. */
        fun from(name: String?): Screen? = when (name?.trim()?.uppercase()) {
            "TEAM", "CHAT", "REPORT", "FEED", "HISTORY" -> TEAM
            "TASKS", "ACTIONS" -> TASKS
            "STATS", "AIRTIME" -> STATS
            "RADIO", "CONNECT" -> RADIO
            "SETTINGS" -> SETTINGS
            else -> null
        }
    }
}

private val TABS = listOf(Screen.TEAM, Screen.TASKS)

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

/** The whole app: theme, language, top bar, the two tabs and the screens of the menu. */
@Composable
fun RescueRoot(graph: AppGraph, initial: Screen = Screen.TEAM, navRequest: Pair<Int, Screen>? = null) {
    val app = LocalContext.current.applicationContext as Application
    val factory = remember(graph) { AppViewModelFactory(app, graph) }
    val settings: SettingsViewModel = viewModel(factory = factory)
    val lang by settings.language.collectAsStateWithLifecycle()
    val theme by settings.theme.collectAsStateWithLifecycle()
    WithAppLanguage(lang) {
        RescueTheme(ThemeMode.of(theme)) {
            // Test tags become resource ids, so uiautomator and the screenshot script can find buttons.
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
                RescueScaffold(graph, factory, settings, lang, initial, navRequest)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RescueScaffold(graph: AppGraph, factory: AppViewModelFactory, settings: SettingsViewModel, lang: String, initial: Screen, navRequest: Pair<Int, Screen>?) {
    val connection: ConnectionViewModel = viewModel(factory = factory)
    val tasksVm: TasksViewModel = viewModel(factory = factory)
    val messages: MessagesViewModel = viewModel(factory = factory)
    val stats: StatsViewModel = viewModel(factory = factory)
    val report: ReportViewModel = viewModel(factory = factory)
    // One conversation today: the team channel. A chat list would hold one of these per channel.
    val chatChannel = ChatChannel.TEAM
    val chat: ChatViewModel = viewModel(key = chatChannel.key, factory = ChatViewModelFactory(LocalContext.current.applicationContext as Application, graph, chatChannel))
    val chatTitle by chat.title.collectAsStateWithLifecycle()

    var screen by rememberSaveable { mutableStateOf(initial.name) }
    var lastTab by rememberSaveable { mutableStateOf(if (initial.isTab) initial.name else Screen.TEAM.name) }
    val current = Screen.valueOf(screen)
    fun go(s: Screen) {
        if (s.isTab) lastTab = s.name
        screen = s.name
    }
    LaunchedEffect(navRequest) { navRequest?.let { go(it.second) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val link by connection.state.collectAsStateWithLifecycle()
    val channel by connection.channel.collectAsStateWithLifecycle()
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()
    val waiting by messages.waiting.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // With the keyboard up the bottom bar goes away, so the composer rides on the keyboard.
    val imeUp = WindowInsets.isImeVisible
    val openCount = tasks.count { !it.local && (it.state == "open" || (it.type == "mayday" && it.state != "done")) }

    // In-app cue for a new message or task while the app is open: the phone's own tone and a haptic tick.
    val view = LocalView.current
    LaunchedEffect(graph) {
        graph.teamAlerts.cues.collect { kind ->
            org.rescuecodec.app.service.InAppSound.play(kind)
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // Back: from a menu screen to the last tab, from Tasks to Team, then out of the app.
    BackHandler(enabled = current != Screen.TEAM) { if (current.isTab) go(Screen.TEAM) else screen = lastTab }

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
            MaydayBanner(tasksVm, tasks, waiting, lang) { go(Screen.TASKS) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (current) {
                    Screen.TEAM -> ChatScreen(chat, messages, tasksVm, report, tasks, link, channel, snackbar, lang, onOpenRadio = { go(Screen.RADIO) }, onSos = onSos)
                    Screen.TASKS -> TasksScreen(tasksVm, tasks, snackbar, lang)
                    Screen.STATS -> StatsScreen(stats, lang)
                    Screen.RADIO -> ConnectScreen(connection, snackbar)
                    Screen.SETTINGS -> SettingsScreen(settings, connection, lang)
                }
                // SOS is always one hold away: in the composer on Team, at the bottom left everywhere else.
                if (current != Screen.TEAM && !imeUp) SosDock(onSos, Modifier.align(Alignment.BottomStart))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 80.dp)) },
        topBar = {
            if (current.isTab) {
                TabTopBar(
                    title = if (current == Screen.TEAM) chatTitle ?: stringResource(R.string.nav_team) else stringResource(R.string.nav_tasks),
                    link = link,
                    queued = waiting.size,
                    lang = lang,
                    onLanguage = settings::toggleLanguage,
                    onStatus = { go(Screen.RADIO) },
                    onMenu = { go(it) },
                )
            } else {
                BackTopBar(stringResource(current.labelRes)) { screen = lastTab }
            }
        },
        bottomBar = {
            if (!wide && !imeUp && current.isTab) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.testTag("nav_bar"),
                ) {
                    TABS.forEach { s ->
                        NavigationBarItem(
                            selected = current == s,
                            onClick = { go(s) },
                            modifier = Modifier.testTag(s.tag),
                            icon = {
                                BadgedBox(badge = {
                                    if (s == Screen.TASKS && openCount > 0) Badge(containerColor = Rc.colors.openSolid, contentColor = androidx.compose.ui.graphics.Color.Black) { Text(openCount.toString(), style = MaterialTheme.typography.labelMedium) }
                                }) { RcIcon(s.iconRes, null, tint = LocalContentColorFor(current == s)) }
                            },
                            label = { Text(stringResource(s.labelRes), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Rc.colors.acceptedSoft,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
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
        if (wide && current.isTab) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxHeight()) {
                    TABS.forEach { s ->
                        NavigationRailItem(
                            selected = current == s,
                            onClick = { go(s) },
                            modifier = Modifier.testTag(s.tag),
                            icon = {
                                BadgedBox(badge = {
                                    if (s == Screen.TASKS && openCount > 0) Badge(containerColor = Rc.colors.openSolid, contentColor = androidx.compose.ui.graphics.Color.Black) { Text(openCount.toString()) }
                                }) { RcIcon(s.iconRes, null, tint = LocalContentColorFor(current == s)) }
                            },
                            label = { Text(stringResource(s.labelRes), style = MaterialTheme.typography.labelLarge) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = Rc.colors.acceptedSoft,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)).imePadding())
            }
        } else if (!current.isTab || wide) {
            content(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)).imePadding())
        } else {
            content(Modifier.padding(padding).fillMaxSize().imePadding())
        }
    }
}

@Composable
private fun LocalContentColorFor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Top bar of the two tabs, like a messenger: the channel name with the radio status under it (tap: Radio screen),
 * EN / DE, and a menu with Radio, Airtime and savings, Settings.
 */
@Composable
private fun TabTopBar(
    title: String,
    link: LinkState,
    queued: Int,
    lang: String,
    onLanguage: () -> Unit,
    onStatus: () -> Unit,
    onMenu: (Screen) -> Unit,
) {
    val c = Rc.colors
    val noRadio = if (queued > 0) stringResource(R.string.status_none_waiting, queued) else stringResource(R.string.status_none)
    val (dot, text) = when (link) {
        is LinkState.Connected -> c.doneSolid to stringResource(R.string.status_connected, link.nodeName ?: link.target.label)
        is LinkState.Connecting -> c.openSolid to stringResource(R.string.status_connecting)
        is LinkState.Scanning -> c.openSolid to stringResource(R.string.status_scanning)
        is LinkState.Error -> c.mayday to stringResource(R.string.status_error)
        LinkState.Disconnected -> (if (queued > 0) c.mayday else MaterialTheme.colorScheme.outline) to noRadio
    }
    var menu by remember { mutableStateOf(false) }
    val statusDesc = stringResource(R.string.status_desc, text)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelTitle(
                    title = title,
                    onClick = onStatus,
                    description = "$title. $statusDesc",
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(dot, size = 10.dp)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("status_text"))
                    }
                }
                LanguageToggle(lang, onLanguage)
                Box {
                    Surface(
                        onClick = { menu = true },
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.size(48.dp).testTag("menu_button"),
                    ) {
                        Box(contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_more_vert, stringResource(R.string.menu_more)) }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                        listOf(Screen.RADIO, Screen.STATS, Screen.SETTINGS).forEach { s ->
                            DropdownMenuItem(
                                text = { Text(stringResource(s.labelRes), style = MaterialTheme.typography.bodyLarge) },
                                leadingIcon = { RcIcon(s.iconRes, null) },
                                onClick = { menu = false; onMenu(s) },
                                modifier = Modifier.heightIn(min = 52.dp).testTag(s.tag),
                            )
                        }
                    }
                }
            }
            Hairline()
        }
    }
}

/**
 * The conversation title with a line under it (today: the radio status). One tap target. Today it opens the Radio
 * screen; with several channels it opens the chat list, and the radio status moves to its own chip.
 */
@Composable
fun ChannelTitle(title: String, onClick: () -> Unit, description: String, modifier: Modifier = Modifier, subtitle: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = 56.dp).testTag("status_pill").semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("top_title"))
            subtitle()
        }
    }
}

/** EN | DE in the top bar: the current language is filled. One tap switches the whole app. */
@Composable
private fun LanguageToggle(lang: String, onToggle: () -> Unit) {
    val desc = stringResource(R.string.lang_toggle_desc, lang.uppercase())
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(10.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }.testTag("lang_toggle"),
    ) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("en", "de").forEach { l ->
                    val on = l == lang
                    Text(
                        l.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.background(if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** Top bar of a menu screen: back arrow and title. */
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(onClick = onBack, color = androidx.compose.ui.graphics.Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(48.dp).testTag("back_button")) {
                    Box(contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_arrow_back, stringResource(R.string.action_back)) }
                }
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("top_title"))
            }
            Hairline()
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui

import android.app.Application
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import org.rescuecodec.app.ui.screens.RadioScreen
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
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
import org.rescuecodec.app.core.ChatId
import org.rescuecodec.app.core.TeamAlerts
import org.rescuecodec.app.ui.chat.ChatListScreen
import org.rescuecodec.app.ui.chat.ChatScreen
import org.rescuecodec.app.ui.components.Hairline
import org.rescuecodec.app.ui.components.SosBar
import org.rescuecodec.app.ui.components.StatusDot
import org.rescuecodec.app.ui.screens.MaydayBanner
import org.rescuecodec.app.ui.screens.TasksScreen
import org.rescuecodec.app.ui.screens.SettingsScreen
import org.rescuecodec.app.ui.theme.Rc
import org.rescuecodec.app.ui.theme.RescueTheme
import org.rescuecodec.app.ui.theme.ThemeMode
import java.util.Locale

/**
 * The places in the app. Three tabs (Chats, Tasks, Radio); Settings opens from the gear in the top bar.
 */
enum class Screen(val labelRes: Int, val iconRes: Int, val tag: String) {
    CHATS(R.string.chats_title, R.drawable.ic_chat, "tab_chats"),
    TASKS(R.string.nav_tasks, R.drawable.ic_assignment_turned_in, "tab_tasks"),
    RADIO(R.string.nav_radio, R.drawable.ic_radio_tab, "tab_radio"),
    SETTINGS(R.string.nav_settings, R.drawable.ic_settings, "settings_button"),
    ;

    val isTab: Boolean get() = this != SETTINGS

    companion object {
        /**
         * The `--es screen X` launch extra and notification taps. Old names still work: REPORT, FEED and TEAM open
         * Chats, ACTIONS opens Tasks, STATS opens Radio (the airtime numbers live there now).
         */
        fun from(name: String?): Screen? = when (name?.trim()?.uppercase()) {
            "CHATS", "CHAT", "TEAM", "REPORT", "FEED", "HISTORY" -> CHATS
            "TASKS", "ACTIONS" -> TASKS
            "RADIO", "CONNECT", "STATS", "AIRTIME" -> RADIO
            "SETTINGS" -> SETTINGS
            else -> null
        }
    }
}

private val TABS = listOf(Screen.CHATS, Screen.TASKS, Screen.RADIO)

/** A screen (and for Chats, a chat by its key) asked for by an intent while the app is open. [n] makes each one new. */
data class NavRequest(val n: Int, val screen: Screen, val chat: String? = null)

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

/** The whole app: theme, language, top bar, the three tabs and Settings. */
@Composable
fun RescueRoot(graph: AppGraph, initial: Screen = Screen.CHATS, navRequest: NavRequest? = null, initialChat: String? = null) {
    val app = LocalContext.current.applicationContext as Application
    val factory = remember(graph) { AppViewModelFactory(app, graph) }
    val settings: SettingsViewModel = viewModel(factory = factory)
    val lang by settings.language.collectAsStateWithLifecycle()
    WithAppLanguage(lang) {
        // Light only for now: always Day, whatever the phone's dark setting. The night palette stays in the theme.
        RescueTheme(ThemeMode.DAY) {
            // Test tags become resource ids, so uiautomator and the screenshot script can find buttons.
            Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = MaterialTheme.colorScheme.background) {
                RescueScaffold(graph, factory, settings, lang, initial, navRequest, initialChat)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RescueScaffold(
    graph: AppGraph,
    factory: AppViewModelFactory,
    settings: SettingsViewModel,
    lang: String,
    initial: Screen,
    navRequest: NavRequest?,
    initialChat: String?,
) {
    val connection: ConnectionViewModel = viewModel(factory = factory)
    val tasksVm: TasksViewModel = viewModel(factory = factory)
    val messages: MessagesViewModel = viewModel(factory = factory)
    val stats: StatsViewModel = viewModel(factory = factory)
    val report: ReportViewModel = viewModel(factory = factory)
    val chatList: ChatListViewModel = viewModel(factory = factory)
    // Chats is the chat list or one chat. For speed in an emergency the app opens straight into the last chat used;
    // back goes to the list.
    var openChat by rememberSaveable { mutableStateOf<String?>(initialChat ?: chatList.startChat().key) }
    val chatId = openChat?.let { ChatId.parse(it) }
    val chat: ChatViewModel? = chatId?.let { id ->
        viewModel(key = id.key, factory = ChatViewModelFactory(LocalContext.current.applicationContext as Application, graph, id))
    }
    val chatTitle = chat?.title?.collectAsStateWithLifecycle()?.value

    var screen by rememberSaveable { mutableStateOf(initial.name) }
    var lastTab by rememberSaveable { mutableStateOf(if (initial.isTab) initial.name else Screen.CHATS.name) }
    val current = Screen.valueOf(screen)
    fun go(s: Screen) {
        if (s.isTab) lastTab = s.name
        screen = s.name
    }
    LaunchedEffect(navRequest) { navRequest?.let { r -> r.chat?.let { openChat = it }; go(r.screen) } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val link = org.rescuecodec.app.Demo.link(connection.state.collectAsStateWithLifecycle().value)
    val channel by connection.channel.collectAsStateWithLifecycle()
    val tasks by tasksVm.tasks.collectAsStateWithLifecycle()
    val waiting by messages.waiting.collectAsStateWithLifecycle()
    val chats by chatList.chats.collectAsStateWithLifecycle()
    val names by graph.nodes.nodes.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // With the keyboard up the bottom bar goes away and a slim SOS strip rides on the keyboard.
    val imeUp = WindowInsets.isImeVisible
    val openCount = tasks.count { !it.local && (it.state == "open" || (it.type == "mayday" && it.state != "done")) }
    val unread = chats.orEmpty().sumOf { it.unread }
    val inChat = current == Screen.CHATS && chat != null

    // In-app cue for a new message or task while the app is open: the phone's own tone and a haptic tick.
    val view = LocalView.current
    LaunchedEffect(graph) {
        graph.teamAlerts.cues.collect { kind ->
            org.rescuecodec.app.service.InAppSound.play(kind)
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // Back: from a chat to the chat list, from Settings to the last tab, from Tasks or Radio to Chats, then out.
    BackHandler(enabled = current != Screen.CHATS || openChat != null) {
        when {
            current == Screen.CHATS -> openChat = null
            current.isTab -> go(Screen.CHATS)
            else -> screen = lastTab
        }
    }

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
            MaydayBanner(tasksVm, tasks, waiting, lang, names) { go(Screen.TASKS) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (current) {
                    Screen.CHATS -> if (chat == null) ChatListScreen(chatList, tasks, lang, onOpen = { openChat = it.key })
                    else ChatScreen(chat, messages, tasksVm, report, tasks, link, channel, snackbar, lang, onOpenRadio = { go(Screen.RADIO) })
                    Screen.TASKS -> TasksScreen(tasksVm, tasks, snackbar, lang)
                    Screen.RADIO -> RadioScreen(connection, stats, chats.orEmpty(), link, snackbar, lang)
                    Screen.SETTINGS -> SettingsScreen(settings, connection, lang)
                }
            }
            // SOS on every screen, one hold away: the red strip above the tabs, slimmer on top of the keyboard.
            SosBar(onSos, slim = imeUp)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 64.dp)) },
        topBar = {
            val (dot, status, tint) = statusOf(link, waiting.size, if (inChat) names.keys.count { it != graph.settings.lastOwnNode } else 0)
            val directFallback = (chatId as? ChatId.Direct)?.let { stringResource(R.string.sender_node, TeamAlerts.shortNode(it.node)) }
            TopBar(
                title = when {
                    inChat -> chatTitle ?: directFallback ?: stringResource(R.string.chats_title)
                    else -> stringResource(current.labelRes)
                },
                status = status, dot = dot, tint = tint,
                onBack = if (inChat) ({ openChat = null }) else null,
                inSettings = current == Screen.SETTINGS,
                onSettings = { if (current == Screen.SETTINGS) screen = lastTab else go(Screen.SETTINGS) },
                onStatus = { go(Screen.RADIO) },
            )
        },
        bottomBar = {
            if (!wide && !imeUp && !inChat) {
                BottomTabs(current, unread, openCount) { go(it) }
            }
        },
    ) { padding ->
        if (wide && !inChat) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxHeight()) {
                    TABS.forEach { s ->
                        NavigationRailItem(
                            selected = current == s,
                            onClick = { go(s) },
                            modifier = Modifier.testTag(s.tag),
                            icon = {
                                BadgedBox(badge = {
                                    val n = if (s == Screen.TASKS) openCount else if (s == Screen.CHATS) unread else 0
                                    if (n > 0) Badge(containerColor = Rc.colors.openSolid, contentColor = Color(0xFF1D1200)) { Text(n.toString()) }
                                }) { RcIcon(s.iconRes, null, tint = Rc.colors.ink3) }
                            },
                            label = { Text(stringResource(s.labelRes), style = MaterialTheme.typography.labelLarge) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = Rc.colors.acceptedSoft,
                                selectedIconColor = Rc.colors.ink3,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                content(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)).imePadding())
            }
        } else if (inChat || wide) {
            content(Modifier.padding(padding).fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)).imePadding())
        } else {
            content(Modifier.padding(padding).fillMaxSize().imePadding())
        }
    }
}

private data class StatusLook(val dot: Color, val text: String, val tint: Color)

/** The words and colours of the status pill. [inReach] > 0 inside a chat: "5 radios in reach" while connected. */
@Composable
private fun statusOf(link: LinkState, queued: Int, inReach: Int): StatusLook {
    val c = Rc.colors
    val noRadio = if (queued > 0) stringResource(R.string.link_radio_none_queued, queued) else stringResource(R.string.link_radio_none)
    return when (link) {
        is LinkState.Connected -> StatusLook(
            c.doneSolid,
            if (inReach > 0) stringResource(R.string.radios_in_reach, inReach) else stringResource(R.string.link_radio_connected, link.nodeName ?: link.target.label),
            if (c.dark) Color(0xFF102018) else Color(0xFFE2EFE2),
        )
        is LinkState.Connecting -> StatusLook(c.openSolid, stringResource(R.string.link_connecting), c.openSoft)
        is LinkState.Scanning -> StatusLook(c.openSolid, stringResource(R.string.link_scanning), c.openSoft)
        is LinkState.Error -> StatusLook(c.mayday, stringResource(R.string.link_error), c.maydaySoft)
        LinkState.Disconnected -> if (queued > 0) StatusLook(c.mayday, noRadio, c.maydaySoft) else StatusLook(MaterialTheme.colorScheme.outline, noRadio, MaterialTheme.colorScheme.surfaceContainerHigh)
    }
}

/**
 * The overnight top bar: a status pill ("● Radio connected, D-Node 2" and the screen name in condensed capitals; tap:
 * Radio) and the gear for Settings. Inside a chat: a back arrow, the pill with the chat name, no gear.
 */
@Composable
private fun TopBar(
    title: String,
    status: String,
    dot: Color,
    tint: Color,
    onBack: (() -> Unit)?,
    inSettings: Boolean,
    onSettings: () -> Unit,
    onStatus: () -> Unit,
) {
    val desc = stringResource(R.string.status_desc, status)
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .fillMaxWidth().padding(start = if (onBack != null) 0.dp else 12.dp, end = if (onBack != null) 12.dp else 4.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (onBack != null) {
                Surface(onClick = onBack, color = Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(48.dp).testTag("chat_back")) {
                    Box(contentAlignment = Alignment.Center) { RcIcon(R.drawable.ic_arrow_back, stringResource(R.string.chat_open_list)) }
                }
            }
            Surface(
                onClick = onStatus,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("status_pill").semantics { contentDescription = "$title. $desc" },
                shape = MaterialTheme.shapes.medium,
                color = tint,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(dot, size = 11.dp)
                    Text(
                        status, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("status_text"),
                    )
                    Text(
                        title.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, letterSpacing = 0.5.sp),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp).testTag("top_title"),
                    )
                }
            }
            if (onBack == null) Surface(
                onClick = onSettings,
                modifier = Modifier.size(48.dp).testTag("settings_button"),
                shape = MaterialTheme.shapes.medium,
                color = if (inSettings) MaterialTheme.colorScheme.primary else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    RcIcon(R.drawable.ic_settings, stringResource(R.string.nav_settings), tint = if (inSettings) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The three tabs, overnight style: card colour, a hairline on top, a soft blue pill behind the chosen icon, amber counts. */
@Composable
private fun BottomTabs(current: Screen, unread: Int, open: Int, onGo: (Screen) -> Unit) {
    val c = Rc.colors
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("nav_bar")) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Hairline()
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                TABS.forEach { s ->
                    val on = current == s
                    val n = when (s) { Screen.CHATS -> unread; Screen.TASKS -> open; else -> 0 }
                    val label = stringResource(s.labelRes)
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected = on, role = Role.Tab, onClick = { onGo(s) })
                            .semantics { contentDescription = if (n > 0) "$label, $n" else label }
                            .testTag(s.tag),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Box(
                                Modifier.size(width = 56.dp, height = 28.dp).background(if (on) c.acceptedSoft else Color.Transparent, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) { RcIcon(s.iconRes, null, tint = if (on) c.accepted else c.ink3, size = 22.dp) }
                            if (n > 0) Box(
                                Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-4).dp).heightIn(min = 19.dp).widthIn(min = 19.dp)
                                    .background(c.openSolid, RoundedCornerShape(10.dp)).padding(horizontal = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (n > 99) "99+" else n.toString(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), color = Color(0xFF1D1200)) }
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = if (on) MaterialTheme.colorScheme.onSurface else c.ink3, maxLines = 1)
                    }
                }
            }
        }
    }
}

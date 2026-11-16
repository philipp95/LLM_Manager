package com.llmmanager.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmmanager.android.data.ChatView
import kotlinx.coroutines.launch

/**
 * The bar on the left of a repository: its sessions as branches, each with its pull request and
 * checks. It slides in from the left edge, by a swipe, by the tab on that edge or by the
 * sessions row in the page; choosing a session shows it in the middle and slides the bar away.
 */
@Composable
fun SessionDrawer(chat: ChatScreen, content: @Composable (openSessions: () -> Unit) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val open: () -> Unit = { scope.launch { drawer.open() } }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Palette.harbor, modifier = Modifier.widthIn(max = 336.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    SectionTitle("Sessions")
                    Spacer(Modifier.height(10.dp))
                    SessionTree(chat.project, chat.sessions, chat.selected?.id) { id ->
                        chat.select(id)
                        scope.launch { drawer.close() }
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            content(open)
            EdgeTab(chat.sessions.count { !it.merged && !it.closed }, Modifier.align(Alignment.CenterStart), open)
        }
    }
}

/** The tab on the left edge that pulls the sessions in; it carries how many are open. */
@Composable
private fun EdgeTab(openSessions: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier.width(22.dp).background(Palette.rust, shape)
            .clickable(onClickLabel = "Show the sessions", role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Glyphs.branch, contentDescription = "Sessions", tint = Palette.onRust, modifier = Modifier.size(14.dp))
        if (openSessions > 0) {
            Text(openSessions.toString(), color = Palette.onRust, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The row in the page that says which session is shown and opens the bar with all of them. */
@Composable
fun SessionsBar(sessions: List<ChatView>, selected: ChatView?, onClick: () -> Unit) {
    val open = sessions.filter { !it.merged && !it.closed }
    val pulls = open.count { it.pullRequest?.state == "open" }
    val shape = RoundedCornerShape(16.dp)
    val summary = when {
        sessions.isEmpty() -> "No sessions yet"
        pulls > 0 -> plural(open.size, "open session", "open sessions") + " · " + plural(pulls, "pull request", "pull requests")
        else -> plural(open.size, "open session", "open sessions")
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.harbor2, shape).border(1.dp, Palette.harborLine, shape)
            .clickable(onClickLabel = "Show the sessions", role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.branch, contentDescription = null, tint = Palette.onHarbor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                selected?.title ?: "New session", color = Palette.onHarbor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(summary, color = Palette.onHarbor2, fontSize = 12.5.sp)
        }
        Text("All sessions", color = Palette.onHarbor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

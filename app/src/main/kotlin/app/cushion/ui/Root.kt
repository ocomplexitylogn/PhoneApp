package app.cushion.ui

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.cushion.R
import app.cushion.core.auth.AuthState
import app.cushion.data.AppGraph
import app.cushion.ui.screens.BrowseScreen
import app.cushion.ui.screens.ConnectScreen
import app.cushion.ui.screens.EarningsScreen
import app.cushion.ui.screens.ExposureScreen
import app.cushion.ui.screens.IdeasScreen
import app.cushion.ui.screens.SettingsScreen
import app.cushion.ui.screens.ShortlistScreen
import app.cushion.ui.screens.SignInScreen
import app.cushion.ui.screens.TickerScreen
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object Routes {
    const val IDEAS = "ideas"
    const val BROWSE = "browse"
    const val EARNINGS = "earnings"
    const val SHORTLIST = "shortlist"
    const val EXPOSURE = "exposure"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val SIGN_IN = "signin"
    const val TICKER = "ticker/{symbol}?expiry={expiry}"

    fun ticker(symbol: String, expiry: LocalDate? = null): String =
        "ticker/${Uri.encode(symbol)}" + if (expiry != null) "?expiry=$expiry" else ""
}

private data class Tab(val route: String, @DrawableRes val icon: Int, val label: String)

private val tabs = listOf(
    Tab(Routes.IDEAS, R.drawable.ic_nav_list, "Ideas"),
    Tab(Routes.SHORTLIST, R.drawable.ic_nav_clipboard, "Shortlist"),
    Tab(Routes.EXPOSURE, R.drawable.ic_nav_chart, "Exposure"),
    Tab(Routes.SETTINGS, R.drawable.ic_nav_command, "Settings"),
)

private fun tabIndexFor(route: String?): Int = when (route) {
    Routes.SHORTLIST -> 1
    Routes.EXPOSURE -> 2
    Routes.SETTINGS -> 3
    else -> 0
}

@Composable
fun CushionRoot(graph: AppGraph) {
    val auth by graph.tokens.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    if (auth is AuthState.Loading) {
        Box(Modifier.fillMaxSize().background(Palette.Ink))
        return
    }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val start = remember { if (auth is AuthState.NotConfigured && !settings.demoMode) Routes.CONNECT else Routes.IDEAS }
    val inSetup = route == Routes.CONNECT || route == Routes.SIGN_IN

    Column(Modifier.fillMaxSize().background(Palette.Ink).statusBarsPadding()) {
        if (settings.demoMode) {
            StatusStrip("Demo data. Nothing here is a live price.")
        } else if (!inSetup) {
            authMessage(auth)?.let { message ->
                StatusStrip(message, onClick = { nav.navigate(Routes.CONNECT) })
            }
        }
        Box(Modifier.weight(1f).imePadding()) {
            NavHost(navController = nav, startDestination = start) {
                composable(Routes.IDEAS) { IdeasScreen(graph, nav) }
                composable(Routes.BROWSE) { BrowseScreen(graph, nav) }
                composable(Routes.EARNINGS) { EarningsScreen(graph, nav) }
                composable(
                    Routes.TICKER,
                    arguments = listOf(
                        navArgument("symbol") { type = NavType.StringType },
                        navArgument("expiry") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    val symbol = entry.arguments?.getString("symbol").orEmpty()
                    val expiry = entry.arguments?.getString("expiry")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    TickerScreen(graph, nav, symbol, expiry)
                }
                composable(Routes.SHORTLIST) { ShortlistScreen(graph, nav) }
                composable(Routes.EXPOSURE) { ExposureScreen(graph, nav) }
                composable(Routes.SETTINGS) { SettingsScreen(graph, nav) }
                composable(Routes.CONNECT) { ConnectScreen(graph, nav) }
                composable(Routes.SIGN_IN) { SignInScreen(graph, nav) }
            }
        }
        if (!inSetup) {
            BottomBar(selected = tabIndexFor(route), onSelect = { nav.switchTab(tabs[it].route) })
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun authMessage(auth: AuthState): String? = when (auth) {
    is AuthState.NotConfigured -> "Connect Schwab to see live data. Tap to set up."
    is AuthState.SignInRequired -> "${auth.reason}. Tap to sign in."
    is AuthState.Ready -> auth.refreshExpiresAtMs?.let { expires ->
        val left = expires - System.currentTimeMillis()
        val hours = TimeUnit.MILLISECONDS.toHours(left)
        when {
            left <= 0 -> "Your Schwab sign-in has expired. Tap to sign in."
            hours < 48 -> "Schwab sign-in expires in ${hours.coerceAtLeast(1)}h. Tap to renew."
            else -> null
        }
    }
    else -> null
}

@Composable
private fun StatusStrip(text: String, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().background(Palette.PillFill)
    Text(
        text = text,
        style = Type.SmallStrong,
        color = Palette.Lime,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Ink)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.NavTrack))
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(62.dp)
                .clip(CircleShape)
                .background(Palette.NavTrack)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Palette.NavSelected else Palette.NavTrack)
                        .clickable(onClickLabel = tab.label) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(tab.icon),
                        contentDescription = tab.label,
                        tint = if (isSelected) Palette.Lime else Palette.Text,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

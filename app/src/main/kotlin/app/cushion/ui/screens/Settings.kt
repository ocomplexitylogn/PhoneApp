package app.cushion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cushion.BuildConfig
import app.cushion.core.auth.AuthState
import app.cushion.core.screen.ScreenConfig
import app.cushion.data.AppGraph
import app.cushion.ui.Routes
import app.cushion.ui.components.Card
import app.cushion.ui.components.PrimaryButton
import app.cushion.ui.components.SecondaryButton
import app.cushion.ui.components.TopBar
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun FieldBox(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = Type.Small, color = Palette.Secondary, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = Type.Body,
            cursorBrush = SolidColor(Palette.Lime),
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.Raised)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = Type.Body, color = Palette.Muted)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Label)
            detail?.let { Text(it, style = Type.Small, color = Palette.Secondary) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Ink,
                checkedTrackColor = Palette.Lime,
                uncheckedThumbColor = Palette.Secondary,
                uncheckedTrackColor = Palette.Raised,
                uncheckedBorderColor = Palette.Raised,
            ),
        )
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, nav: NavHostController) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val auth by graph.tokens.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var account by rememberSaveable { mutableStateOf(settings.accountValue?.let { java.math.BigDecimal(it).toPlainString() }.orEmpty()) }
    var nameCap by rememberSaveable { mutableStateOf(settings.perNameCapPct.toString()) }
    var sectorCap by rememberSaveable { mutableStateOf(settings.sectorCapPct.toString()) }
    var minDte by rememberSaveable { mutableStateOf(settings.screen.minDte.toString()) }
    var maxDte by rememberSaveable { mutableStateOf(settings.screen.maxDte.toString()) }
    var cushion by rememberSaveable { mutableStateOf(settings.screen.minCushionSigma.toString()) }
    var leveraged by rememberSaveable { mutableStateOf(settings.screen.minCushionSigmaLeveraged.toString()) }
    var minOi by rememberSaveable { mutableStateOf(settings.screen.minOpenInterest.toString()) }
    var spread by rememberSaveable { mutableStateOf((settings.screen.maxSpreadPct * 100).toString()) }
    var universe by rememberSaveable { mutableStateOf(settings.universe.joinToString(", ")) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Settings")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card {
                Text("Schwab", style = Type.CardTitle, modifier = Modifier.padding(bottom = 4.dp))
                Text(connectionText(auth, settings.demoMode), style = Type.Body, color = Palette.Secondary)
                SettingSwitch("Demo data", "Sample prices, for trying the app with no account.", settings.demoMode) { on ->
                    graph.settings.update { it.copy(demoMode = on) }
                    graph.scans.invalidate()
                }
                Box(Modifier.height(4.dp))
                SecondaryButton("Sign in again", { nav.navigate(Routes.CONNECT) })
                if (auth !is AuthState.NotConfigured) {
                    Box(Modifier.height(8.dp))
                    SecondaryButton("Disconnect and erase keys", {
                        scope.launch {
                            graph.tokens.signOut()
                            graph.scans.invalidate()
                        }
                    })
                }
            }
            Card {
                Text("Account", style = Type.CardTitle, modifier = Modifier.padding(bottom = 4.dp))
                FieldBox("Account value", account, { account = it; saved = false }, keyboard = KeyboardType.Decimal, placeholder = "Sizes contracts and caps")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldBox("Name cap %", nameCap, { nameCap = it; saved = false }, Modifier.weight(1f), KeyboardType.Decimal)
                    FieldBox("Sector cap %", sectorCap, { sectorCap = it; saved = false }, Modifier.weight(1f), KeyboardType.Decimal)
                }
            }
            Card {
                Text("Scan rules", style = Type.CardTitle, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldBox("Min DTE", minDte, { minDte = it; saved = false }, Modifier.weight(1f), KeyboardType.Number)
                    FieldBox("Max DTE", maxDte, { maxDte = it; saved = false }, Modifier.weight(1f), KeyboardType.Number)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldBox("Min cushion σ", cushion, { cushion = it; saved = false }, Modifier.weight(1f), KeyboardType.Decimal)
                    FieldBox("Leveraged σ", leveraged, { leveraged = it; saved = false }, Modifier.weight(1f), KeyboardType.Decimal)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FieldBox("Min open interest", minOi, { minOi = it; saved = false }, Modifier.weight(1f), KeyboardType.Number)
                    FieldBox("Max spread %", spread, { spread = it; saved = false }, Modifier.weight(1f), KeyboardType.Decimal)
                }
                FieldBox("Symbols to scan", universe, { universe = it; saved = false }, singleLine = false)
                PrimaryButton(
                    text = if (saved) "Saved" else "Save and rescan",
                    enabled = !saved,
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        val d = ScreenConfig()
                        val low = minDte.toIntOrNull()?.coerceIn(0, 365) ?: d.minDte
                        val high = (maxDte.toIntOrNull() ?: d.maxDte).coerceIn(low, 365)
                        val symbols = universe.split(',', ' ', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
                        graph.settings.update { s ->
                            s.copy(
                                accountValue = account.replace(",", "").toDoubleOrNull()?.takeIf { it > 0.0 },
                                perNameCapPct = nameCap.toDoubleOrNull()?.coerceIn(0.1, 100.0) ?: s.perNameCapPct,
                                sectorCapPct = sectorCap.toDoubleOrNull()?.coerceIn(0.1, 100.0) ?: s.sectorCapPct,
                                universe = symbols.ifEmpty { s.universe },
                                screen = s.screen.copy(
                                    minDte = low,
                                    maxDte = high,
                                    minCushionSigma = cushion.toDoubleOrNull()?.coerceAtLeast(0.0) ?: d.minCushionSigma,
                                    minCushionSigmaLeveraged = leveraged.toDoubleOrNull()?.coerceAtLeast(0.0) ?: d.minCushionSigmaLeveraged,
                                    minOpenInterest = minOi.toLongOrNull()?.coerceAtLeast(0L) ?: d.minOpenInterest,
                                    maxSpreadPct = spread.toDoubleOrNull()?.div(100.0)?.coerceIn(0.0, 1.0) ?: d.maxSpreadPct,
                                ),
                            )
                        }
                        graph.scans.invalidate()
                        saved = true
                    },
                )
            }
            Text(
                "Cushion ${BuildConfig.VERSION_NAME}. Market data from Schwab, earnings dates from Nasdaq. " +
                    "This app reads data only and places no orders. Chance figures are model estimates, not guarantees, " +
                    "and nothing here is financial advice.",
                style = Type.Small,
                color = Palette.Muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

private fun connectionText(auth: AuthState, demo: Boolean): String = when {
    demo -> "Demo mode is on, so Schwab isn't being used."
    auth is AuthState.Ready -> auth.refreshExpiresAtMs?.let {
        "Connected. Sign-in lasts until ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}."
    } ?: "Connected. Schwab sign-ins last seven days; this token's start date isn't known."
    auth is AuthState.SignInRequired -> auth.reason
    else -> "Not connected."
}

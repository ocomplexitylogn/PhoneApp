package app.cushion.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cushion.R
import app.cushion.core.format.Fmt
import app.cushion.core.model.PutCall
import app.cushion.core.screen.ContractMetrics
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type

fun sideLabel(side: PutCall): String = if (side == PutCall.PUT) "Put" else "Call"

/** The strike row from the mock: title, ring, premium pill, add button. */
@Composable
fun ContractRow(
    metrics: ContractMetrics,
    onOpen: () -> Unit,
    onAdd: (() -> Unit)?,
    added: Boolean,
    title: String = "${Fmt.strike(metrics.contract.strike)} ${sideLabel(metrics.contract.putCall)}",
    subtitle: String = "${Fmt.percent(metrics.distancePct)} to strike",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.Row, maxLines = 1)
            Text(subtitle, style = Type.Small, color = Palette.Secondary, maxLines = 1)
        }
        ProbabilityRing(metrics.chanceWorthless)
        PremiumPill(Fmt.money(metrics.premium))
        if (onAdd != null) {
            CircleButton(
                icon = if (added) R.drawable.ic_check else R.drawable.ic_add,
                label = if (added) "On shortlist" else "Add to shortlist",
                onClick = onAdd,
                size = 30.dp,
                iconSize = 16.dp,
                background = Palette.Raised,
            )
        }
    }
}

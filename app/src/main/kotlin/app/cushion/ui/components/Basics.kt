package app.cushion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cushion.R
import app.cushion.core.format.Fmt
import app.cushion.ui.theme.Palette
import app.cushion.ui.theme.Type

val ScreenGutter = 20.dp
val CardShape = RoundedCornerShape(26.dp)

@Composable
fun CircleButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    background: Color = Palette.Control,
    tint: Color = Palette.Lime,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenGutter, vertical = 8.dp)
            .height(44.dp),
    ) {
        if (onBack != null) {
            CircleButton(R.drawable.ic_back, "Back", onBack, Modifier.align(Alignment.CenterStart))
        }
        Text(title, style = Type.Screen, modifier = Modifier.align(Alignment.Center))
        if (action != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Palette.Card)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SectionHeader(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = Palette.Lime, modifier = Modifier.size(24.dp))
        Text(title, style = Type.Section, modifier = Modifier.padding(start = 10.dp).weight(1f))
        if (onSeeAll != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("See all", style = Type.Label, color = Palette.Lime)
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = Palette.Lime,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Up or down colour, the mock's green and orange. */
fun moveColor(change: Double): Color = if (change < 0.0) Palette.Orange else Palette.Green

@Composable
fun MoveTriangle(change: Double, size: Dp = 12.dp) {
    Icon(
        painterResource(if (change < 0.0) R.drawable.ic_tri_down else R.drawable.ic_tri_up),
        contentDescription = if (change < 0.0) "Down" else "Up",
        tint = moveColor(change),
        modifier = Modifier.size(size),
    )
}

/** Circular "chance expires worthless" gauge. Green from 70%, amber below, orange under 50%. */
@Composable
fun ProbabilityRing(chance: Double?, modifier: Modifier = Modifier, size: Dp = 50.dp) {
    val fraction = (chance ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        chance == null -> Palette.Raised
        chance >= 0.70 -> Palette.Green
        chance >= 0.50 -> Palette.Amber
        else -> Palette.Orange
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Palette.Raised, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(color, -90f, 360f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(chance?.let(Fmt::wholePercent) ?: "–", style = Type.Ring)
    }
}

@Composable
fun PremiumPill(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .width(76.dp)
            .height(39.dp)
            .clip(shape)
            .background(Palette.PillFill)
            .border(1.5.dp, Palette.PillBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Row, color = Palette.Green, maxLines = 1)
    }
}

@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(CircleShape)
            .background(Palette.Raised)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Palette.Lime else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.Label, color = if (isSelected) Palette.Ink else Palette.Secondary)
            }
        }
    }
}

@Composable
fun SmallChip(text: String, modifier: Modifier = Modifier, color: Color = Palette.Secondary, background: Color = Palette.Raised) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, style = Type.Tiny, color = color, maxLines = 1)
    }
}

/** A tappable pill for lists like Top premiums: bold label then a green value. */
@Composable
fun ValueChip(label: String, value: String, onClick: () -> Unit, valueColor: Color = Palette.Lime) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(label, style = Type.Row)
        Text(value, style = Type.Label, color = valueColor)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(if (enabled) Palette.Lime else Palette.Raised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Label, color = if (enabled) Palette.Ink else Palette.Muted)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(Palette.Raised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Label)
    }
}

/** Empty, error and loading messages share this quiet centred block. */
@Composable
fun Notice(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = Type.Row, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, style = Type.Body, color = Palette.Secondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Palette.Text) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Body, color = Palette.Secondary)
        Text(value, style = Type.Label, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

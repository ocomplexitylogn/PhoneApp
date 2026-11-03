package app.cushion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cushion.core.repo.ChartData
import app.cushion.core.repo.Session
import app.cushion.ui.theme.Palette

private fun DrawScope.drawSessions(
    chart: ChartData,
    regularColor: Color,
    strokePx: Float,
    verticalPadPx: Float,
): (Int) -> Offset {
    val points = chart.points
    val low = chart.min
    val span = (chart.max - low).takeIf { it > 0.0 } ?: 1.0
    val height = size.height - verticalPadPx * 2f
    val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
    val at: (Int) -> Offset = { i ->
        val y = verticalPadPx + height * (1f - ((points[i].price - low) / span).toFloat())
        Offset(i * stepX, y)
    }
    var start = 0
    while (start < points.size - 1) {
        // Each run is one session; the joining segment takes the later point's colour.
        val session = points[start + 1].session
        var end = start + 1
        while (end + 1 < points.size && points[end + 1].session == session) end++
        val path = Path().apply {
            at(start).let { moveTo(it.x, it.y) }
            for (i in start + 1..end) at(i).let { lineTo(it.x, it.y) }
        }
        drawPath(
            path = path,
            color = if (session == Session.REGULAR) regularColor else Palette.Line,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        start = end
    }
    return at
}

/** The small intraday line on a Browse card. */
@Composable
fun Sparkline(chart: ChartData?, change: Double, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(62.dp)) {
        if (chart != null && chart.points.size >= 2) {
            drawSessions(chart, moveColor(change), 1.8.dp.toPx(), 2.dp.toPx())
        }
    }
}

/**
 * The large chart on the Ticker screen. Dots mark the open and the latest print, as
 * in the mock. Dragging across it reports the point under the finger.
 */
@Composable
fun PriceChart(
    chart: ChartData?,
    change: Double,
    onScrub: (Int?) -> Unit,
    scrubIndex: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 230.dp,
) {
    val count = chart?.points?.size ?: 0
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(count) {
                if (count < 2) return@pointerInput
                val indexAt = { x: Float -> ((x / size.width) * (count - 1)).toInt().coerceIn(0, count - 1) }
                detectHorizontalDragGestures(
                    onDragStart = { onScrub(indexAt(it.x)) },
                    onDragEnd = { onScrub(null) },
                    onDragCancel = { onScrub(null) },
                    onHorizontalDrag = { pointerChange, _ -> onScrub(indexAt(pointerChange.position.x)) },
                )
            },
    ) {
        if (chart == null || chart.points.size < 2) return@Canvas
        val at = drawSessions(chart, moveColor(change), 2.dp.toPx(), 12.dp.toPx())
        val dot = 6.dp.toPx()
        val open = chart.points.indexOfFirst { it.session == Session.REGULAR }
        if (open > 0) drawCircle(Palette.Line, dot, at(open))
        drawCircle(Palette.Line, dot, at(chart.points.lastIndex))
        if (scrubIndex != null && scrubIndex in chart.points.indices) {
            val p = at(scrubIndex)
            drawLine(Palette.Secondary, Offset(p.x, 0f), Offset(p.x, size.height), 1.dp.toPx())
            drawCircle(Palette.Text, dot, p)
        }
    }
}

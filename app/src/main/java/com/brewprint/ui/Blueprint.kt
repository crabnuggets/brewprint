@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brewprint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brewprint.R

internal val Overpass = FontFamily(
    Font(R.font.overpass_400, FontWeight.Normal), Font(R.font.overpass_500, FontWeight.Medium),
    Font(R.font.overpass_600, FontWeight.SemiBold), Font(R.font.overpass_700, FontWeight.Bold),
)
private val Kalam = FontFamily(Font(R.font.kalam_400))

/**
 * What the chosen theme adds beyond Material colors. Monochrome adds nothing, so every helper here falls back
 * to the monochrome look. Blueprint is graphite on gridded vellum: drafting lines frame the record, the user's
 * own words are set in pencil, and a red pencil marks only what to act on now or change next time.
 */
@Immutable
internal class Look(val blueprint: Boolean = false, val dark: Boolean = false) {
    /** The red pencil. */
    val accent: Color = if (dark) Color(0xFFEE7566) else Color(0xFFC4372C)
    val grid: Color = if (dark) Color(0x0D78AADC) else Color(0x246298CE)
    val gridMajor: Color = if (dark) Color(0x1F78AADC) else Color(0x4D6298CE)
    /** The heavy outer line and the double rules of a title block. */
    val heavy: Color = if (dark) Color(0x9EEEF0F1) else Color(0xD11F2226)
    /** The light rules between a title block's cells. */
    val rule: Color = if (dark) Color(0x29EEF0F1) else Color(0x2E1F2226)
    val tape: Color = if (dark) Color(0xCCD6CDA5) else Color(0xEBE7DEB6)
    val navRadius: Dp = if (blueprint) 16.dp else 29.dp
    val pillRadius: Dp = if (blueprint) 9.dp else 22.dp

    /** The user's own words: pencil in the blueprint, unchanged otherwise. */
    fun hand(style: TextStyle): TextStyle = if (!blueprint) style else style.copy(fontFamily = Kalam, fontWeight = FontWeight.Normal, fontSize = style.fontSize * 1.12f, lineHeight = style.fontSize * 1.45f, letterSpacing = 0.sp)
}

internal val LocalLook = staticCompositionLocalOf { Look() }

/** The color for a value to act on now, such as the pour target: red pencil in the blueprint. */
@Composable
internal fun targetColor(): Color = LocalLook.current.let { if (it.blueprint) it.accent else MaterialTheme.colorScheme.onSurface }

/**
 * The page background. The blueprint draws its 8 dp grid with a heavier line every 40 dp.
 * [originY] is where this piece sits on the page, so the grid lines up across the header, content and footer.
 */
@Composable
internal fun Modifier.paper(originY: () -> Float = { 0f }): Modifier {
    val look = LocalLook.current
    val background = MaterialTheme.colorScheme.background
    if (!look.blueprint) return background(background)
    return drawWithCache {
        val step = 8.dp.toPx()
        val line = 1.dp.toPx()
        onDrawBehind {
            drawRect(background)
            val origin = originY()
            var index = 0
            var x = 0f
            while (x < size.width) {
                drawRect(if (index % 5 == 0) look.gridMajor else look.grid, Offset(x, 0f), Size(line, size.height)); x += step; index++
            }
            // Rows count from the page's top, so a major line stays major as pieces move.
            index = kotlin.math.floor(origin / step).toInt()
            var y = index * step - origin
            while (y < size.height) {
                if (y > -line) drawRect(if (Math.floorMod(index, 5) == 0) look.gridMajor else look.grid, Offset(0f, y), Size(size.width, line))
                y += step; index++
            }
        }
    }
}

/** A heavy drafting border whose lines run a little past each corner, like lines ruled by hand. */
@Composable
internal fun Modifier.ruled(): Modifier {
    val look = LocalLook.current
    val fill = MaterialTheme.colorScheme.surface
    return drawBehind {
        val border = 2.dp.toPx()
        val over = 7.dp.toPx()
        val thin = 1.25.dp.toPx()
        drawRect(fill)
        drawRect(look.heavy, Offset(border / 2, border / 2), Size(size.width - border, size.height - border), style = Stroke(border))
        drawRect(look.heavy, Offset(-over, 0f), Size(size.width + over * 2, thin))
        drawRect(look.heavy, Offset(-over, size.height - thin), Size(size.width + over * 2, thin))
        drawRect(look.heavy, Offset(0f, -over), Size(thin, size.height + over * 2))
        drawRect(look.heavy, Offset(size.width - thin, -over), Size(thin, size.height + over * 2))
    }.padding(2.dp)
}

/** A drafting double rule: under a table's headings and before a title block's revision row. */
@Composable
internal fun DoubleRule(modifier: Modifier = Modifier) {
    val heavy = LocalLook.current.heavy
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val line = 1.3.dp.toPx()
        drawRect(heavy, Offset.Zero, Size(size.width, line))
        drawRect(heavy, Offset(0f, size.height - line), Size(size.width, line))
    }
}

/** A light rule between cells. */
@Composable
internal fun CellRule(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val rule = LocalLook.current.rule
    Box(modifier.then(if (vertical) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp)).background(rule))
}

/** One labelled value in a title block. [spoken] replaces the label and value for screen readers. */
internal class Cell(val label: String, val value: String, val unit: String = "", val spoken: String = "$label, $value $unit")

@Composable
private fun CellContent(cell: Cell, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.clearAndSetSemantics { contentDescription = cell.spoken.trim() }.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(cell.label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        // A unit that does not fit wraps under its value rather than squeezing it.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(cell.value, Modifier.alignByBaseline(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (cell.unit.isNotEmpty()) Text(cell.unit, Modifier.alignByBaseline(), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Cells in ruled rows of [columns], each value under its own label. [weights] widen the columns whose values run long. */
@Composable
internal fun CellGrid(cells: List<Cell>, columns: Int, modifier: Modifier = Modifier, weights: List<Float> = emptyList()) {
    // A narrow screen or large text halves the columns, then stacks the cells, so values are not squeezed.
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val room = maxWidth / scale
    val across = if (room >= 330.dp) columns else if (room >= 200.dp) maxOf(1, columns / 2) else 1
    Column(Modifier.fillMaxWidth()) {
        cells.chunked(across).forEachIndexed { row, items ->
            if (row > 0) CellRule()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                items.forEachIndexed { index, cell ->
                    if (index > 0) CellRule(vertical = true)
                    CellContent(cell, Modifier.weight(weights.takeIf { across == columns }?.getOrNull(index) ?: 1f))
                }
                repeat(across - items.size) { CellRule(vertical = true); Spacer(Modifier.weight(1f)) }
            }
        }
    }
    }
}

/**
 * A drawing's title block: setup and results in ruled cells, and an optional revision row beneath a double
 * rule for what changes next time.
 */
@Composable
internal fun TitleBlock(cells: List<Cell>, modifier: Modifier = Modifier, revision: (@Composable RowScope.() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().ruled()) {
        CellGrid(cells, 2)
        if (revision != null) {
            DoubleRule()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = revision)
        }
    }
}

/** Lets a strip at the end of a card run to the card's edges, past its [padding]. */
internal fun Modifier.bleed(padding: Dp): Modifier = layout { measurable, constraints ->
    val extra = padding.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2))
    layout(placeable.width - extra * 2, (placeable.height - extra).coerceAtLeast(0)) { placeable.place(-extra, 0) }
}

/** A strip of drafting tape holding a card to the page. Decorative; drawn only in the blueprint. */
@Composable
internal fun Taped(content: @Composable () -> Unit) {
    val look = LocalLook.current
    if (!look.blueprint) return content()
    Box {
        content()
        Canvas(Modifier.align(Alignment.TopCenter).offset(y = (-11).dp).size(84.dp, 22.dp).clearAndSetSemantics { }) {
            val w = size.width
            val h = size.height
            val tooth = w * .04f
            // Torn ends zigzag; the long edges are straight.
            val path = Path().apply {
                moveTo(0f, 0f); lineTo(w, 0f)
                for (i in 1..6) lineTo(if (i % 2 == 1) w - tooth else w, h * i / 6f)
                lineTo(0f, h)
                for (i in 5 downTo 0) lineTo(if (i % 2 == 1) tooth else 0f, h * i / 6f)
                close()
            }
            rotate(-2.5f) { drawPath(path, look.tape) }
        }
    }
}

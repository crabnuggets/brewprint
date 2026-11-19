package com.brewprint.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brewprint.data.Appearance

/** The soft fill behind secondary buttons and icon actions; no outlines on buttons. */
internal val ColorScheme.soft: Color get() = secondaryContainer
internal val ColorScheme.softPressed: Color get() = surfaceContainerHighest

@Composable
fun BrewprintTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    // Monochrome levels: titles are the darkest tone, labels a clearly lighter one (still 4.5:1 on cards).
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFF2F2F4), onPrimary = Color(0xFF111214),
        secondary = Color(0xFFCECED5), onSecondary = Color(0xFF161719),
        background = Color(0xFF0C0D0F), onBackground = Color(0xFFF7F7F8),
        surface = Color(0xFF16171A), onSurface = Color(0xFFF2F2F4),
        surfaceContainerLowest = Color(0xFF0C0D0F), surfaceContainerLow = Color(0xFF16171A),
        surfaceContainer = Color(0xFF1B1C20), surfaceContainerHigh = Color(0xFF24252A), surfaceContainerHighest = Color(0xFF34353B),
        surfaceTint = Color(0xFFF2F2F4),
        surfaceVariant = Color(0xFF24252A), onSurfaceVariant = Color(0xFF9EA0AA),
        outline = Color(0xFF777984), outlineVariant = Color(0xFF2C2D32),
        secondaryContainer = Color(0xFF2B2C32), onSecondaryContainer = Color(0xFFF7F7F8),
        inverseSurface = Color(0xFFE9E9EC), inverseOnSurface = Color(0xFF111214), inversePrimary = Color(0xFF111214),
    ) else lightColorScheme(
        primary = Color(0xFF111214), onPrimary = Color.White,
        secondary = Color(0xFF4E505A), onSecondary = Color.White,
        background = Color(0xFFF6F7F9), onBackground = Color(0xFF0B0C0E),
        surface = Color.White, onSurface = Color(0xFF0E0F12),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFC),
        surfaceContainer = Color(0xFFF0F0F4), surfaceContainerHigh = Color(0xFFECECF1), surfaceContainerHighest = Color(0xFFDFE0E6),
        surfaceTint = Color(0xFF121316),
        surfaceVariant = Color(0xFFF0F0F4), onSurfaceVariant = Color(0xFF686A74),
        outline = Color(0xFF898B96), outlineVariant = Color(0xFFE4E5EA),
        secondaryContainer = Color(0xFFE7E8ED), onSecondaryContainer = Color(0xFF0E0F12),
        inverseSurface = Color(0xFF2A2B30), inverseOnSurface = Color(0xFFF2F2F4), inversePrimary = Color(0xFFF2F2F4),
    )
    val base = Typography()
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontSize = 28.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.SemiBold),
            // Section titles are a step heavier than body text, so a title never reads as a label.
            titleLarge = base.titleLarge.copy(fontSize = 21.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontSize = 17.sp, letterSpacing = (-.1).sp, fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
            bodyMedium = base.bodyMedium.copy(letterSpacing = 0.sp, lineHeight = 21.sp),
            bodySmall = base.bodySmall.copy(letterSpacing = 0.sp),
            labelLarge = base.labelLarge.copy(fontSize = 15.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.sp),
            labelSmall = base.labelSmall.copy(letterSpacing = 0.sp),
        ),
        // Buttons use the small shape: softly rounded rectangles, not pills.
        shapes = Shapes(small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}

/** Whether TalkBack-style touch exploration is on, following changes while the app is open. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember(context) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var on by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** Tells the floating navigation whether the current page wants it shown, and how much room it takes. */
internal class Chrome {
    var navShown by mutableStateOf(true)
    var navSpace by mutableStateOf(0.dp)
    /** Height of the current page's pinned footer, so the Undo bar can sit above it. */
    var footerSpace by mutableStateOf(0.dp)
}
internal val LocalChrome = staticCompositionLocalOf { Chrome() }

private const val SCROLL_SLOP = 24f

@Composable
fun Page(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable () -> Unit)? = null, pinHeader: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scroll = remember(title) { ScrollState(0) }
    val chrome = LocalChrome.current
    var headerShown by remember(title) { mutableStateOf(true) }
    var headerHeight by remember { mutableIntStateOf(0) }
    // The header and the navigation step aside while the content scrolls down and come back on any scroll up.
    // The header overlays the content instead of resizing it, so hiding it never moves the scroll position.
    LaunchedEffect(scroll) {
        chrome.navShown = true
        var last = scroll.value
        var travel = 0f
        snapshotFlow { scroll.value to scroll.maxValue }.collect { (value, max) ->
            val delta = value - last
            last = value
            travel = if (delta == 0 || (delta > 0) != (travel > 0)) delta.toFloat() else travel + delta
            when {
                value <= headerHeight -> headerShown = true
                travel > SCROLL_SLOP -> headerShown = false
                travel < -SCROLL_SLOP -> headerShown = true
            }
            chrome.navShown = value == 0 || value >= max || travel < -SCROLL_SLOP || (chrome.navShown && travel <= SCROLL_SLOP)
        }
    }
    val shown = headerShown || pinHeader || rememberTouchExploration()
    val offset by animateFloatAsState(if (shown) 0f else -headerHeight.toFloat(), tween(280), label = "header")
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val scrolledUnder by remember(scroll) { derivedStateOf { scroll.value > headerHeight } }
    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val density = LocalDensity.current
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(with(density) { headerHeight.toDp() }))
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
                if (back == null) Spacer(Modifier.height(chrome.navSpace))
            }
            Row(
                Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.graphicsLayer { translationY = offset }
                    .background(MaterialTheme.colorScheme.background)
                    .then(if (scrolledUnder) Modifier.drawBehind { drawLine(outlineVariant, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx()) } else Modifier)
                    .padding(start = if (back == null) 24.dp else 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Text(title, style = if (back == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f).semantics { heading() })
                actions()
            }
        }
        // A hairline marks where content continues under the footer, so a cut-off line reads as scrollable.
        val density = LocalDensity.current
        val bottomPadding = with(density) { 24.dp.roundToPx() }
        val moreBelow by remember(scroll, bottomPadding) { derivedStateOf { scroll.maxValue - scroll.value > bottomPadding } }
        if (footer != null && moreBelow) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (footer != null) Box(Modifier.fillMaxWidth().onSizeChanged { chrome.footerSpace = with(density) { it.height.toDp() } }.background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp, vertical = 12.dp).animateContentSize(tween(300))) { footer() }
        else LaunchedEffect(Unit) { chrome.footerSpace = 0.dp }
    }
}

@Composable
internal fun FloatingNavigation(selectedTab: String, shown: Boolean, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    AnimatedVisibility(shown, modifier, enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)), exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200))) {
        Surface(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).testTag("bottom-navigation"),
            shape = RoundedCornerShape(29.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp).selectableGroup().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                listOf("Brew", "History", "Library").forEach { destination ->
                    val selected = destination == selectedTab
                    // The selected destination shows its name beside its icon; the others keep their names for screen readers.
                    Row(
                        Modifier.clip(RoundedCornerShape(22.dp))
                            .background(if (selected) MaterialTheme.colorScheme.soft else Color.Transparent)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                            .semantics { if (!selected) text = AnnotatedString(destination) }
                            .heightIn(min = 48.dp).widthIn(min = 76.dp)
                            .animateContentSize(tween(280))
                            .padding(start = if (selected) 14.dp else 0.dp, end = if (selected) 18.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(when (destination) {
                            "Brew" -> if (selected) Icons.Filled.Coffee else Icons.Outlined.Coffee
                            "History" -> if (selected) Icons.AutoMirrored.Filled.FormatListBulleted else Icons.AutoMirrored.Outlined.FormatListBulleted
                            else -> if (selected) Icons.AutoMirrored.Filled.LibraryBooks else Icons.AutoMirrored.Outlined.LibraryBooks
                        }, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selected) Text(destination, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
internal fun softFieldColors(): TextFieldColors {
    val colors = MaterialTheme.colorScheme
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surfaceVariant.copy(alpha = .4f),
        errorContainerColor = colors.surfaceVariant.copy(alpha = .4f),
        focusedBorderColor = colors.onSurface,
        // At least 3:1 against the fill, so the field reads as a control.
        unfocusedBorderColor = colors.outline,
        errorBorderColor = colors.error.copy(alpha = .6f),
        focusedLabelColor = colors.onSurfaceVariant,
        errorLabelColor = colors.onSurfaceVariant,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.animateContentSize(tween(300)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** A button shrinks slightly while pressed, so a tap feels physical. */
@Composable
internal fun Modifier.pressScale(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, tween(140), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
private fun softColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.soft, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    disabledContainerColor = MaterialTheme.colorScheme.soft.copy(alpha = .5f), disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
)

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(source), enabled = enabled, shape = MaterialTheme.shapes.small, interactionSource = source) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
internal fun SecondaryAction(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    SoftButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, Modifier.padding(vertical = 4.dp))
    }
}

/** A filled button sized by its caller, with the shared press feedback. */
@Composable
internal fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.small, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(onClick, modifier.pressScale(source), enabled = enabled, shape = shape, interactionSource = source, content = content)
}

/** A secondary button: a soft fill instead of an outline. */
@Composable
internal fun SoftButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = MaterialTheme.shapes.small, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(onClick, modifier.pressScale(source), enabled = enabled, shape = shape, colors = softColors(), elevation = null, contentPadding = contentPadding, interactionSource = source, content = content)
}

/** A smaller action that would otherwise be bare text; the soft fill makes it read as a button. */
@Composable
internal fun SoftTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(onClick, modifier.heightIn(min = 40.dp).pressScale(source), enabled = enabled, shape = MaterialTheme.shapes.small, colors = softColors(), elevation = null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), interactionSource = source, content = content)
}

/** An inline "Change …" action: a round icon button whose name is spoken and shown on long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(), modifier = modifier) {
        val source = remember { MutableInteractionSource() }
        FilledIconButton(onClick, Modifier.size(size).pressScale(source), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.soft, contentColor = MaterialTheme.colorScheme.onSecondaryContainer), interactionSource = source) {
            Icon(icon, label, Modifier.size(if (size < 40.dp) 16.dp else 18.dp))
        }
    }
}

/**
 * Swaps content with a short blur cross-fade when [key] changes: the old content blurs out, the new one
 * blurs in. Within the same key the content updates in place. Blur needs Android 12; before it, this is a fade.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun <T, K> BlurSwap(value: T, key: (T) -> K, modifier: Modifier = Modifier, alignment: Alignment = Alignment.TopStart, content: @Composable (T) -> Unit) {
    AnimatedContent(value, modifier, contentAlignment = alignment, contentKey = key, label = "blur-swap",
        transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.snap() } },
    ) { shown ->
        val blur by transition.animateFloat({ tween(if (targetState == EnterExitState.Visible) 340 else 150) }, label = "blur") { if (it == EnterExitState.Visible) 0f else 10f }
        val leaving = transition.targetState == EnterExitState.PostExit
        Box((if (leaving) Modifier.clearAndSetSemantics { } else Modifier).graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > .05f) renderEffect = BlurEffect(blur.dp.toPx(), blur.dp.toPx(), TileMode.Decal)
        }) { content(shown) }
    }
}

/** A counting number rolls to its next value instead of jumping. */
@Composable
internal fun <T> Roll(value: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    AnimatedContent(value, modifier, label = "roll", transitionSpec = {
        (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(180))) using SizeTransform(clip = false)
    }) { shown ->
        val leaving = transition.targetState == EnterExitState.PostExit
        Box(if (leaving) Modifier.clearAndSetSemantics { } else Modifier) { content(shown) }
    }
}

/** A section title: one style across screens, and a heading for TalkBack navigation. */
@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun DisclosureRow(label: String, expanded: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep, title = { Text("Discard changes?") }, confirmButton = { SoftTextButton(onClick = onDiscard) { Text("Discard") } }, dismissButton = { SoftTextButton(onClick = onKeep) { Text("Keep editing") } })
}

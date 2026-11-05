package com.brewprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brewprint.data.Appearance

@Composable
fun BrewprintTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFF2F2F4), onPrimary = Color(0xFF111214),
        secondary = Color(0xFFCECED5), onSecondary = Color(0xFF161719),
        background = Color(0xFF0C0D0F), onBackground = Color(0xFFF0F0F2),
        surface = Color(0xFF151619), onSurface = Color(0xFFF0F0F2),
        surfaceContainerLowest = Color(0xFF0C0D0F), surfaceContainerLow = Color(0xFF151619),
        surfaceContainer = Color(0xFF1B1C20), surfaceContainerHigh = Color(0xFF24252A), surfaceContainerHighest = Color(0xFF2D2E34),
        surfaceTint = Color(0xFFF2F2F4),
        surfaceVariant = Color(0xFF24252A), onSurfaceVariant = Color(0xFFBCBDC7),
        outline = Color(0xFF777984), outlineVariant = Color(0xFF34353C),
        secondaryContainer = Color(0xFF303139), onSecondaryContainer = Color.White,
    ) else lightColorScheme(
        primary = Color(0xFF121316), onPrimary = Color.White,
        secondary = Color(0xFF4E505A), onSecondary = Color.White,
        background = Color(0xFFF6F7F9), onBackground = Color(0xFF17191D),
        surface = Color.White, onSurface = Color(0xFF121316),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFC),
        surfaceContainer = Color(0xFFF0F0F4), surfaceContainerHigh = Color(0xFFECECF1), surfaceContainerHighest = Color(0xFFE5E5EB),
        surfaceTint = Color(0xFF121316),
        surfaceVariant = Color(0xFFF0F0F4), onSurfaceVariant = Color(0xFF595B66),
        outline = Color(0xFF898B96), outlineVariant = Color(0xFFE0E1E7),
        secondaryContainer = Color(0xFFECECF1), onSecondaryContainer = Color(0xFF121316),
    )
    val base = Typography()
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontSize = 28.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontSize = 22.sp, letterSpacing = (-.4).sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontSize = 17.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
            bodyMedium = base.bodyMedium.copy(letterSpacing = 0.sp, lineHeight = 21.sp),
            bodySmall = base.bodySmall.copy(letterSpacing = 0.sp),
            labelLarge = base.labelLarge.copy(fontSize = 15.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
            labelMedium = base.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.sp),
            labelSmall = base.labelSmall.copy(letterSpacing = 0.sp),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}

@Composable
fun Page(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scroll = remember(title) { ScrollState(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = if (back == null) 24.dp else 12.dp, end = 16.dp, top = 16.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, style = if (back == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            actions()
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        if (footer != null) Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = .8f), MaterialTheme.colorScheme.background))).padding(horizontal = 24.dp, vertical = 12.dp)) { footer() }
    }
}

@Composable
internal fun FloatingNavigation(selectedTab: String, onSelect: (String) -> Unit) {
    Surface(
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).testTag("bottom-navigation"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Brew", "History", "Library").forEach { destination ->
                val selected = destination == selectedTab
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(when (destination) {
                        "Brew" -> Icons.Outlined.Coffee
                        "History" -> Icons.AutoMirrored.Outlined.FormatListBulleted
                        else -> Icons.AutoMirrored.Outlined.MenuBook
                    }, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(destination, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
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
        focusedBorderColor = colors.onSurface.copy(alpha = .35f),
        unfocusedBorderColor = colors.outlineVariant,
        errorBorderColor = colors.outlineVariant,
        focusedLabelColor = colors.onSurfaceVariant,
        errorLabelColor = colors.onSurfaceVariant,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.small) { Text(text, modifier = Modifier.padding(vertical = 4.dp)) }
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
    AlertDialog(onDismissRequest = onKeep, title = { Text("Discard changes?") }, confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } }, dismissButton = { TextButton(onClick = onKeep) { Text("Keep editing") } })
}

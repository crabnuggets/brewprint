package com.brewprint.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        surfaceVariant = Color(0xFF24252A), onSurfaceVariant = Color(0xFFBCBDC7),
        outline = Color(0xFF777984), outlineVariant = Color(0xFF34353C),
        secondaryContainer = Color(0xFF303139), onSecondaryContainer = Color.White,
    ) else lightColorScheme(
        primary = Color(0xFF121316), onPrimary = Color.White,
        secondary = Color(0xFF4E505A), onSecondary = Color.White,
        background = Color(0xFFFAFAFC), onBackground = Color(0xFF121316),
        surface = Color.White, onSurface = Color(0xFF121316),
        surfaceVariant = Color(0xFFF0F0F4), onSurfaceVariant = Color(0xFF595B66),
        outline = Color(0xFF898B96), outlineVariant = Color(0xFFE0E1E7),
        secondaryContainer = Color(0xFFECECF1), onSecondaryContainer = Color(0xFF121316),
    )
    val base = Typography()
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
            labelLarge = base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content,
    )
}

@Composable
fun Page(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(title, style = if (back == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            actions()
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = MaterialTheme.shapes.small) { Text(text) }
}

@Composable
fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep, title = { Text("Discard changes?") }, confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } }, dismissButton = { TextButton(onClick = onKeep) { Text("Keep editing") } })
}

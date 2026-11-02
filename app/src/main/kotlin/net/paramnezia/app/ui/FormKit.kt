package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The building blocks of the «Добавить» and «Флот» screens and their dialogs: one look, one rhythm
 * (8 dp grid, 16 dp inside cards, 24 dp between sections), and input fields made for pasting.
 */

/** Icons the core icon set lacks, as Material Symbols paths (Apache 2.0). */
object Glyph {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
        }.build()

    val Paste by lazy {
        icon("Paste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z")
    }
    val Keyboard by lazy {
        icon("Keyboard", "M20,5L4,5c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2L22,7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2L11,8zM11,11h2v2h-2v-2zM8,8h2v2L8,10L8,8zM8,11h2v2L8,13v-2zM7,13L5,13v-2h2v2zM7,10L5,10L5,8h2v2zM16,17L8,17v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2L14,8h2v2zM19,13h-2v-2h2v2zM19,10h-2L17,8h2v2z")
    }
    val Qr by lazy {
        icon("Qr", "M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5L11,5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13L11,13zM17.5,6.5v3h-3v-3H17.5M19,5h-6v6h6V5L19,5zM13,13h1.5v1.5H13V13zM14.5,14.5H16V16h-1.5V14.5zM16,13h1.5v1.5H16V13zM13,16h1.5v1.5H13V16zM14.5,17.5H16V19h-1.5V17.5zM16,16h1.5v1.5H16V16zM17.5,14.5H19V16h-1.5V14.5zM17.5,17.5H19V19h-1.5V17.5zM22,7h-2V4h-3V2h5V7zM22,22v-5h-2v3h-3v2H22zM2,22h5v-2H4v-3H2V22zM2,2v5h2V4h3V2H2z")
    }
    val File by lazy {
        icon("File", "M14,2L6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2L18,22c1.1,0 2,-0.9 2,-2L20,8l-6,-6zM16,18L8,18v-2h8v2zM16,14L8,14v-2h8v2zM13,9L13,3.5L18.5,9L13,9z")
    }
    val Cloud by lazy {
        icon("Cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
    }
    val Server by lazy {
        icon("Server", "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1zM7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1zM7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z")
    }
    val PersonAdd by lazy {
        icon("PersonAdd", "M15,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM6,10L6,7L4,7v3L1,10v2h3v3h2v-3h3v-2L6,10zM15,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    }
    val Link by lazy {
        icon("Link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4L11,7L7,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9L7,15.1c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2L8,11v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4L13,17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    }
    val Globe by lazy {
        icon("Globe", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,19.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zM17.9,17.39c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1L8,12v-2h2c0.55,0 1,-0.45 1,-1L11,7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z")
    }
    val Phone by lazy {
        icon("Phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z")
    }
    val Key by lazy {
        icon("Key", "M12.65,10C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 5.65,-4H17v4h4v-4h2v-4H12.65zM7,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z")
    }
    val Eye by lazy {
        icon("Eye", "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z")
    }
    val Cpu by lazy {
        icon("Cpu", "M15,9H9v6h6V9zM13,13h-2v-2h2V13zM21,11V9h-2V7c0,-1.1 -0.9,-2 -2,-2h-2V3h-2v2h-2V3H9v2H7C5.9,5 5,5.9 5,7v2H3v2h2v2H3v2h2v2c0,1.1 0.9,2 2,2h2v2h2v-2h2v2h2v-2h2c1.1,0 2,-0.9 2,-2v-2h2v-2h-2v-2H21zM17,17H7V7h10V17z")
    }
    val Ram by lazy {
        icon("Ram", "M2,7h20v8h-2v2h-2v-2h-2v2h-2v-2h-2v2H8v-2H6v2H4v-2H2V7zM5,9v4h2V9H5zM9,9v4h2V9H9zM13,9v4h2V9h-2zM17,9v4h2V9h-2z")
    }
    val Disk by lazy {
        icon("Disk", "M2,20h20v-4L2,16v4zM4,17h2v2L4,19v-2zM2,4v4h20L22,4L2,4zM6,7L4,7L4,5h2v2zM2,14h20v-4L2,10v4zM4,11h2v2L4,13v-2z")
    }
    val Bolt by lazy {
        icon("Bolt", "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z")
    }
    val Tune by lazy {
        icon("Tune", "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z")
    }
    val Shield by lazy {
        icon("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z")
    }
}

/**
 * An input field made for pasting: the keyboard never opens by itself and does not cover the
 * field. «Вставить» puts the clipboard in at once; the keyboard button opens the keyboard for
 * typing. When a value is there, the paste button gives way to a clear button.
 *
 * [onPaste] handles the clipboard text itself (to fill several fields from one paste); by default
 * the text replaces the value. [display] shows a value in place of the raw text (a key's kind
 * rather than the key) until the person edits it.
 */
@Composable
fun PasteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    mono: Boolean = false,
    display: ((String) -> String?)? = null,
    onPaste: ((String) -> Unit)? = null,
    onEmptyClipboard: (() -> Unit)? = null,
    /** Open the keyboard as soon as the field appears (it was shown by a «type» button). */
    startTyping: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var typing by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    val shown = if (!typing) display?.invoke(value) else null
    if (startTyping) LaunchedEffect(Unit) {
        delay(150) // the field must be laid out before it can take focus
        runCatching { typing = true; focus.requestFocus(); keyboard?.show() }
    }

    fun paste() {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        if (text.isEmpty()) { onEmptyClipboard?.invoke(); return }
        if (onPaste != null) onPaste(text) else onValueChange(if (singleLine) text.lines().first().trim() else text)
    }

    // Narrow (dialogs) and multi-line fields get their buttons under the field, so the text keeps its room.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val below = !singleLine || maxWidth < 300.dp
        val actions: @Composable () -> Unit = {
            if (secret && value.isNotEmpty() && shown == null) {
                IconButton(onClick = { reveal = !reveal }, enabled = enabled) {
                    Icon(Glyph.Eye, if (reveal) "Скрыть" else "Показать", Modifier.size(20.dp),
                        tint = if (reveal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
        IconButton(enabled = enabled, onClick = {
            if (shown != null) onValueChange("")
            typing = true
            focus.requestFocus()
            keyboard?.show()
        }) {
            Icon(Glyph.Keyboard, "Ввести с клавиатуры", tint = if (typing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value.isEmpty()) {
            PastePill(enabled) { paste() }
        } else {
            IconButton(enabled = enabled, onClick = { onValueChange(""); reveal = false }) {
                Icon(Icons.Filled.Close, "Очистить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
        val trailing: (@Composable () -> Unit)? = if (below) null else {
            { Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) { actions() } }
        }
        Column {
            OutlinedTextField(
                value = shown ?: value,
                onValueChange = { if (shown == null) onValueChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { if (!it.isFocused) typing = false },
                enabled = enabled,
                // Read-only until the keyboard is asked for: a tap selects and copies, nothing pops up.
                readOnly = !typing,
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                supportingText = supporting?.let { { Text(it) } },
                isError = isError,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                shape = RoundedCornerShape(16.dp),
                textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                visualTransformation = if (secret && !reveal && shown == null) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret && !reveal) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                trailingIcon = trailing,
            )
            if (below) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

/** The labelled paste button inside a field: big enough to hit, obvious enough to find. */
@Composable
private fun PastePill(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(start = 10.dp, end = 14.dp),
        modifier = Modifier.height(40.dp),
    ) {
        Icon(Glyph.Paste, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Вставить", style = MaterialTheme.typography.labelLarge)
    }
}

/** Section heading: a short label above a group, with an optional count or action on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = Space.S, end = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** A list of rows in one rounded block, rows separated by a hairline gap (Material 3 expressive lists). */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

/** One row of a [Group]: leading icon in a soft circle, title and subtitle, a badge, trailing content. */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    monogram: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.then(if (onClick != null) Modifier.tactile(onClick = onClick) else Modifier)
                .heightIn(min = 64.dp).padding(start = Space.M, end = Space.S, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || monogram != null) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = accent)
                    else Text(monogram!!, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(Space.M))
            }
            Column(Modifier.weight(1f).padding(end = Space.S)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (badge != null) {
                        Spacer(Modifier.width(Space.S))
                        Pill(badge, accent)
                    }
                }
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
        }
    }
}

/** Small status pill. */
@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Square-ish tile with an icon over a label: a row of three equal choices (QR, file, keyboard). */
@Composable
fun SmallTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(20.dp), color = container,
        modifier = modifier.heightIn(min = 84.dp).clip(RoundedCornerShape(20.dp)).then(if (enabled) Modifier.tactile(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = Space.S), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.38f))
            Spacer(Modifier.height(Space.S))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) content else content.copy(alpha = 0.38f), maxLines = 1)
        }
    }
}

/**
 * A choice that opens in place: icon, title, what it means, and a chevron. The content below is
 * shown when [expanded], so secondary things stay one tap away without leaving the screen.
 */
@Composable
fun ExpandTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (expanded) BorderStroke(1.dp, accent.copy(alpha = 0.4f)) else null,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).tactile(onClick = onToggle).heightIn(min = 72.dp)
                    .padding(start = Space.M, end = Space.M, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = accent)
                }
                Spacer(Modifier.width(Space.M))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.KeyboardArrowDown, if (expanded) "Свернуть" else "Развернуть", Modifier.rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = Space.M, end = Space.M, bottom = Space.M), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

/** Screen title with a one-line explanation under it. */
@Composable
fun ScreenTitle(title: String, subtitle: String?, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Space.S), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke(this)
    }
}

/** A quiet note inside a section: icon and text on a tinted background. */
@Composable
fun Hint(text: String, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = color)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (icon != null) MaterialTheme.colorScheme.onSurfaceVariant else color)
    }
}

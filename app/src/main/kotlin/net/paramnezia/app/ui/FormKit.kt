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
 * The building blocks of the «Добавить» and «Семья» screens and their dialogs: one look, one rhythm
 * (8 dp grid, 16 dp inside cards, 24 dp between sections), and input fields made for pasting.
 */

/** Names the screens use for their icons; all come from the one Lucide set ([PnIcons]). */
object Glyph {
    val Paste get() = PnIcons.Paste
    val Keyboard get() = PnIcons.Keyboard
    val Qr get() = PnIcons.Qr
    val File get() = PnIcons.File
    val Cloud get() = PnIcons.Cloud
    val Server get() = PnIcons.Server
    val PersonAdd get() = PnIcons.PersonAdd
    val Link get() = PnIcons.Link
    val Globe get() = PnIcons.Globe
    val Phone get() = PnIcons.Phone
    val Key get() = PnIcons.Key
    val Eye get() = PnIcons.Eye
    val Cpu get() = PnIcons.Cpu
    val Ram get() = PnIcons.Ram
    val Disk get() = PnIcons.Disk
    val Bolt get() = PnIcons.Bolt
    val Tune get() = PnIcons.Tune
    val Wifi get() = PnIcons.Wifi
    val Shield get() = PnIcons.Shield
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
                Icon(PnIcons.Close, "Очистить", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val shape = RoundedCornerShape(24.dp)
    Column(modifier.fillMaxWidth().edge(shape).clip(shape), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
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
        modifier = modifier.heightIn(min = 84.dp).edge(RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).then(if (enabled) Modifier.tactile(onClick = onClick) else Modifier),
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
        modifier = modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.edge(RoundedCornerShape(24.dp))).animateContentSize(),
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
                Icon(PnIcons.ChevronDown, if (expanded) "Свернуть" else "Развернуть", Modifier.rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

package net.paramnezia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.paramnezia.core.profile.SshCredentials
import net.paramnezia.core.profile.SshPaste

/** What the person entered for SSH access to their own VPS. */
@Stable
class SshFormState {
    var host by mutableStateOf("")
    var port by mutableStateOf("22")
    var user by mutableStateOf("root")
    /** A password, or a private key (told apart by its PEM header). */
    var secret by mutableStateOf("")
    var name by mutableStateOf("")
    var more by mutableStateOf(false)

    val ready: Boolean get() = host.isNotBlank() && secret.isNotBlank()

    fun credentials(): SshCredentials {
        val key = SshPaste.isPrivateKey(secret)
        return SshCredentials(
            host.trim(), port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22, user.trim().ifBlank { "root" },
            password = if (key) "" else secret, privateKey = if (key) secret.trim() else "",
        )
    }

    /** Puts in whatever [f] has; returns what was filled, for the message. */
    fun fill(f: SshPaste.Found): List<String> {
        val done = ArrayList<String>()
        f.host?.let { host = it; done += "адрес" }
        f.user?.let { user = it; done += "логин"; if (it != "root") more = true }
        f.port?.let { port = it.toString(); done += "порт"; if (it != 22) more = true }
        f.privateKey?.let { secret = it; done += "SSH-ключ" } ?: f.password?.let { secret = it; done += "пароль" }
        return done
    }

    fun clear() { host = ""; port = "22"; user = "root"; secret = ""; name = ""; more = false }
}

/**
 * Address, password or key, and (one tap away) login, port and name. Pasting the hoster's whole
 * e-mail into any field fills all of them.
 */
@Composable
fun SshFields(state: SshFormState, enabled: Boolean, toast: (String) -> Unit, showName: Boolean = true) {
    val emptyClipboard = { toast("Буфер обмена пуст: сначала скопируйте данные сервера") }
    /** One paste may hold everything (an e-mail), or just the value for this field. */
    fun smartPaste(text: String, single: (String) -> Unit) {
        val found = SshPaste.parse(text)
        val several = listOfNotNull(found.host, found.user, found.password ?: found.privateKey).size >= 2
        if (several) {
            val filled = state.fill(found)
            toast("Заполнено: " + filled.joinToString(", "))
        } else single(text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint("Скопируйте письмо хостера целиком и нажмите «Вставить»: адрес, логин, пароль и порт заполнятся сами.", Icons.Filled.Info)
        PasteField(
            state.host, { state.host = it.trim() }, "IP или домен сервера",
            placeholder = "203.0.113.10", keyboardType = KeyboardType.Uri, enabled = enabled,
            onEmptyClipboard = emptyClipboard,
            onPaste = { text ->
                smartPaste(text) {
                    val f = SshPaste.parse(it)
                    state.host = f.host ?: it.lines().first().trim()
                    f.user?.let { u -> state.user = u; if (u != "root") state.more = true }
                    f.port?.let { p -> state.port = p.toString(); if (p != 22) state.more = true }
                }
            },
        )
        PasteField(
            state.secret, { state.secret = it }, "Пароль или SSH-ключ",
            secret = true, enabled = enabled, onEmptyClipboard = emptyClipboard,
            display = { if (SshPaste.isPrivateKey(it)) SshPaste.describeKey(it) + " · вставлен" else null },
            onPaste = { text ->
                smartPaste(text) {
                    val key = SshPaste.parse(it).privateKey
                    state.secret = key ?: it.lines().first().trim()
                }
            },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Логин ${state.user.ifBlank { "root" }} · порт ${state.port.ifBlank { "22" }}" +
                    if (showName && state.name.isNotBlank()) " · «${state.name}»" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            TextButton(enabled = enabled, onClick = { state.more = !state.more }) { Text(if (state.more) "Скрыть" else "Изменить") }
        }
        AnimatedVisibility(state.more) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasteField(state.user, { state.user = it.trim() }, "Логин", enabled = enabled, onEmptyClipboard = emptyClipboard)
                PasteField(state.port, { state.port = it.filter(Char::isDigit).take(5) }, "Порт SSH", enabled = enabled,
                    keyboardType = KeyboardType.Number, onEmptyClipboard = emptyClipboard)
                if (showName) PasteField(state.name, { state.name = it }, "Название (необязательно)", placeholder = "Например, «Нидерланды»",
                    enabled = enabled, onEmptyClipboard = emptyClipboard)
            }
        }
    }
}

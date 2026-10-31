package io.paramnezia.app.data

import android.content.Context
import io.paramnezia.deploy.HostKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** The app's single source of truth: an in-memory state written through to the [Vault]. */
class Repo(context: Context) {
    private val vault = Vault(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _state = MutableStateFlow(
        vault.read()?.let { runCatching { json.decodeFromString(AppState.serializer(), it) }.getOrNull() } ?: AppState(),
    )
    val state: StateFlow<AppState> = _state.asStateFlow()
    val current: AppState get() = _state.value

    /** Applies [change] and persists the result. Cheap enough to call from the main thread. */
    @Synchronized
    fun update(change: (AppState) -> AppState): AppState {
        val next = change(_state.value)
        if (next != _state.value) {
            _state.value = next
            vault.write(json.encodeToString(AppState.serializer(), next))
        }
        return next
    }

    val hostKeys = object : HostKeyStore {
        override fun get(host: String, port: Int) = current.hostKeys["$host:$port"]
        override fun put(host: String, port: Int, fingerprint: String) {
            update { it.copy(hostKeys = it.hostKeys + ("$host:$port" to fingerprint)) }
        }
    }
}

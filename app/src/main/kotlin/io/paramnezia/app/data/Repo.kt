package io.paramnezia.app.data

import android.content.Context
import io.paramnezia.deploy.HostKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The app's single source of truth: an in-memory state written through to the [Vault].
 *
 * The vault is decrypted on a background thread as soon as the process starts, so the first frame
 * never waits for the Keystore: the UI starts from [AppState] defaults and recomposes a few
 * milliseconds later. Code that needs the real state ([current], [update]) waits for the load.
 */
class Repo(context: Context) {
    private val vault = Vault(context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val loaded = CountDownLatch(1)

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _ready = MutableStateFlow(false)
    /** False until the stored state has been read (a few ms after process start). */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        thread(name = "pn-vault") {
            vault.read()?.let { runCatching { json.decodeFromString(AppState.serializer(), it) }.getOrNull() }?.let { _state.value = it }
            loaded.countDown()
            _ready.value = true
        }
    }

    val current: AppState get() { loaded.await(); return _state.value }

    /** Applies [change] and persists the result. */
    @Synchronized
    fun update(change: (AppState) -> AppState): AppState {
        loaded.await()
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

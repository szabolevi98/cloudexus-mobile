package net.levente.cloudexus.mobile.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.api.ApiException

sealed interface SessionState {
    data object Loading : SessionState

    /** [expired] is true when the server rejected the token, so the login screen can say why. */
    data class SignedOut(val expired: Boolean = false) : SessionState

    data class SignedIn(val session: Session) : SessionState
}

/** The one place that knows whether someone is signed in; every screen follows [state]. */
class SessionManager(
    private val store: SessionStore,
    private val api: ApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val current: Session? get() = (state.value as? SessionState.SignedIn)?.session

    /**
     * Restores the stored session and checks the token in the background. Only
     * a definite 401 signs out; without network the worker keeps the session,
     * because the warehouse wifi may simply be out of reach at start-up.
     */
    fun restore() {
        scope.launch {
            val session = store.load()
            if (session == null) {
                _state.value = SessionState.SignedOut()
                return@launch
            }
            _state.value = SessionState.SignedIn(session)
            refresh(session)
        }
    }

    /**
     * Reads the user again — after the server refused something (403), the
     * role may have changed on the web since sign-in; the home screen follows.
     */
    fun refreshUser() {
        val session = current ?: return
        scope.launch { refresh(session) }
    }

    private suspend fun refresh(session: Session) {
        try {
            val user = api.me(session.connection())
            if (user != session.user) {
                val updated = session.copy(user = user)
                store.save(updated)
                _state.value = SessionState.SignedIn(updated)
            }
        } catch (e: ApiException.Unauthorized) {
            expire()
        } catch (e: ApiException) {
            // Offline or server trouble: keep working with the stored session.
        }
    }

    /** Throws [ApiException] when the sign-in fails. */
    suspend fun signIn(baseUrl: String, username: String, password: String, deviceName: String): Session {
        val result = api.login(baseUrl, username, password, deviceName)
        val languages = try {
            api.languages(baseUrl, result.token)
        } catch (e: ApiException) {
            emptyList()
        }
        val session = Session(baseUrl, result.token, result.user, languages)
        store.save(session)
        _state.value = SessionState.SignedIn(session)
        return session
    }

    /** Revokes the token on the server when it can, and forgets it on the device either way. */
    fun signOut() {
        val session = current
        _state.value = SessionState.SignedOut()
        scope.launch {
            store.clear()
            if (session != null) {
                try {
                    api.logout(session.connection())
                } catch (e: ApiException) {
                    // The token expires on its own if the server cannot be reached now.
                }
            }
        }
    }

    /** The server no longer accepts the token (expired, password changed, user deactivated). */
    fun expire() {
        if (state.value is SessionState.SignedOut) return
        _state.value = SessionState.SignedOut(expired = true)
        scope.launch { store.clear() }
    }

    suspend fun lastBaseUrl(): String? = store.lastBaseUrl()

    suspend fun lastUsername(): String? = store.lastUsername()
}

package net.levente.cloudexus.mobile.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import net.levente.cloudexus.mobile.data.api.Connection
import net.levente.cloudexus.mobile.data.api.User
import java.util.Locale

/** A signed-in user on a server. */
data class Session(
    val baseUrl: String,
    val token: String,
    val user: User,
    /** Language codes the server offers; product names are asked for in the device language when it is one of them. */
    val serverLanguages: List<String>,
) {
    fun connection(): Connection {
        val device = Locale.getDefault().language.lowercase()
        return Connection(baseUrl, token, device.takeIf { it in serverLanguages })
    }
}

/** Persists the session between app starts; the token is stored encrypted. */
class SessionStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val json: Json,
) {
    suspend fun load(): Session? {
        val prefs = dataStore.data.first()
        val baseUrl = prefs[BASE_URL] ?: return null
        val token = prefs[TOKEN]?.let(cipher::decrypt) ?: return null
        val user = prefs[USER]?.let { runCatching { json.decodeFromString(User.serializer(), it) }.getOrNull() } ?: return null
        val languages = prefs[LANGUAGES].orEmpty().split(',').filter { it.isNotBlank() }
        return Session(baseUrl, token, user, languages)
    }

    suspend fun save(session: Session) {
        dataStore.edit {
            it[BASE_URL] = session.baseUrl
            it[LAST_BASE_URL] = session.baseUrl
            it[LAST_USERNAME] = session.user.username
            it[TOKEN] = cipher.encrypt(session.token)
            it[USER] = json.encodeToString(User.serializer(), session.user)
            it[LANGUAGES] = session.serverLanguages.joinToString(",")
        }
    }

    /** Signs out locally; the server address and username stay for the next sign-in. */
    suspend fun clear() {
        dataStore.edit {
            it.remove(BASE_URL)
            it.remove(TOKEN)
            it.remove(USER)
            it.remove(LANGUAGES)
        }
    }

    suspend fun lastBaseUrl(): String? = dataStore.data.first()[LAST_BASE_URL]

    suspend fun lastUsername(): String? = dataStore.data.first()[LAST_USERNAME]

    private companion object {
        val BASE_URL = stringPreferencesKey("session_base_url")
        val TOKEN = stringPreferencesKey("session_token")
        val USER = stringPreferencesKey("session_user")
        val LANGUAGES = stringPreferencesKey("session_languages")
        val LAST_BASE_URL = stringPreferencesKey("last_base_url")
        val LAST_USERNAME = stringPreferencesKey("last_username")
    }
}

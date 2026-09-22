package net.levente.cloudexus.mobile

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.scanner.ScannerSettings
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.data.session.SessionStore
import net.levente.cloudexus.mobile.data.session.TokenCipher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "cloudexus")

/** The app's few long-lived objects, built once; small enough to need no DI framework. */
class AppContainer(context: Context) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Outlives every screen; the session checks and sign-outs it runs must not be cancelled by navigation. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val api = ApiClient(http, json)
    val sessions = SessionManager(SessionStore(context.dataStore, TokenCipher(), json), api, appScope)
    val scannerSettings = ScannerSettings(context.dataStore)
}

class CloudexusApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.sessions.restore()
    }
}

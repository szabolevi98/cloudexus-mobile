package net.levente.cloudexus.mobile.ui.login

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.levente.cloudexus.mobile.BuildConfig
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.ApiClient
import net.levente.cloudexus.mobile.data.session.SessionManager
import net.levente.cloudexus.mobile.data.session.SessionState
import net.levente.cloudexus.mobile.data.session.SessionStore
import net.levente.cloudexus.mobile.data.session.TokenCipher
import net.levente.cloudexus.mobile.ui.theme.CloudexusTheme
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Two-step sign-in against a fake server on the device: the code field only
 * appears once the server asks for it, a wrong code says so, and the right one
 * signs in. Each step is saved to the app's external files, for `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var sessions: SessionManager
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        // localhost, which the debug build may reach over plain HTTP.
        server.start(InetAddress.getByName("localhost"), 0)
        storeFile = File(context.cacheDir, "login-test-${System.nanoTime()}.preferences_pb")
        val store = SessionStore(PreferenceDataStoreFactory.create { storeFile }, TokenCipher(), json)
        sessions = SessionManager(store, ApiClient(OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(), json), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        storeFile.delete()
    }

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())
    }

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun text(id: Int) = context.getString(id)

    private fun submit() {
        rule.onNode(hasText(text(R.string.login_submit)) and hasClickAction()).performScrollTo().performClick()
    }

    private fun fill() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTextExists(BuildConfig.DEFAULT_SERVER_URL) }
        rule.onNodeWithText(text(R.string.login_server)).performTextReplacement("http://localhost:${server.port}")
        rule.onNodeWithText(text(R.string.login_username)).performTextReplacement("pdateszt")
        rule.onNodeWithText(text(R.string.login_password)).performTextInput("not-a-real-password")
    }

    @Test
    fun theCodeIsAskedForOnlyWhenTheServerWantsItAndTheRightOneSignsIn() {
        respond(403, """{"error":{"status":403,"message":"A two-step sign-in code is required.","details":{"two_factor_required":true}}}""")
        respond(401, """{"error":{"status":401,"message":"That two-step sign-in code is not right.","details":{"two_factor_required":true}}}""")
        respond(201, """{"data":{"token":"cxu_abc","expires_at":"2026-12-21 21:28:53","user":{"id":4,"username":"pdateszt","full_name":"PDA Teszt","email":"x@y","role":"user"}}}""")
        respond(404, """{"error":{"status":404,"message":"Not found."}}""")

        rule.setContent { CloudexusTheme { LoginScreen(sessions, expired = false) } }
        rule.onNodeWithText(text(R.string.login_code)).assertDoesNotExist()

        fill()
        submit()

        rule.waitUntil(10_000) { rule.onAllNodesWithTextExists(text(R.string.login_code_lead)) }
        rule.onNodeWithText(text(R.string.login_code)).assertIsDisplayed()
        assertFalse(server.takeRequest().body!!.utf8().contains("\"code\""))
        save("login-code-asked")

        rule.onNodeWithText(text(R.string.login_code)).performTextInput("000000")
        submit()
        rule.waitUntil(10_000) { rule.onAllNodesWithTextExists(text(R.string.login_code_wrong)) }
        assertTrue(server.takeRequest().body!!.utf8().contains("\"code\":\"000000\""))
        save("login-code-wrong")

        rule.onNodeWithText(text(R.string.login_use_recovery_code)).performScrollTo().performClick()
        rule.onNodeWithText(text(R.string.login_recovery_code)).performTextInput("kp9kp-hc3hr")
        submit()
        rule.waitUntil(10_000) { sessions.state.value is SessionState.SignedIn }
        assertTrue(server.takeRequest().body!!.utf8().contains("\"code\":\"kp9kp-hc3hr\""))
    }

    @Test
    fun aUserWithoutTwoStepSignInIsNeverAsked() {
        respond(201, """{"data":{"token":"cxu_abc","expires_at":"2026-12-21 21:28:53","user":{"id":4,"username":"pdateszt","full_name":"PDA Teszt","email":"x@y","role":"user"}}}""")
        respond(404, """{"error":{"status":404,"message":"Not found."}}""")

        rule.setContent { CloudexusTheme { LoginScreen(sessions, expired = false) } }
        fill()
        submit()

        rule.waitUntil(10_000) { sessions.state.value is SessionState.SignedIn }
        rule.onNodeWithText(text(R.string.login_code)).assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(text: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

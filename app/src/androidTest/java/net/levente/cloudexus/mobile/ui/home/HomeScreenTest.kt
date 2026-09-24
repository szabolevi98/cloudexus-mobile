package net.levente.cloudexus.mobile.ui.home

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.levente.cloudexus.mobile.R
import net.levente.cloudexus.mobile.data.api.User
import net.levente.cloudexus.mobile.data.session.Session
import net.levente.cloudexus.mobile.ui.theme.CloudexusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The home screen follows the role: bookings for a role that may move stock,
 * a notice instead of them for one that may not. Each test also saves what it
 * saw to the app's external files, for a look with `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun session(roleName: String?, permissions: List<String>?) = Session(
        baseUrl = "http://10.0.2.2/cloudexus/web",
        token = "cxu_test",
        user = User(4, "pdateszt", "PDA Teszt", roleName = roleName, permissions = permissions),
        serverLanguages = listOf("hu", "en"),
    )

    private fun show(session: Session) {
        rule.setContent { CloudexusTheme { HomeScreen(session, onBooking = {}, onLookup = {}, onSettings = {}) } }
    }

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun aRoleThatMayMoveStockSeesTheBookings() {
        show(session("Raktáros", listOf("stock.view", "stock.move")))

        rule.onNodeWithText(context.getString(R.string.mode_in)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.mode_transfer)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.home_no_booking_title)).assertDoesNotExist()
        rule.onNodeWithText("Raktáros", substring = true).assertIsDisplayed()
        save("home-warehouse")
    }

    @Test
    fun aRoleThatMayNotMoveStockIsToldWhyAndCanStillLookUp() {
        show(session("Csak olvasó", listOf("stock.view")))

        rule.onNodeWithText(context.getString(R.string.home_no_booking_title)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.mode_in)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.mode_lookup)).assertIsDisplayed()
        save("home-viewer")
    }

    @Test
    fun aServerOlderThanRolesHidesNothing() {
        show(session(null, null))

        rule.onNodeWithText(context.getString(R.string.mode_out)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.home_no_booking_title)).assertDoesNotExist()
    }
}

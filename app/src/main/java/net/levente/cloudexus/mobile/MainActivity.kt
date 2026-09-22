package net.levente.cloudexus.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.levente.cloudexus.mobile.data.scanner.ScannerConfig
import net.levente.cloudexus.mobile.data.session.SessionState
import net.levente.cloudexus.mobile.ui.booking.BookingMode
import net.levente.cloudexus.mobile.ui.booking.BookingScreen
import net.levente.cloudexus.mobile.ui.booking.BookingViewModel
import net.levente.cloudexus.mobile.ui.home.HomeScreen
import net.levente.cloudexus.mobile.ui.login.LoginScreen
import net.levente.cloudexus.mobile.ui.lookup.LookupScreen
import net.levente.cloudexus.mobile.ui.lookup.LookupViewModel
import net.levente.cloudexus.mobile.ui.settings.SettingsScreen
import net.levente.cloudexus.mobile.ui.theme.CloudexusTheme
import net.levente.cloudexus.mobile.ui.theme.CxNavyFrom

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Every screen starts with the dark navy header, so the status bar icons are light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val container = (application as CloudexusApp).container
        setContent {
            CloudexusTheme { CloudexusNavigation(container) }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val BOOKING = "booking/{mode}"
    const val LOOKUP = "lookup"
    const val SETTINGS = "settings"

    fun booking(mode: BookingMode) = "booking/${mode.name}"
}

@Composable
private fun CloudexusNavigation(container: AppContainer) {
    val sessionState by container.sessions.state.collectAsStateWithLifecycle()
    val scanner by container.scannerSettings.config.collectAsStateWithLifecycle(ScannerConfig())

    when (val current = sessionState) {
        SessionState.Loading -> Box(Modifier.fillMaxSize().background(CxNavyFrom))
        is SessionState.SignedOut -> LoginScreen(container.sessions, current.expired)
        is SessionState.SignedIn -> {
            // Signing out leaves this branch, so every sign-in gets a fresh back stack from home.
            val nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        session = current.session,
                        onBooking = { nav.navigate(Routes.booking(it)) },
                        onLookup = { nav.navigate(Routes.LOOKUP) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.BOOKING, arguments = listOf(navArgument("mode") { type = NavType.StringType })) { entry ->
                    val mode = BookingMode.valueOf(entry.arguments?.getString("mode") ?: BookingMode.IN.name)
                    val viewModel: BookingViewModel = viewModel(
                        factory = viewModelFactory { initializer { BookingViewModel(mode, container.api, container.sessions) } },
                    )
                    BookingScreen(viewModel, scanner, onExit = { nav.popBackStack() })
                }
                composable(Routes.LOOKUP) {
                    val viewModel: LookupViewModel = viewModel(
                        factory = viewModelFactory { initializer { LookupViewModel(container.api, container.sessions) } },
                    )
                    LookupScreen(viewModel, scanner, onBack = { nav.popBackStack() })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        session = current.session,
                        config = scanner,
                        settings = container.scannerSettings,
                        onSignOut = container.sessions::signOut,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

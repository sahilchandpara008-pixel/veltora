package com.streams.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.streams.app.data.UpiPay
import com.streams.app.data.supabase
import com.streams.app.ui.AppNav
import com.streams.app.ui.theme.StreamsTheme
import io.github.jan.supabase.auth.handleDeeplinks

class MainActivity : ComponentActivity() {

    // Registered here (not inside a screen) so the UPI app's answer is still delivered when
    // Android closed and re-created Streams while GPay / PhonePe / Paytm was open.
    private val upiLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            UpiPay.onResult(r.resultCode, r.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        UpiPay.launcher = upiLauncher
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handleAuthLink(intent)
        setContent { StreamsTheme { AppNav() } }
    }

    override fun onDestroy() {
        if (UpiPay.launcher === upiLauncher) UpiPay.launcher = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthLink(intent)
    }

    /** streams://login-callback[/admin-reset|/set-password]?code=... — finishes Google / e-mail sign-in. */
    private fun handleAuthLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.path?.startsWith("/admin-reset") == true) AppState.pendingRoute.value = "admin-reset"
        if (data.path?.startsWith("/set-password") == true) AppState.pendingRoute.value = "set-password"
        supabase.handleDeeplinks(intent)
    }
}

package com.streams.app.ui.admin

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.streams.app.data.MetaStatus
import com.streams.app.data.PaymentSettings
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.ui.theme.Amber
import com.streams.app.ui.theme.Green
import kotlinx.coroutines.launch

/**
 * Admin → Settings → Meta (Facebook) ads tracking. The owner pastes the IDs once:
 *  - App ID + Client Token → the Android app's Meta SDK (installs, app events, purchases with value).
 *  - Pixel / dataset ID + Conversions API token → the website (browser Pixel + server events).
 * The token is stored server-side and never shown again or sent to phones / browsers.
 */
@Composable
fun MetaSettingsSection(settings: PaymentSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(settings.metaEnabled) }
    var appId by remember { mutableStateOf(settings.metaAppId.orEmpty()) }
    var clientToken by remember { mutableStateOf(settings.metaClientToken.orEmpty()) }
    var pixelId by remember { mutableStateOf(settings.metaPixelId.orEmpty()) }
    var capiToken by remember { mutableStateOf("") }
    var testCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<MetaStatus?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { Repo.metaStatus() }.onSuccess { status = it; testCode = it.testEventCode.orEmpty() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.size(8.dp))
        HorizontalDivider()
        Text("Meta (Facebook) ads tracking", style = MaterialTheme.typography.titleMedium)
        Text(
            "Paste the IDs from Meta Events Manager and save. The app and the website start sending events " +
                "(install, app open, registration, content view, search, video play, trailer complete, purchase " +
                "with amount) automatically. A new App ID is used from the next app start.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tracking on", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        Text("Android app (Meta SDK)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            appId, { appId = it.filter(Char::isDigit) }, label = { Text("Meta App ID") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            clientToken, { clientToken = it.trim() }, label = { Text("Client Token") }, singleLine = true,
            supportingText = { Text("Meta for Developers → your app → App settings → Advanced → Client token") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Website (Pixel + Conversions API)", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            pixelId, { pixelId = it.filter(Char::isDigit) }, label = { Text("Pixel / Dataset ID") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            capiToken, { capiToken = it.trim() },
            label = { Text("Conversions API access token") },
            placeholder = { Text(if (status?.hasToken == true) "Saved ${status?.tokenHint.orEmpty()} — leave empty to keep" else "Paste token") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (status?.hasToken == true) "Access token saved (${status?.tokenHint.orEmpty()}). It is never shown again."
            else "No access token saved yet.",
            style = MaterialTheme.typography.bodySmall,
            color = if (status?.hasToken == true) Green else Amber,
        )
        OutlinedTextField(
            testCode, { testCode = it.trim() }, label = { Text("Test event code (optional)") }, singleLine = true,
            supportingText = { Text("Only while testing in Events Manager → Test events. Clear it before going live.") },
            modifier = Modifier.fillMaxWidth(),
        )

        BusyButton("Save Meta settings", busy) {
            if (enabled && appId.isBlank() && pixelId.isBlank()) {
                Toast.makeText(context, "Enter the App ID (for the app) and/or the Pixel ID (for the website)", Toast.LENGTH_LONG).show()
                return@BusyButton
            }
            if (appId.isNotBlank() && clientToken.isBlank()) {
                Toast.makeText(context, "The app also needs the Client Token", Toast.LENGTH_LONG).show(); return@BusyButton
            }
            busy = true
            scope.launch {
                runCatching {
                    Repo.saveMetaSettings(enabled, appId, clientToken, pixelId)
                    status = Repo.saveMetaCapi(capiToken, testCode)
                    capiToken = ""
                }.onSuccess { Toast.makeText(context, "Meta settings saved", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                busy = false
            }
        }
        if (status?.hasToken == true) {
            TextButton(onClick = {
                scope.launch {
                    runCatching { Repo.clearMetaCapi() }.onSuccess { status = it }
                        .onFailure { Toast.makeText(context, it.friendly(), Toast.LENGTH_LONG).show() }
                }
            }) { Text("Remove saved access token") }
        }
    }
}

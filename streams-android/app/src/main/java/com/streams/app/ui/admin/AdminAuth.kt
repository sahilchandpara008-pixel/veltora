package com.streams.app.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.streams.app.AppState
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.ui.theme.Red
import io.github.jan.supabase.auth.status.SessionStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Admin-only e-mail + password sign-in. Completely separate from the customer sign-in screen. */
@Composable
fun AdminLoginScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AdminForm("Admin sign in") {
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        BusyButton("Sign in", busy) {
            error = null; message = null; busy = true
            scope.launch {
                try {
                    Repo.adminPasswordSignIn(email, password)
                    if (Repo.myAdminRole() == null) {
                        // Never leave a non-admin session active from this page.
                        Repo.signOut()
                        error = "This account doesn't have admin access"
                    } else {
                        AppState.refreshAccess()
                        nav.navigate("admin") { popUpTo("admin-login") { inclusive = true } }
                    }
                } catch (e: Exception) {
                    error = e.friendly()
                }
                busy = false
            }
        }
        TextButton(onClick = {
            error = null; message = null
            if (!email.contains("@")) { error = "Type your email above first"; return@TextButton }
            scope.launch {
                runCatching { Repo.sendAdminPasswordLink(email) }
                    .onSuccess { message = "We emailed a link to $email. Open it on this phone to set a new password." }
                    .onFailure { error = it.friendly() }
            }
        }) { Text("Forgot password? / Set first password") }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Opened from the e-mail link. The link already signed the person in; now they choose a password. */
@Composable
fun AdminResetPasswordScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The e-mail link signs in a moment after this screen opens, so watch the session.
    val session by AppState.session.collectAsStateWithLifecycle()
    val signedInEmail = (session as? SessionStatus.Authenticated)?.session?.user?.email

    AdminForm("Set admin password") {
        Text(
            if (signedInEmail != null) "Signed in as $signedInEmail" else "Finishing sign-in from your email link…",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            pw, { pw = it }, label = { Text("New password (min 8 characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        BusyButton("Save password", busy) {
            error = null
            when {
                pw.length < 8 -> { error = "Use at least 8 characters"; return@BusyButton }
                pw != pw2 -> { error = "Passwords don't match"; return@BusyButton }
                signedInEmail == null -> {
                    error = "This link has expired. Go back and request a new one."; return@BusyButton
                }
            }
            busy = true
            scope.launch {
                try {
                    Repo.setPassword(pw)
                    if (Repo.myAdminRole() == null) {
                        Repo.signOut()
                        error = "Password saved, but this account doesn't have admin access"
                    } else {
                        nav.navigate("admin") { popUpTo("admin-reset") { inclusive = true } }
                    }
                } catch (e: Exception) {
                    error = e.friendly()
                }
                busy = false
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun AdminForm(heading: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .statusBarsPadding()
            .padding(24.dp),
    ) {
        Text("STREAMS", color = Red, style = MaterialTheme.typography.titleLarge)
        Text(heading, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
        content()
    }
}

@Composable
fun BusyButton(label: String, busy: Boolean, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = Red),
        modifier = modifier.height(48.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text(label)
    }
}

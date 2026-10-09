package com.streams.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jan.supabase.auth.status.SessionStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streams.app.AppState
import com.streams.app.data.Repo
import com.streams.app.data.friendly
import com.streams.app.ui.components.PrimaryButton
import com.streams.app.ui.theme.Bg
import com.streams.app.ui.theme.Poppins
import com.streams.app.ui.theme.Red
import com.streams.app.ui.theme.Surface1
import com.streams.app.ui.theme.TextFaint
import com.streams.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(onClose: () -> Unit, reason: String = "Sign in to watch and to subscribe.") {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var usePassword by remember { mutableStateOf(true) }   // e-mail + password, or a one-tap e-mail link
    var creating by remember { mutableStateOf(false) }      // password mode: sign in or create account
    var sentTo by remember { mutableStateOf<String?>(null) }
    var sentWhat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, cursorColor = Color.White, focusedLabelColor = Color.White)
    val validEmail = email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))

    fun passwordSubmit() {
        error = null
        if (!validEmail) { error = "Please enter a valid email address"; return }
        if (password.length < 8) { error = "Password must be at least 8 characters"; return }
        busy = true
        scope.launch {
            runCatching {
                if (creating) {
                    if (!Repo.passwordSignUp(email, password)) {
                        sentWhat = "We sent a confirmation link to\n${email.trim()}\n\nOpen it once to activate your account. After that you can sign in with your email and password.\n\nAlready have an account? Use “Forgot password?” to set one."
                        sentTo = email.trim()
                    }
                } else {
                    Repo.passwordSignIn(email, password)
                }
            }.onFailure {
                error = when {
                    it.message?.contains("Invalid login credentials") == true ->
                        "Wrong email or password. New here? Tap “Create account”. Signed in with a link before? Tap “Forgot password?” to set a password."
                    else -> it.friendly()
                }
            }
            busy = false
        }
    }

    fun forgotPassword() {
        error = null
        if (!validEmail) { error = "Enter your email address first"; return }
        busy = true
        scope.launch {
            runCatching { Repo.sendPasswordLink(email) }
                .onSuccess {
                    sentWhat = "We sent a link to\n${email.trim()}\n\nOpen it on this phone to choose a new password."
                    sentTo = email.trim()
                }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    fun sendLink() {
        error = null
        if (!validEmail) { error = "Please enter a valid email address"; return }
        busy = true
        scope.launch {
            runCatching { Repo.sendMagicLink(email) }
                .onSuccess {
                    sentWhat = "We sent a sign-in link to\n${email.trim()}\n\nOpen it on this phone — no password needed."
                    sentTo = email.trim()
                }
                .onFailure { error = it.friendly() }
            busy = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Red.copy(alpha = 0.22f), Bg, Bg))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            Text("STREAMS", color = Red, fontFamily = Poppins, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Movies, series & originals",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(reason, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(40.dp))

            if (sentTo != null) {
                Box(Modifier.size(76.dp).clip(CircleShape).background(Surface1), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MarkEmailRead, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("Check your inbox", style = MaterialTheme.typography.headlineSmall)
                Text(
                    sentWhat,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { sentTo = null }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Back", color = Color.White)
                }
            } else {
                Button(
                    onClick = {
                        error = null
                        scope.launch { runCatching { Repo.signInWithGoogle() }.onFailure { error = it.friendly() } }
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("G", fontWeight = FontWeight.Black, color = Color(0xFF4285F4), fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google", style = MaterialTheme.typography.labelLarge, color = Color.Black)
                }

                Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = TextFaint.copy(alpha = 0.4f))
                    Text("  or  ", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.weight(1f), color = TextFaint.copy(alpha = 0.4f))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    placeholder = { Text("you@example.com") },
                    label = { Text("Email address") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = if (usePassword) ImeAction.Next else ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendLink() }),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (usePassword) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text(if (creating) "Choose a password (min 8 characters)" else "Password") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (showPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { passwordSubmit() }),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!creating) {
                        TextButton(onClick = { forgotPassword() }, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                            Text("Forgot password?", color = TextMuted)
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                    }
                    PrimaryButton(if (creating) "Create account" else "Sign in", { passwordSubmit() }, loading = busy)
                    TextButton(onClick = { creating = !creating; error = null }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            if (creating) "Already have an account? Sign in" else "New here? Create account",
                            color = Color.White,
                        )
                    }
                    TextButton(onClick = { usePassword = false; error = null }) {
                        Text("Email me a sign-in link instead", color = TextMuted)
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Email me a sign-in link", { sendLink() }, loading = busy)
                    Text(
                        "No password needed — we'll email you a one-tap link.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TextButton(onClick = { usePassword = true; error = null }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Sign in with email and password", color = Color.White)
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(36.dp))
            Text(
                "Free titles need a free account.\nPremium titles also need a plan.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopEnd)) {
            Icon(Icons.Default.Close, "Not now", tint = Color.White)
        }
    }
}

/** Opened from the "Forgot password?" e-mail link. The link already signed the person in; now they choose a password. */
@Composable
fun SetPasswordScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    // The e-mail link signs in a moment after this screen opens, so watch the session.
    val session by AppState.session.collectAsStateWithLifecycle()
    val signedInEmail = (session as? SessionStatus.Authenticated)?.session?.user?.email
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, cursorColor = Color.White, focusedLabelColor = Color.White)

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .statusBarsPadding()
            .padding(24.dp),
    ) {
        Text("STREAMS", color = Red, fontFamily = Poppins, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        Text("Set your password", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        Text(
            if (signedInEmail != null) "Signed in as $signedInEmail" else "Finishing sign-in from your email link…",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        if (saved) {
            Text("Password saved. Next time, sign in with your email and this password.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Continue", onDone)
            return@Column
        }
        OutlinedTextField(
            pw, { pw = it; error = null }, label = { Text("New password (min 8 characters)") }, singleLine = true,
            shape = MaterialTheme.shapes.small, colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            pw2, { pw2 = it; error = null }, label = { Text("Repeat password") }, singleLine = true,
            shape = MaterialTheme.shapes.small, colors = fieldColors,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Save password", {
            error = when {
                pw.length < 8 -> "Use at least 8 characters"
                pw != pw2 -> "Passwords don't match"
                signedInEmail == null -> "This link has expired. Go back and tap “Forgot password?” again."
                else -> null
            }
            if (error == null) {
                busy = true
                scope.launch {
                    runCatching { Repo.setPassword(pw) }
                        .onSuccess { saved = true }
                        .onFailure { error = it.friendly() }
                    busy = false
                }
            }
        }, loading = busy)
        TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text("Not now", color = TextMuted) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

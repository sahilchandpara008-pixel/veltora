package com.streams.app.data

import com.streams.app.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage

/** Deep links that Supabase sends people back to after e-mail / Google sign-in. */
object Links {
    const val SCHEME = "streams"
    const val HOST = "login-callback"
    const val LOGIN = "$SCHEME://$HOST"
    const val ADMIN_SET_PASSWORD = "$SCHEME://$HOST/admin-reset"
    const val SET_PASSWORD = "$SCHEME://$HOST/set-password"
}

val supabase by lazy {
    createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Auth) {
            scheme = Links.SCHEME
            host = Links.HOST
            flowType = FlowType.PKCE
        }
        install(Postgrest)
        install(Functions)
        install(Storage)
    }
}

/** Cover images live in the public "images" bucket. */
fun imageUrl(path: String?): String? =
    path?.takeIf { it.isNotBlank() }?.let { supabase.storage.from("images").publicUrl(it) }

fun currentEmail(): String? = supabase.auth.currentUserOrNull()?.email

/** Turns Supabase/Postgres error text into something a customer can read. */
fun Throwable.friendly(): String {
    val raw = message ?: return "Something went wrong. Please try again."
    val known = listOf(
        "You already have a payment waiting for verification",
        "This reference number has already been submitted",
        "Reference number should be",
        "This plan is not available",
        "This payment was already processed",
        "Please type a short reason",
        "Please sign in first",
        "Invalid login credentials",
        "Email not confirmed",
        "User already registered",
        "Password should be",
        "Not allowed",
        "This payment has expired",
        "The UPI app did not",
        "This transaction has already been used",
        "Too many payments in progress",
    )
    known.firstOrNull { raw.contains(it) }?.let { k ->
        return raw.substring(raw.indexOf(k)).lineSequence().first()
    }
    if (raw.contains("Unable to resolve host") || raw.contains("timeout", ignoreCase = true)) {
        return "No internet connection. Please try again."
    }
    return raw.lineSequence().first().take(160)
}

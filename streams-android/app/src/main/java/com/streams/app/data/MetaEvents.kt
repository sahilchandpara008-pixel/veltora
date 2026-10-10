package com.streams.app.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import com.facebook.FacebookSdk
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.Currency

/**
 * Meta (Facebook) app events for ad campaigns. The App ID and Client Token are entered by the owner
 * in Admin → Settings (they are public values, made to ship inside apps), so nothing is hard-coded:
 * the app reads them at start, remembers them on the phone and starts the Meta SDK.
 *
 * Events: app install / open (automatic), CompleteRegistration, ViewContent, Search, VideoPlay,
 * TrailerComplete and Purchase (with the paid amount, for value / ROAS optimisation).
 * Registration and Purchase are claimed on the server, so each is reported once per account /
 * payment even across phones, reinstalls and the website.
 */
object MetaEvents {
    private const val TAG = "MetaEvents"
    private lateinit var app: Application
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logger: AppEventsLogger? = null

    fun init(application: Application) {
        app = application
        prefs = application.getSharedPreferences("meta", Context.MODE_PRIVATE)
        // Start straight away with the IDs saved last time, so the install / first open is counted.
        start(prefs.getBoolean(KEY_ENABLED, false), prefs.getString(KEY_APP_ID, null), prefs.getString(KEY_TOKEN, null))
        // Then pick up changes from the admin panel (a new App ID applies from the next app start).
        scope.launch {
            val s = runCatching { Repo.paymentSettings() }.getOrNull() ?: return@launch
            prefs.edit()
                .putBoolean(KEY_ENABLED, s.metaEnabled)
                .putString(KEY_APP_ID, s.metaAppId?.trim())
                .putString(KEY_TOKEN, s.metaClientToken?.trim())
                .apply()
            launchOnMain {
                val wasRunning = logger != null
                start(s.metaEnabled, s.metaAppId?.trim(), s.metaClientToken?.trim())
                // Started just now (first run after setup): catch up on this account's events.
                if (!wasRunning && logger != null && supabase.auth.currentUserOrNull() != null) onSignedIn()
            }
        }
    }

    private fun start(enabled: Boolean, appId: String?, clientToken: String?) {
        if (logger != null || !enabled || appId.isNullOrBlank() || clientToken.isNullOrBlank()) return
        if (!appId.all { it.isDigit() }) return
        runCatching {
            FacebookSdk.setApplicationId(appId)
            FacebookSdk.setClientToken(clientToken)
            FacebookSdk.setAutoInitEnabled(true)
            FacebookSdk.setAutoLogAppEventsEnabled(true)
            FacebookSdk.setAdvertiserIDCollectionEnabled(true)
            @Suppress("DEPRECATION")
            FacebookSdk.sdkInitialize(app)
            FacebookSdk.fullyInitialize()
            AppEventsLogger.activateApp(app)            // installs, app opens, sessions
            logger = AppEventsLogger.newLogger(app)
        }.onFailure { Log.w(TAG, "Meta SDK not started", it) }
    }

    private fun log(name: String, params: Bundle? = null, valueToSum: Double? = null) {
        val l = logger ?: return
        runCatching { if (valueToSum != null) l.logEvent(name, valueToSum, params) else l.logEvent(name, params) }
    }

    fun viewContent(t: Title) = log(AppEventsConstants.EVENT_NAME_VIEWED_CONTENT, Bundle().apply {
        putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, t.id)
        putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, t.kind)
        putString("fb_content_name", t.name)
    })

    fun search(query: String) = log(AppEventsConstants.EVENT_NAME_SEARCHED, Bundle().apply {
        putString(AppEventsConstants.EVENT_PARAM_SEARCH_STRING, query.take(100))
        putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "video")
    })

    /** mode = full | trailer | preview */
    fun videoPlay(t: Title, mode: String) = log("VideoPlay", Bundle().apply {
        putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, t.id)
        putString("fb_content_name", t.name)
        putString("mode", mode)
    })

    fun trailerComplete(t: Title) = log("TrailerComplete", Bundle().apply {
        putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, t.id)
        putString("fb_content_name", t.name)
    })

    /** After sign-in / sign-up and whenever access is refreshed: report a new account and new purchases once. */
    fun onSignedIn() {
        if (logger == null) return
        scope.launch {
            val first = runCatching {
                supabase.postgrest.rpc("claim_meta_registration").data.trim() == "true"
            }.getOrDefault(false)
            if (first) log(AppEventsConstants.EVENT_NAME_COMPLETED_REGISTRATION, Bundle().apply {
                putString(AppEventsConstants.EVENT_PARAM_REGISTRATION_METHOD, "email")
            })
            checkPurchases()
        }
    }

    /** Approved payments not reported yet (claimed on the server, so never twice). */
    fun checkPurchases() {
        val l = logger ?: return
        scope.launch {
            val rows = runCatching { supabase.postgrest.rpc("claim_meta_purchases").decodeList<MetaPurchase>() }.getOrDefault(emptyList())
            rows.forEach { p ->
                runCatching {
                    l.logPurchase(BigDecimal.valueOf(p.amount), Currency.getInstance(p.currency), Bundle().apply {
                        putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, p.paymentId)
                        putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
                        p.planName?.let { putString("fb_content_name", it) }
                    })
                }
            }
            if (rows.isNotEmpty()) runCatching { l.flush() }
        }
    }

    private fun launchOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private const val KEY_ENABLED = "enabled"
    private const val KEY_APP_ID = "app_id"
    private const val KEY_TOKEN = "client_token"
}

@Serializable
private data class MetaPurchase(
    @SerialName("payment_id") val paymentId: String,
    val amount: Double,
    val currency: String = "INR",
    @SerialName("plan_name") val planName: String? = null,
)

package com.streams.app.data

import android.content.Context
import android.content.SharedPreferences
import com.android.installreferrer.api.InstallReferrerClient
import com.streams.app.AppState
import com.streams.app.BuildConfig
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Install attribution (Meta ads vs organic vs unknown) via the official Google Play Install Referrer.
 *
 *  1. Once per install the app reads the Play Install Referrer and reports it with a random
 *     install id (record_install). The server parses and stores it — the app never decides
 *     the source itself.
 *  2. After sign-in the account claims that install (attribute_user). The server keeps the
 *     account's first touch forever and only gives it to an account created on/after the install.
 *
 * App data (and so the install id) is wiped on uninstall, so a reinstall is a new install.
 * Nothing here identifies the person: only the referrer string Google Play hands the app.
 */
object Attribution {
    private const val PREFS = "attribution"
    private const val MAX_TRANSIENT_ATTEMPTS = 3

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val mutex = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Random id for this installation of the app (not a device or advertising id). */
    val installId: String
        get() = prefs.getString("install_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("install_id", it).apply() }

    private data class Referrer(val status: String, val referrer: String? = null, val clickTs: Long = 0, val installTs: Long = 0)

    /** App start: report the install once (retried on later starts if it fails), then log app_open. */
    fun onAppStart() {
        scope.launch {
            ensureInstallRecorded()
            logEvent("app_open")
        }
    }

    /**
     * Signed-in account changed (null = signed out). Logs `login` for a newly signed-in account,
     * links the install to it, and returns true when that unlocked campaign content (screens reload).
     */
    suspend fun onSessionChanged(userId: String?): Boolean {
        val previous = prefs.getString("session_user", null)
        if (userId == null) {
            prefs.edit().remove("session_user").apply()   // nothing user-specific survives a sign-out
            return false
        }
        if (previous != userId) {
            prefs.edit().putString("session_user", userId).apply()
            logEvent("login")
        }
        if (!ensureInstallRecorded()) return false
        val key = "linked_$userId"
        if (prefs.getBoolean(key, false)) return false
        val result = runCatching { Repo.attributeUser(installId) }.getOrNull() ?: return false
        if (result.pending) return false
        prefs.edit().putBoolean(key, true).apply()
        return result.special
    }

    fun logEvent(event: String, contentId: String? = null) {
        scope.launch { runCatching { Repo.logEvent(event, installId, contentId) } }
    }

    /** This install came from Meta ads (as the server decided): may browse campaign content and trailers before sign-in. */
    val isMetaInstall: Boolean
        get() = prefs.getString("install_source", null) == "meta"

    private suspend fun ensureInstallRecorded(): Boolean = mutex.withLock {
        if (prefs.getBoolean("install_reported", false) && prefs.contains("install_source")) return true
        val ref = referrer() ?: return false   // temporary failure: try again next time
        return runCatching { Repo.recordInstall(installId, ref.status, ref.referrer, ref.clickTs, ref.installTs) }
            .onSuccess { source ->
                prefs.edit().putBoolean("install_reported", true).putString("install_source", source ?: "unknown").apply()
                // Ads install: reload the screens so the campaign titles show up before sign-in.
                if (source == "meta") AppState.accessVersion.value++
            }
            .isSuccess
    }

    /** Reads the referrer once and caches the final answer; null = transient failure, retry later. */
    private suspend fun referrer(): Referrer? {
        prefs.getString("ref_status", null)?.let { status ->
            return Referrer(status, prefs.getString("ref_value", null), prefs.getLong("ref_click", 0), prefs.getLong("ref_install", 0))
        }
        // Streams-ads.apk (the download link used in ads) carries its own referrer, because an APK
        // installed from a link has no Google Play install referrer.
        val read = BuildConfig.APK_REFERRER.takeIf { it.isNotBlank() }?.let { Referrer("ok", it) }
            ?: withTimeoutOrNull(10_000) { readFromPlay() } ?: Referrer("unavailable")
        val attempts = prefs.getInt("ref_attempts", 0) + 1
        val final = read.status == "ok" || read.status == "not_supported" || attempts >= MAX_TRANSIENT_ATTEMPTS
        if (!final) {
            prefs.edit().putInt("ref_attempts", attempts).apply()
            return null
        }
        prefs.edit()
            .putString("ref_status", read.status)
            .putString("ref_value", read.referrer)
            .putLong("ref_click", read.clickTs)
            .putLong("ref_install", read.installTs)
            .apply()
        return read
    }

    private suspend fun readFromPlay(): Referrer = suspendCancellableCoroutine { cont ->
        val client = try {
            InstallReferrerClient.newBuilder(appContext).build()
        } catch (e: Exception) {
            cont.resume(Referrer("error")); return@suspendCancellableCoroutine
        }
        fun finish(r: Referrer) {
            if (cont.isActive) cont.resume(r)
            runCatching { client.endConnection() }
        }
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    val r = when (code) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> try {
                            val d = client.installReferrer
                            Referrer("ok", d.installReferrer, d.referrerClickTimestampSeconds, d.installBeginTimestampSeconds)
                        } catch (e: Exception) {
                            Referrer("unavailable")
                        }
                        // Not installed from Google Play / Play Store too old.
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> Referrer("not_supported")
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> Referrer("unavailable")
                        else -> Referrer("error")
                    }
                    finish(r)
                }

                override fun onInstallReferrerServiceDisconnected() = finish(Referrer("unavailable"))
            })
        } catch (e: Exception) {
            finish(Referrer("error"))
        }
    }
}

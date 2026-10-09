package com.streams.app.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import com.streams.app.AppState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Opens the UPI app and delivers its answer to the backend, independent of any screen.
 *
 * Android often closes Streams while GPay / PhonePe / Paytm is open. The launcher is registered by
 * MainActivity (so the result still arrives after Android re-creates the app), and the order id and
 * the UPI app's answer are kept on the phone until report_upi_result has accepted them.
 */
object UpiPay {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sending: Job? = null

    /** Registered in MainActivity.onCreate. */
    var launcher: ActivityResultLauncher<Intent>? = null

    /** The backend's answer after the UPI app's result was delivered; Profile shows it. */
    val result = MutableStateFlow<Payment?>(null)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("upi_pay", Context.MODE_PRIVATE)
        send()   // an answer left over from a previous run (app closed, no internet, expired sign-in)
    }

    /** Returns false when the UPI app could not be opened. */
    fun pay(intent: Intent, orderId: String): Boolean {
        val l = launcher ?: return false
        prefs.edit().putString(KEY_ORDER, orderId).remove(KEY_RESPONSE).apply()
        return runCatching { l.launch(intent) }.isSuccess
    }

    /** Called by MainActivity with whatever the UPI app returned. */
    fun onResult(resultCode: Int, data: Intent?) {
        if (prefs.getString(KEY_ORDER, null) == null) return
        val response = upiResponse(data) ?: buildString {
            append("Status=NO_RESPONSE&resultCode=").append(resultCode)
            data?.extras?.keySet()?.takeIf { it.isNotEmpty() }?.let { append("&extras=").append(it.joinToString(",")) }
        }
        prefs.edit().putString(KEY_RESPONSE, response).apply()
        send()
    }

    fun send() {
        if (sending?.isActive == true) return
        sending = scope.launch {
            runCatching { supabase.auth.awaitInitialization() }
            repeat(30) { attempt ->
                val order = prefs.getString(KEY_ORDER, null) ?: return@launch
                val response = prefs.getString(KEY_RESPONSE, null) ?: return@launch
                val sent = runCatching { Repo.reportUpiResult(order, response) }
                sent.onSuccess { p ->
                    prefs.edit().remove(KEY_ORDER).remove(KEY_RESPONSE).apply()
                    result.value = p
                    if (p.status == "approved") {
                        AppState.accessVersion.value++
                        Toast.makeText(appContext, "Payment successful! Your subscription is now active.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                // An order of another account (signed out meanwhile) can never be delivered.
                if (sent.exceptionOrNull()?.message?.contains("Order not found") == true) {
                    prefs.edit().remove(KEY_ORDER).remove(KEY_RESPONSE).apply()
                    return@launch
                }
                delay(minOf(30_000L, 2_000L * (attempt + 1)))
            }
        }
    }

    /** UPI apps return "txnId=..&Status=..", usually in the "response" extra; a few send separate extras. */
    private fun upiResponse(data: Intent?): String? {
        data ?: return null
        data.getStringExtra("response")?.takeIf { it.isNotBlank() }?.let { return it }
        val extras = data.extras ?: return null
        val pairs = extras.keySet().mapNotNull { k -> extras.getString(k)?.let { "$k=$it" } }
        return pairs.takeIf { list -> list.any { it.startsWith("Status=", ignoreCase = true) } }?.joinToString("&")
    }

    private const val KEY_ORDER = "order"
    private const val KEY_RESPONSE = "response"
}

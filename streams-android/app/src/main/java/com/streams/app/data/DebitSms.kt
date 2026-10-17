package com.streams.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.streams.app.AppState
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After the customer pays in their UPI app, asks them (Google SMS User Consent: one message,
 * one tap, no SMS permission) to share the bank's debit SMS, e.g.
 * "A/C X3526 debited by 1.00 ... trf to AS ENTERPRISE Refno 628351826253".
 * The text goes to report_debit_sms, which checks payee, amount and Refno on the server.
 */
object DebitSms {
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var receiver: BroadcastReceiver? = null
    private var pendingConsent: Intent? = null
    private var tries = 0

    /** Registered in MainActivity.onCreate. */
    var launcher: ActivityResultLauncher<Intent>? = null
    /** Set by MainActivity: the consent screen can only be shown while Veltora is in front. */
    var resumed = false
        set(value) { field = value; if (value) showConsent() }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences("debit_sms", Context.MODE_PRIVATE)
    }

    /** Called when the UPI app is opened for an order. Listens for the next SMS for 5 minutes. */
    fun start(orderId: String) {
        prefs.edit().putString(KEY_ORDER, orderId).apply()
        tries = 0
        listen()
    }

    private fun listen() {
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                    val status = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_STATUS, Status::class.java)
                    if (status?.statusCode != CommonStatusCodes.SUCCESS) return
                    pendingConsent = IntentCompat.getParcelableExtra(intent, SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                        ?: return
                    showConsent()
                }
            }
            ContextCompat.registerReceiver(
                appContext, r, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = r
        }
        runCatching { SmsRetriever.getClient(appContext).startSmsUserConsent(null) }
    }

    private fun showConsent() {
        val consent = pendingConsent ?: return
        val l = launcher ?: return
        if (!resumed) return
        pendingConsent = null
        runCatching { l.launch(consent) }
    }

    /** Called by MainActivity with the consent screen's result. */
    fun onConsentResult(ok: Boolean, data: Intent?) {
        val text = if (ok) data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE) else null
        val order = prefs.getString(KEY_ORDER, null) ?: return
        if (text == null || !Regex("\\bdebit", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            // Not the bank's debit SMS (or the customer said no): wait for one more message.
            if (ok && tries++ < 2) listen()
            return
        }
        scope.launch {
            runCatching { supabase.auth.awaitInitialization() }
            repeat(5) { attempt ->
                val sent = runCatching { Repo.reportDebitSms(order, text) }
                sent.onSuccess { p ->
                    prefs.edit().remove(KEY_ORDER).apply()
                    UpiPay.result.value = p
                    if (p.status == "paid") {
                        AppState.accessVersion.value++
                        MetaEvents.checkPurchases()
                        Toast.makeText(appContext, "Payment confirmed! Your subscription is now active.", Toast.LENGTH_LONG).show()
                    } else if (p.status == "submitted" || p.status == "review") {
                        Toast.makeText(appContext, "Payment found — verifying. Your plan starts as soon as it's confirmed.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                delay(2_000L * (attempt + 1))
            }
        }
    }

    private const val KEY_ORDER = "order"
}

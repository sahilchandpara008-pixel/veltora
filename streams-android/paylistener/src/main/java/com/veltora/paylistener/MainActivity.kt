package com.veltora.paylistener

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun text(s: String, size: Float = 15f) = TextView(this).apply { this.text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { this.text = s; setOnClickListener { onClick() } }

        root.addView(text("Veltora Pay Listener", 22f))
        root.addView(text("Keep this app on the phone that gets your Google Pay / PhonePe / Paytm / SBI \"money received\" notifications. It sends them to Veltora so plans activate by themselves."))
        status = text("")
        root.addView(status)
        root.addView(button("1. Allow notification access") { askConsent() })
        root.addView(text("2. Paste the SMS link from Admin → Payment Settings → Create new SMS link:"))
        val link = EditText(this).apply { setText(Forwarder.link(this@MainActivity)); hint = "https://…/functions/v1/bank-sms?key=…"; textSize = 13f }
        root.addView(link, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(button("Save link") {
            val v = link.text.toString().trim()
            if (!v.startsWith("https://") || !v.contains("bank-sms?key=")) {
                Toast.makeText(this, "That doesn't look like the Veltora SMS link", Toast.LENGTH_LONG).show()
            } else {
                Forwarder.saveLink(this, v); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); refresh()
            }
        })
        root.addView(button("3. Send test") {
            Forwarder.send(this, "notif:veltora.test", "Veltora Pay Listener test")
            Toast.makeText(this, "Sent — see Recent below", Toast.LENGTH_SHORT).show()
            status.postDelayed({ refresh() }, 3000)
        })
        root.addView(text("4. Phone Settings → Apps → Veltora Pay Listener → Battery → Unrestricted, so the phone doesn't stop it."))
        root.addView(text("Recent", 18f))
        log = text("", 13f)
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })
        if (!getSharedPreferences("veltora_pay_listener", MODE_PRIVATE).contains("consent")) askConsent()
    }

    /** Prominent disclosure: explain exactly what is read and sent, and only then open the system setting. */
    private fun askConsent() {
        AlertDialog.Builder(this)
            .setTitle("Allow Veltora Pay Listener to read payment notifications?")
            .setMessage(
                "This app reads notifications ONLY from Google Pay, PhonePe, Paytm, BHIM and SBI YONO.\n\n" +
                    "When one says money was received, its text (payer name, amount, reference) is sent to your own " +
                    "Veltora server so the customer's plan is activated.\n\n" +
                    "Notifications from every other app are ignored and never leave this phone. Nothing is shared with anyone else.\n\n" +
                    "You can turn this off any time in Settings → Notification access."
            )
            .setPositiveButton("Allow") { _, _ ->
                getSharedPreferences("veltora_pay_listener", MODE_PRIVATE).edit().putBoolean("consent", true).apply()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Deny") { _, _ ->
                getSharedPreferences("veltora_pay_listener", MODE_PRIVATE).edit().putBoolean("consent", false).apply()
                Toast.makeText(this, "Without this, payments must be approved by hand in the admin panel.", Toast.LENGTH_LONG).show()
                refresh()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        Forwarder.retryPending(this)
        refresh()
    }

    private fun refresh() {
        val access = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, PayListenerService::class.java).flattenToString()) == true
        val hasLink = Forwarder.link(this).isNotBlank()
        status.text = buildString {
            append(if (access) "✅ Notification access allowed\n" else "❌ Notification access NOT allowed — tap button 1\n")
            append(if (hasLink) "✅ SMS link saved" else "❌ SMS link not saved — step 2")
        }
        log.text = Forwarder.history(this).joinToString("\n").ifEmpty { "Nothing sent yet." }
    }
}

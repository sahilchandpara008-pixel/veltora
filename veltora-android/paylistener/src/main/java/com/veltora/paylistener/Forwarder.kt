package com.veltora.paylistener

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Sends messages to the Veltora bank-sms link; keeps failed ones and retries them later. */
object Forwarder {
    /** UPI / bank apps whose notifications are forwarded. The server checks the same list. */
    val APPS = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.sbi.lotusintouch" to "SBI YONO",
        "com.sbi.SBIFreedomPlus" to "YONO Lite",
    )

    private const val PREFS = "veltora_pay_listener"
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastError: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun link(ctx: Context) = prefs(ctx).getString("link", "").orEmpty()
    fun saveLink(ctx: Context, link: String) = prefs(ctx).edit().putString("link", link.trim()).apply()
    fun history(ctx: Context): List<String> = readList(ctx, "history")

    /** True the first time this exact notification is seen (apps re-post the same one). */
    @Synchronized
    fun firstTime(ctx: Context, id: String): Boolean {
        val seen = readList(ctx, "seen")
        if (id in seen) return false
        writeList(ctx, "seen", (seen + id).takeLast(60))
        return true
    }

    fun send(ctx: Context, from: String, text: String) = deliver(ctx, JSONObject().put("from", from).put("text", text).toString())

    /** Re-sends messages that could not be delivered earlier (no internet, server busy). */
    fun retryPending(ctx: Context) = deliver(ctx, null)

    private fun deliver(ctx: Context, newItem: String?) {
        val app = ctx.applicationContext
        io.execute {
            val queue = readList(app, "queue").toMutableList()
            if (newItem != null) queue += newItem
            if (queue.isEmpty()) return@execute
            val left = mutableListOf<String>()
            for (item in queue) {
                val o = JSONObject(item)
                val result = post(link(app), o.getString("from"), o.getString("text"))
                if (result == null) left += item
                addHistory(app, "${o.getString("from").removePrefix("notif:")}: ${result ?: (lastError ?: "not sent, will retry")}")
                lastError = null
            }
            writeList(app, "queue", left.takeLast(50))
        }
    }

    /** Returns the server's result, or null when it could not be delivered (retry later). */
    private fun post(link: String, from: String, text: String): String? {
        if (!link.startsWith("https://")) return null
        return try {
            val c = URL(link).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("from", from).put("text", text).toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            when {
                // Wrong/old link: keep the message so it is re-sent once the right link is saved.
                code == 401 -> { lastError = "wrong link (create a new SMS link and paste it again)"; null }
                code >= 500 -> null
                else -> runCatching { JSONObject(body).optString("result", body) }.getOrDefault(body).take(120)
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun addHistory(ctx: Context, line: String) {
        val time = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        writeList(ctx, "history", (listOf("$time  $line") + readList(ctx, "history")).take(20))
    }

    private fun readList(ctx: Context, key: String): List<String> {
        val a = runCatching { JSONArray(prefs(ctx).getString(key, "[]")) }.getOrDefault(JSONArray())
        return List(a.length()) { a.getString(it) }
    }

    private fun writeList(ctx: Context, key: String, list: List<String>) {
        prefs(ctx).edit().putString(key, JSONArray(list).toString()).apply()
    }
}

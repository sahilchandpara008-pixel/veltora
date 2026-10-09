package com.streams.app.data

import android.content.Context
import android.net.Uri
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.upload
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * Every read/write the app does. Security is enforced by Postgres (RLS + functions in
 * supabase/schema.sql) — this file never decides who is allowed to see or pay for what.
 */
object Repo {

    // ---------------------------------------------------------------- auth
    suspend fun signInWithGoogle() = supabase.auth.signInWith(Google, redirectUrl = Links.LOGIN)

    suspend fun sendMagicLink(email: String) =
        supabase.auth.signInWith(OTP, redirectUrl = Links.LOGIN) {
            this.email = email.trim()
            createUser = true
        }

    /** Customer e-mail + password sign-in. */
    suspend fun passwordSignIn(email: String, password: String) =
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }

    /**
     * New account with e-mail + password. Returns true when the person is signed in straight away,
     * false when Supabase first wants the address confirmed (a confirmation e-mail was sent).
     */
    suspend fun passwordSignUp(email: String, password: String): Boolean {
        supabase.auth.signUpWith(Email, redirectUrl = Links.LOGIN) {
            this.email = email.trim()
            this.password = password
        }
        return supabase.auth.currentSessionOrNull() != null
    }

    /** "Forgot password?" — e-mails a one-time link that opens the set-password screen. */
    suspend fun sendPasswordLink(email: String) =
        supabase.auth.signInWith(OTP, redirectUrl = Links.SET_PASSWORD) {
            this.email = email.trim()
            createUser = false
        }

    suspend fun adminPasswordSignIn(email: String, password: String) =
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }

    /** "Forgot password?" — e-mails a one-time link that opens the set-password screen. */
    suspend fun sendAdminPasswordLink(email: String) =
        supabase.auth.signInWith(OTP, redirectUrl = Links.ADMIN_SET_PASSWORD) {
            this.email = email.trim()
            createUser = true
        }

    suspend fun setPassword(newPassword: String) {
        supabase.auth.updateUser { password = newPassword }
    }

    suspend fun signOut() = supabase.auth.signOut()

    suspend fun myAdminRole(): String? =
        supabase.postgrest.rpc("my_admin_role").data.trim().trim('"').takeIf { it.isNotBlank() && it != "null" }

    // ---------------------------------------------------------------- catalogue
    /** Waits for the saved session to load at app start, so a signed-in member isn't mistaken for a guest. */
    private suspend fun signedIn(): Boolean {
        runCatching { supabase.auth.awaitInitialization() }
        return supabase.auth.currentUserOrNull() != null
    }

    /**
     * Before sign-in the catalog comes from catalog_for_install: the same titles everyone sees,
     * plus the STREAMS_SPECIAL ones when this install came from Meta ads (decided by the server).
     */
    suspend fun titles(): List<Title> =
        if (!signedIn()) {
            supabase.postgrest.rpc("catalog_for_install", buildJsonObject { put("p_install_id", Attribution.installId) })
                .decodeList()
        } else {
            supabase.from("titles").select {
                order("created_at", Order.DESCENDING)
            }.decodeList()
        }

    suspend fun title(id: String): Title? =
        if (!signedIn()) titles().firstOrNull { it.id == id }
        else supabase.from("titles").select { filter { eq("id", id) } }.decodeList<Title>().firstOrNull()

    /** Trailer link for a Meta-ads install that hasn't signed in yet (Edge Function "trailer-url"). */
    suspend fun adsTrailerUrl(titleId: String): String {
        val res = supabase.functions.invoke("trailer-url", buildJsonObject {
            put("title_id", titleId); put("install_id", Attribution.installId)
        })
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        return body["url"]?.jsonPrimitive?.content ?: error(body["error"]?.jsonPrimitive?.content ?: "Sign in to watch.")
    }

    suspend fun channels(): List<Channel> =
        supabase.from("channels").select { order("sort_order", Order.ASCENDING) }.decodeList()

    suspend fun episodes(titleId: String): List<Episode> =
        if (!signedIn()) {
            supabase.postgrest.rpc("episodes_for_install", buildJsonObject {
                put("p_install_id", Attribution.installId); put("p_title_id", titleId)
            }).decodeList()
        } else supabase.from("episodes").select {
            filter { eq("title_id", titleId) }
            order("episode_number", Order.ASCENDING)
        }.decodeList()

    suspend fun activeCampaign(): Campaign? =
        supabase.from("campaigns").select {
            filter { eq("active", true) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Campaign>().firstOrNull()

    /** Signed, time-limited link to a file in the private "videos" bucket. Postgres decides if allowed. */
    suspend fun streamUrl(path: String): String =
        supabase.storage.from("videos").createSignedUrl(path, 6.hours)

    suspend fun recordView(titleId: String, preview: Boolean) = runCatching {
        supabase.postgrest.rpc("record_view", buildJsonObject {
            put("p_title_id", titleId); put("p_preview", preview)
        })
    }

    // ---------------------------------------------------------------- subscription & payments
    suspend fun plans(): List<Plan> =
        supabase.from("subscription_plans").select {
            filter { eq("active", true) }
            order("sort_order", Order.ASCENDING)
        }.decodeList()

    suspend fun mySubscription(): Subscription? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        return supabase.from("subscriptions").select { filter { eq("user_id", uid) } }
            .decodeList<Subscription>().firstOrNull()
    }

    suspend fun hasActiveSubscription(): Boolean =
        supabase.postgrest.rpc("has_active_subscription").data.trim() == "true"

    suspend fun paymentSettings(): PaymentSettings =
        supabase.from("app_settings").select().decodeSingle()

    suspend fun myPayments(): List<Payment> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        return supabase.from("payments").select {
            filter { eq("user_id", uid); neq("status", "initiated") }
            order("created_at", Order.DESCENDING)
        }.decodeList()
    }

    /** Price is looked up on the server from planId — the app never sends an amount. */
    suspend fun submitPayment(planId: String, reference: String): Payment =
        supabase.postgrest.rpc("submit_payment", buildJsonObject {
            put("p_plan_id", planId); put("p_reference", reference)
        }).decodeAs()

    /** Step 1 of an automatic UPI payment: the server creates the order at the plan's real price. */
    suspend fun startUpiPayment(planId: String): Payment =
        supabase.postgrest.rpc("start_upi_payment", buildJsonObject { put("p_plan_id", planId) }).decodeAs()

    /** Re-read one of my orders (the app polls it while waiting for the payment to arrive). */
    suspend fun payment(id: String): Payment? =
        supabase.from("payments").select { filter { eq("id", id) } }.decodeList<Payment>().firstOrNull()

    /** My unfinished automatic order from the last hour, if any (so waiting survives an app restart). */
    suspend fun myOpenOrder(): Payment? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val since = java.time.OffsetDateTime.now().minusHours(24).toString()   // open orders stay recoverable for 24 h
        return supabase.from("payments").select {
            filter { eq("user_id", uid); eq("status", "initiated"); gt("created_at", since) }
            order("created_at", Order.DESCENDING)
            limit(1)
        }.decodeList<Payment>().firstOrNull()
    }

    /** Admin saw this order's money in the bank statement: activates it with that UTR as evidence. */
    suspend fun adminVerifyPayment(paymentId: String, utr: String) {
        supabase.postgrest.rpc("admin_verify_payment", buildJsonObject {
            put("p_payment_id", paymentId); put("p_utr", utr)
        })
    }

    /**
     * Step 2: send whatever the UPI app returned to the backend. SUCCESS → plan applied now,
     * FAILURE → order marked failed, anything else → order stays open.
     */
    /**
     * The customer may spend minutes in the UPI app; the sign-in token can expire meanwhile and the
     * server then answers 401. Refresh the session first so the UPI app's answer is never rejected.
     */
    suspend fun reportUpiResult(paymentId: String, response: String): Payment {
        runCatching { supabase.auth.refreshCurrentSession() }
        return supabase.postgrest.rpc("report_upi_result", buildJsonObject {
            put("p_payment_id", paymentId); put("p_response", response)
        }).decodeAs()
    }

    /**
     * Ask the backend for the authoritative status of my order. The server tries trusted
     * verification (bank credit seen on the owner's phone; later the bank's status API) and
     * returns the order — the app never decides that a payment succeeded.
     */
    suspend fun checkPaymentStatus(paymentId: String): Payment =
        supabase.postgrest.rpc("check_payment_status", buildJsonObject { put("p_payment_id", paymentId) }).decodeAs()

    /** Customer closes an unfinished order (UPI app gave no answer and they did not pay). */
    suspend fun cancelUpiPayment(paymentId: String) {
        supabase.postgrest.rpc("cancel_upi_payment", buildJsonObject { put("p_payment_id", paymentId) })
    }

    /** Step 2: forward the UPI app's response; on Status=SUCCESS the plan is applied immediately. */
    suspend fun confirmUpiPayment(paymentId: String, response: String): Payment =
        supabase.postgrest.rpc("confirm_upi_payment", buildJsonObject {
            put("p_payment_id", paymentId); put("p_response", response)
        }).decodeAs()

    // ---------------------------------------------------------------- acquisition attribution
    // The server parses the referrer and decides the source; the app only passes along what
    // Google Play returned. Users can never write their attribution directly (RLS).

    /** Returns the source the server settled on for this install (meta / organic / direct / other / unknown). */
    suspend fun recordInstall(installId: String, status: String, referrer: String?, clickTs: Long, installTs: Long): String? {
        val res = supabase.postgrest.rpc("record_install", buildJsonObject {
            put("p_install_id", installId); put("p_status", status); put("p_referrer", referrer)
            put("p_click_ts", clickTs); put("p_install_ts", installTs)
        })
        return runCatching { Json.parseToJsonElement(res.data).jsonObject["source"]?.jsonPrimitive?.content }.getOrNull()
    }

    suspend fun attributeUser(installId: String): AttributeResult =
        supabase.postgrest.rpc("attribute_user", buildJsonObject { put("p_install_id", installId) }).decodeAs()

    suspend fun logEvent(event: String, installId: String, contentId: String?) {
        supabase.postgrest.rpc("log_event", buildJsonObject {
            put("p_event", event); put("p_install_id", installId); put("p_content_id", contentId)
        })
    }

    suspend fun attributionReport(from: String?, source: String?, campaign: String?, planId: String?): AttributionReport =
        supabase.postgrest.rpc("admin_attribution_report", buildJsonObject {
            put("p_from", from); put("p_to", null as String?); put("p_source", source)
            put("p_campaign", campaign); put("p_plan", planId)
        }).decodeAs()

    suspend fun attributionUsers(
        from: String?, source: String?, campaign: String?, purchased: Boolean?, planId: String?, search: String?,
    ): List<AttributedUser> =
        supabase.postgrest.rpc("admin_attribution_users", buildJsonObject {
            put("p_from", from); put("p_to", null as String?); put("p_source", source); put("p_campaign", campaign)
            put("p_purchased", purchased); put("p_plan", planId); put("p_search", search?.ifBlank { null }); put("p_limit", 200)
        }).decodeAs()

    suspend fun userDetail(userId: String): UserDetail =
        supabase.postgrest.rpc("admin_user_detail", buildJsonObject { put("p_user_id", userId) }).decodeAs()

    // ---------------------------------------------------------------- admin
    suspend fun adminStats(): AdminStats = supabase.postgrest.rpc("admin_stats").decodeAs()

    suspend fun adminPayments(status: String?): List<Payment> =
        supabase.from("payments").select {
            if (status != null) filter { eq("status", status) }
            order("created_at", Order.DESCENDING)
            limit(200)
        }.decodeList()

    suspend fun approvePayment(id: String) {
        supabase.postgrest.rpc("approve_payment", buildJsonObject { put("p_payment_id", id) })
    }

    suspend fun rejectPayment(id: String, note: String) {
        supabase.postgrest.rpc("reject_payment", buildJsonObject {
            put("p_payment_id", id); put("p_note", note)
        })
    }

    suspend fun revokePayment(id: String, note: String) {
        supabase.postgrest.rpc("revoke_payment", buildJsonObject {
            put("p_payment_id", id); put("p_note", note)
        })
    }

    suspend fun saveSettings(s: PaymentSettings) {
        supabase.from("app_settings").update(buildJsonObject {
            put("upi_id", s.upiId.trim()); put("payee_name", s.payeeName.trim())
            put("merchant_code", s.merchantCode?.trim()?.ifEmpty { null })
        }) { filter { eq("id", 1) } }
    }

    suspend fun team(): List<AdminEmail> = supabase.from("admin_emails").select().decodeList()

    suspend fun addManager(email: String) {
        supabase.from("admin_emails").insert(buildJsonObject {
            put("email", email.trim().lowercase()); put("role", "manager")
        })
    }

    suspend fun removeManager(email: String) {
        supabase.from("admin_emails").delete { filter { eq("email", email) } }
    }

    suspend fun saveChannel(id: String?, name: String, description: String, premium: Boolean) {
        val body = buildJsonObject {
            put("name", name.trim()); put("description", description.trim()); put("is_premium", premium)
        }
        if (id == null) supabase.from("channels").insert(body)
        else supabase.from("channels").update(body) { filter { eq("id", id) } }
    }

    suspend fun deleteChannel(id: String) {
        supabase.from("channels").delete { filter { eq("id", id) } }
    }

    suspend fun campaigns(): List<Campaign> =
        supabase.from("campaigns").select { order("created_at", Order.DESCENDING) }.decodeList()

    suspend fun saveCampaign(id: String?, name: String, message: String, active: Boolean) {
        val body = buildJsonObject { put("name", name.trim()); put("message", message.trim()); put("active", active) }
        if (id == null) supabase.from("campaigns").insert(body)
        else supabase.from("campaigns").update(body) { filter { eq("id", id) } }
    }

    suspend fun deleteCampaign(id: String) {
        supabase.from("campaigns").delete { filter { eq("id", id) } }
    }

    suspend fun setPublished(id: String, published: Boolean) {
        supabase.from("titles").update({ set("published", published) }) { filter { eq("id", id) } }
    }

    suspend fun setTier(id: String, tier: String) {
        supabase.from("titles").update({ set("tier", tier) }) { filter { eq("id", id) } }
    }

    suspend fun setFeatured(id: String, featured: Boolean) {
        supabase.from("titles").update({ set("featured", featured) }) { filter { eq("id", id) } }
    }

    /** Insert when [id] is null, otherwise update. Returns the title id. */
    suspend fun saveTitle(id: String?, fields: JsonObject): String {
        return if (id == null) {
            supabase.from("titles").insert(fields) { select() }.decodeSingle<Title>().id
        } else {
            supabase.from("titles").update(fields) { filter { eq("id", id) } }
            id
        }
    }

    suspend fun deleteTitle(t: Title) {
        val eps = episodes(t.id)
        supabase.from("titles").delete { filter { eq("id", t.id) } }
        runCatching {
            val videos = listOfNotNull(t.videoPath, t.trailerPath) + eps.map { it.videoPath }
            if (videos.isNotEmpty()) supabase.storage.from("videos").delete(videos)
            t.coverPath?.let { supabase.storage.from("images").delete(it) }
        }
    }

    suspend fun addEpisode(titleId: String, number: Int, name: String, videoPath: String) {
        supabase.from("episodes").insert(buildJsonObject {
            put("title_id", titleId); put("episode_number", number); put("name", name.trim()); put("video_path", videoPath)
        })
    }

    suspend fun deleteEpisode(e: Episode) {
        supabase.from("episodes").delete { filter { eq("id", e.id) } }
        runCatching { supabase.storage.from("videos").delete(e.videoPath) }
    }

    // ---------------------------------------------------------------- premium cloud storage
    suspend fun myFiles(): List<CloudFile> = supabase.postgrest.rpc("my_files").decodeList()

    suspend fun cloudUsageBytes(): Long =
        supabase.postgrest.rpc("my_cloud_usage").data.trim().trim('"').toLongOrNull() ?: 0L

    suspend fun cloudFileUrl(name: String): String =
        supabase.storage.from("user-files").createSignedUrl(name, 1.hours)

    suspend fun deleteCloudFile(name: String) {
        supabase.storage.from("user-files").delete(name)
    }

    /** Uploads a picked file into the member's own folder, keeping its original name. */
    suspend fun uploadCloudFile(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val uid = supabase.auth.currentUserOrNull()?.id ?: error("Please sign in first")
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val original = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        val safe = original.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "file" }
        val tmp = File.createTempFile("cloud", null, context.cacheDir)
        try {
            resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val path = "$uid/${System.currentTimeMillis()}__$safe"
            supabase.storage.from("user-files").upload(path, tmp) {
                upsert = false
                contentType = ContentType.parse(mime)
            }
            path
        } finally {
            tmp.delete()
        }
    }

    /**
     * Copies the picked file to a temp file (so big videos stream from disk instead of memory)
     * and uploads it. Returns the storage path to save on the title.
     */
    suspend fun uploadFile(context: Context, uri: Uri, bucket: String, folder: String): String =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val ext = when {
                mime.contains("mp4") -> "mp4"
                mime.contains("quicktime") -> "mov"
                mime.contains("webm") -> "webm"
                mime.contains("matroska") -> "mkv"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.startsWith("image") -> "jpg"
                else -> "bin"
            }
            val tmp = File.createTempFile("upload", ".$ext", context.cacheDir)
            try {
                resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val path = "$folder/${UUID.randomUUID()}.$ext"
                supabase.storage.from(bucket).upload(path, tmp) {
                    upsert = false
                    contentType = ContentType.parse(mime)
                }
                path
            } finally {
                tmp.delete()
            }
        }
}

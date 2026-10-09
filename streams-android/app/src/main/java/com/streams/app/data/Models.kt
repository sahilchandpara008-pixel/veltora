package com.streams.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object Tier {
    const val FREE = "free"
    const val PREMIUM = "premium"
    const val HIDDEN = "hidden_premium"

    fun next(tier: String) = when (tier) {
        FREE -> PREMIUM
        PREMIUM -> HIDDEN
        else -> FREE
    }

    fun label(tier: String) = when (tier) {
        FREE -> "Free"
        PREMIUM -> "Premium"
        else -> "Exclusive"
    }
}

@Serializable
data class Channel(
    val id: String = "",
    val name: String,
    val description: String = "",
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Title(
    val id: String = "",
    val kind: String = "movie",                 // "movie" or "series"
    val name: String,
    val description: String = "",
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("trailer_path") val trailerPath: String? = null,
    @SerialName("video_path") val videoPath: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val tier: String = Tier.FREE,
    val published: Boolean = false,
    val featured: Boolean = false,
    @SerialName("view_count") val viewCount: Long = 0,
    @SerialName("preview_count") val previewCount: Long = 0,
    /** Content tags, e.g. STREAMS_SPECIAL (Meta-campaign content, filtered by the server). */
    val tags: List<String> = emptyList(),
) {
    val isSeries get() = kind == "series"
    val isStreamsSpecial get() = Tags.STREAMS_SPECIAL in tags
}

object Tags {
    const val STREAMS_SPECIAL = "STREAMS_SPECIAL"
    const val NEW = "NEW"
}

@Serializable
data class Episode(
    val id: String = "",
    @SerialName("title_id") val titleId: String,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String,
    val description: String = "",
    @SerialName("video_path") val videoPath: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
)

@Serializable
data class Plan(
    val id: String,
    val name: String,
    @SerialName("duration_label") val durationLabel: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("duration_hours") val durationHours: Int = 0,
    val price: Double,
    val currency: String = "INR",
    val active: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Subscription(
    @SerialName("user_id") val userId: String,
    @SerialName("plan_id") val planId: String? = null,
    val status: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class Payment(
    val id: String,
    @SerialName("user_email") val userEmail: String? = null,
    @SerialName("plan_id") val planId: String,
    @SerialName("plan_name") val planName: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val reference: String,
    val status: String,
    @SerialName("admin_note") val adminNote: String? = null,
    @SerialName("created_at") val createdAt: String,
    /** "manual" (customer typed a UTR) or "upi_auto" (confirmed from the UPI app's response). */
    val method: String = "manual",
    @SerialName("txn_id") val txnId: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    /** Set when the money was seen arriving (bank SMS / UPI app notification on the owner's phone). */
    @SerialName("bank_credit_id") val bankCreditId: String? = null,
    /** Exactly what the UPI app returned ("txnId=..&Status=SUCCESS|FAILURE|..&responseCode=.."). */
    @SerialName("upi_response") val upiResponse: String? = null,
    /** What the UPI app said (success/failure/…): a client report only, never proof of payment. */
    @SerialName("client_status") val clientStatus: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
    /** Who verified it: bank_credit | admin | provider_api. */
    @SerialName("verification_source") val verificationSource: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** Order status shown to people: CREATED · PENDING · SUCCESS · FAILED · CANCELLED (+ admin-review states). */
    val displayStatus: String
        get() = when (status) {
            "initiated" -> if (clientStatus == null) "CREATED" else "PENDING"
            "approved" -> "SUCCESS"
            "failed" -> "FAILED"
            "cancelled" -> "CANCELLED"
            "pending" -> "UTR REVIEW"
            "rejected" -> "REJECTED"
            "revoked" -> "REVOKED"
            else -> status.uppercase()
        }

    private fun upiField(key: String) = upiResponse?.split('&')
        ?.firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
        ?.substringAfter('=', "")?.takeIf { it.isNotBlank() }

    /** e.g. "SUCCESS", "FAILURE (U30)", "NO RESPONSE" — the payment status reported by the UPI app. */
    val upiStatusLabel: String?
        get() {
            val st = upiField("Status")?.uppercase()?.replace('_', ' ') ?: return null
            val code = upiField("responseCode")?.takeIf { st != "NO RESPONSE" }
            return if (code != null) "$st ($code)" else st
        }

    val isAuto get() = method == "upi_auto"
    val bankVerified get() = bankCreditId != null
    /** The number to look for in the bank statement. */
    val utr get() = txnId ?: reference
}

@Serializable
data class PaymentSettings(
    @SerialName("upi_id") val upiId: String,
    @SerialName("payee_name") val payeeName: String,
    @SerialName("cloud_quota_gb") val cloudQuotaGb: Int = 2048,
    /** Merchant category code (mc) for a merchant UPI ID, e.g. from BharatPe / Paytm / PhonePe Business. */
    @SerialName("merchant_code") val merchantCode: String? = null,
)

/** A file in the member's private Cloud Storage folder (user-files/<user id>/...). */
@Serializable
data class CloudFile(
    val name: String,
    val size: Long = 0,
    val mimetype: String? = null,
    @SerialName("created_at") val createdAt: String,
) {
    /** Display name without the folder and the upload-time prefix. */
    val displayName: String get() = name.substringAfterLast('/').substringAfter("__")
}

@Serializable
data class AdminEmail(val email: String, val role: String)

@Serializable
data class Campaign(
    val id: String = "",
    val name: String,
    val message: String = "",
    val active: Boolean = false,
)

@Serializable
data class AdminStats(
    @SerialName("total_videos") val totalVideos: Long = 0,
    @SerialName("exclusive_videos") val exclusiveVideos: Long = 0,
    @SerialName("total_views") val totalViews: Long = 0,
    @SerialName("preview_views") val previewViews: Long = 0,
    @SerialName("paying_subscribers") val payingSubscribers: Long = 0,
    @SerialName("expired_subscribers") val expiredSubscribers: Long = 0,
    @SerialName("pending_payments") val pendingPayments: Long = 0,
)

// ---------------------------------------------------------------- acquisition attribution

@Serializable
data class AttributeResult(
    val source: String? = null,
    val special: Boolean = false,
    val pending: Boolean = false,
)

/** One row of the admin attribution report (a source, or a source + campaign). */
@Serializable
data class AttributionRow(
    val source: String,
    val campaign: String? = null,
    val installs: Long = 0,
    val registrations: Long = 0,
    val purchasers: Long = 0,
    val purchases: Long = 0,
    val revenue: Double = 0.0,
)

@Serializable
data class AttributionReport(
    @SerialName("by_source") val bySource: List<AttributionRow> = emptyList(),
    val campaigns: List<AttributionRow> = emptyList(),
)

@Serializable
data class AttributedUser(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    @SerialName("registered_at") val registeredAt: String,
    val source: String = "unknown",
    val campaign: String? = null,
    val content: String? = null,
    @SerialName("first_touch_at") val firstTouchAt: String? = null,
    @SerialName("last_touch_source") val lastTouchSource: String? = null,
    @SerialName("last_touch_at") val lastTouchAt: String? = null,
    @SerialName("sub_status") val subStatus: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("plan_name") val planName: String? = null,
    val revenue: Double = 0.0,
    val purchases: Long = 0,
)

@Serializable
data class UserAttributionInfo(
    @SerialName("first_touch_source") val firstTouchSource: String = "unknown",
    @SerialName("first_touch_medium") val firstTouchMedium: String? = null,
    @SerialName("first_touch_campaign") val firstTouchCampaign: String? = null,
    @SerialName("first_touch_content") val firstTouchContent: String? = null,
    @SerialName("first_touch_at") val firstTouchAt: String? = null,
    @SerialName("last_touch_source") val lastTouchSource: String? = null,
    @SerialName("last_touch_campaign") val lastTouchCampaign: String? = null,
    @SerialName("last_touch_at") val lastTouchAt: String? = null,
)

@Serializable
data class UserSubscriptionInfo(
    val status: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val plan: String? = null,
)

@Serializable
data class UserPaymentInfo(
    val id: String,
    val plan: String? = null,
    val amount: Double? = null,
    val status: String,
    val method: String? = null,
    val utr: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("attribution_source") val attributionSource: String? = null,
    @SerialName("attribution_campaign") val attributionCampaign: String? = null,
)

@Serializable
data class UserDetail(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    @SerialName("registered_at") val registeredAt: String,
    val attribution: UserAttributionInfo? = null,
    val subscription: UserSubscriptionInfo? = null,
    val payments: List<UserPaymentInfo> = emptyList(),
    val revenue: Double = 0.0,
)

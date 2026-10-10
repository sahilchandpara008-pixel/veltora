# Veltora — full rebuild prompt

Copy everything below the line into a new AI coding session (Claude Code, Lovable, etc.).
Fill in the values in `<angle brackets>` first. Never paste passwords, service-role keys or
access tokens into the prompt — add those yourself in the dashboards when asked.

---

Build **Veltora**, a Netflix-style video streaming product with three parts that share one
Supabase backend:

1. **Android app** (Kotlin + Jetpack Compose), distributed as a direct APK download from the website (not Play Store).
2. **Web app** (React + TypeScript + Tailwind, mobile-first, dark theme) that works fully in the browser.
3. **Supabase backend**: Postgres with RLS, SECURITY DEFINER RPCs, Storage and Edge Functions.

Use only the public anon key in the app and repo. Server secrets live only in Supabase.
If anything is unclear, stop and ask me — never guess.

## 1. Branding
- Name: Veltora. Dark UI (near-black background, red accent, gold for Premium), Poppins headings.
- Professional adaptive app icon (red "V" play mark on dark), splash icon, favicon. No builder watermark/branding anywhere.

## 2. Two kinds of users (audience model)
Every visitor/install is either **ads** (came from my Meta ads) or **organic** (everyone else).
- Detection:
  - Web: `utm_*` parameters on the landing URL.
  - Android: the Play Install Referrer, or a build flag baked into a separate `Veltora-ads.apk`.
  - `utm_source` of facebook/fb/instagram/ig/meta/an/messenger/threads counts as **meta**. The visit is recorded with an install id (`record_install`), and after sign-up the account inherits it (`attribute_user` → `user_attribution.first_touch_source`). First touch is never overwritten.
- Channels and titles have `audience` = `organic` | `ads` | `all` ("Organic users only", "Ads users only", "Both").
  - Organic users see organic + all.
  - Ads users see ads + all.
  - Admins see everything.
  - Enforce this in RLS and in the guest RPCs (`catalog_for_install`, `channels_for_install`, `episodes_for_install`), not only in the UI.
- Titles tagged `STREAMS_SPECIAL` are only returned to ads (meta) accounts.
- Tiers:
  - `free`
  - `premium` (needs an active subscription)
  - `hidden_premium` (only visible with an active subscription)

## 3. Content and watching
- Titles: movie or series (episodes), cover/thumbnail, trailer, full video, description, tags (NEW, STREAMS_SPECIAL), channel, tier, audience, published, featured.
- Storage buckets:
  - `images` (public)
  - `videos` (private, signed URLs only)
- Guests can browse and watch trailers. When a trailer ends:
  - guests see "Sign in to watch";
  - signed-in users see "Watch full video".
- Full videos need sign-in, and premium tiers need a plan.
- Home rows:
  - featured;
  - "From channels you joined";
  - "More from <channel>" on the title page.
- Shows tab with search.
- Player (Media3 ExoPlayer on Android):
  - auto-rotate, plus a full-screen toggle to landscape;
  - audio focus, keep screen on;
  - error overlay with retry.
- Report button on titles/channels (reasons list) → admin Reports tab.

## 4. Creator channels (user-generated)
- Any signed-in user can create a channel with a name, description and photo (button "Create", accept creator terms). The channel starts **pending**.
- The admin sees requests with the creator's email and approves or rejects them (with a reason shown to the creator). When approving, the admin picks the audience: Organic only / Ads only / Both.
- Once a channel is approved, the creator's uploads publish automatically with the channel's audience. The admin can still unpublish or change a single video's audience.
- Creator upload form (full-screen):
  - title and description;
  - thumbnail — optional: if skipped, take a frame from the video at 10% of its length (max 10 s), JPEG; also on Edit → Save for old videos;
  - optional trailer;
  - full video (required);
  - step progress.
- The creator can edit/delete videos, change the channel photo, and delete the channel.
- Users can **Join** channels (member counts are public). Guests who tap Join go to sign-in.

## 5. Accounts
- Supabase Auth with email + password and magic link, using the implicit flow so links work in any browser.
- Robust set-password page.
- Site URL and redirect URLs set to `<web URL>/**` and `veltora://login-callback/**`.
- Profile:
  - membership card, plans, payment history;
  - "My channels", Privacy, Terms;
  - Delete account (type DELETE). The Edge Function `delete-account` removes the user's files and the auth user, and refuses for the owner.
- Legal pages: /privacy, /terms, /child-safety, /delete-account.
- No cloud-storage feature (streaming only).

## 6. Plans and payments — my own UPI, no payment gateway
- Plans (editable in admin):
  - 1 Hour Pass ₹1
  - Trial ₹99 / 3 days
  - Silver ₹149 / 7 days
  - Gold ₹259 / 30 days
  - Platinum ₹599 / 180 days
  - Diamond ₹999 / 365 days
- Settings: UPI ID `<my UPI ID>`, payee name "Veltora", free-mode switch (free mode hides all prices).
- Charge the **exact plan price** (no extra paise). The amount and the order are created server-side (`start_upi_payment`), and the client never sets the price.
- Android: open a UPI intent (`upi://pay?pa=…&pn=…&tr=<order>&am=<amount>&cu=INR`) and report the UPI app's answer (`report_upi_result`).
  - **ads** account + SUCCESS + transaction id (used once, within 2 h) → activate the plan.
  - **organic** account → order goes to `pending` ("Needs approval").
- Web: on mobile, the "Open UPI app" link; on desktop, a QR code. Poll `check_payment_status` every 5 s for 30 min. The "Paid already? Enter UTR" fallback is `submit_payment`.
- **Bank SMS auto-verify** (no gateway):
  - An SMS-forwarder app on the owner's phone POSTs the bank's credit SMS to the Edge Function `bank-sms?key=<secret>`. The function calls `ingest_bank_sms` (service role only).
  - The key is generated in admin, only its SHA-256 hash is stored, and it is shown once.
  - Accept only SMS whose sender contains the configured bank sender name (e.g. BANDHN). Ignore SMS from phone numbers, debit SMS, non-credit SMS and duplicates (same UPI ref).
  - Parse the amount (`Rs/INR/₹`) and the 12-digit UPI ref, and store them in `bank_credits`.
  - Match order: first by UPI ref (= customer's UTR or the UPI app txn id); otherwise by amount, but only if exactly one open order has that amount in the last 30 min. If several match, wait for the customer's UTR.
  - When matched: ads account → activate; organic → `pending` with the bank proof attached (admin approves).
  - Log every SMS in `bank_sms_log` (sender, result, amount, ref).
- Only one function grants plans (`apply_verified_payment`, idempotent, checks amount and the single use of the txn id). Subscriptions extend from the current expiry.
- Customer wording:
  - pending = "Payment received — waiting for approval";
  - never mention ads/organic to customers.

## 7. Admin panel (in the app and on the web)
Roles: owner and manager.

Tabs:
- **Overview**
- **Attribution**: installs, sign-ups, purchases and revenue by source/campaign.
- **Payments**
  - Filters: All, Needs approval, Success, Unpaid, Failed, Cancelled, Rejected, Revoked.
  - Badges: ADS/ORGANIC (from `payments.attribution_source`, set when the order is created), "Bank SMS ✓".
  - Actions: Approve / Reject / Revoke / Mark as paid with UTR.
- **Content**: CRUD titles and episodes, uploads, audience, tier, Special tag, NEW tag, featured.
- **Creators**: channel requests and creator videos, approve/reject with reason, audience picker.
- **Reports**
- **Channels**
- **Campaigns**: an active banner message.
- **Team** (owner only)
- **Settings** (owner only):
  - UPI, payee, free mode;
  - Meta tracking;
  - Bank SMS auto-verify (switch, sender name, "Create new SMS link", last 20 SMS, setup steps).

## 8. Meta (Facebook) ads tracking — configured from admin only
- Admin enters App ID, Client Token, Pixel ID, Conversions API access token and an optional test event code, then switches tracking on.
- The token is stored server-side only (`meta_secrets`), and the status view shows only a hint of it.
- Android: Meta SDK (`facebook-core`) initialised at runtime from the admin settings. Remove its auto-init provider from the manifest.
- Events: install/app open, CompleteRegistration, ViewContent, Search, VideoPlay, TrailerComplete, Purchase with value.
- Web: Pixel + Conversions API (Edge Function `meta-capi`) with the same event_id for dedup, behind an opt-in consent bar and a Profile "Ad tracking" toggle.
- CompleteRegistration and Purchase are claimed once server-side, so they are never double-counted. Purchase is sent with the plan value for ROAS campaigns.
- No cloaking, no reviewer/bot detection, and no different version for reviewers or Google — ads vs organic only changes which catalog a signed-in or attributed user sees.

## 9. Web app extras for ad traffic
- Inside the Facebook/Instagram in-app browser on Android:
  - sticky bar with "Open in Chrome" (`intent://…#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=…;end`, keeping all utm params);
  - one automatic redirect attempt per session.
- iPhone: show the hint "Tap ••• then Open in browser".
- If a later visit has `utm_*` and the stored source isn't meta, record the install again.
- Home hero for signed-out visitors: "Watch now in your browser", with "Sign up free", "See plans" (scrolls to plans; plans visible to guests) and "Download for Android" (hidden on iPhone).
- `/download` page:
  - ads visitors get `Veltora-ads.apk`, others get `Veltora.apk`;
  - QR code on desktop;
  - "Get app" banner on Android browsers.

## 10. Build and release
- GitHub Actions on every push to main:
  - build debug APKs `Veltora.apk` and `Veltora-ads.apk` and publish them to the release `veltora-latest`;
  - when the signing secrets exist (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_PASSWORD`, `UPLOAD_KEY_ALIAS`), also build a signed release AAB/APK.
- versionCode = 100 + run number.
- Keep the full SQL in `supabase/schema.sql` as numbered sections, idempotent where possible.
- Test SQL on a local Postgres before applying it live.
- Commit messages are plain English. Never commit secrets.

## 11. Definition of done
- A guest from an ad link (with utm) sees ads + all content and trailers in Chrome. After signing up and paying, the plan activates automatically (UPI SUCCESS on Android, or bank SMS) and premium plays online.
- An organic user's paid order waits in "Needs approval" until I approve it.
- The creator flow works end-to-end, with audience control by the admin.
- Every Meta event shows in Events Manager → Test events.

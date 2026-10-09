# Streams — Android app (Kotlin + Jetpack Compose + Supabase)

A native Android streaming app with a **Home / Channels / Shows / Profile** bottom bar, Free / Premium / Exclusive content, manual UPI payments verified by an admin, and a hidden admin panel for uploads, payments, channels, campaigns and team access.

```
streams-android/
├── supabase/schema.sql          ← database, security rules, payment approval logic (run once)
├── app/src/main/java/com/streams/app/
│   ├── data/                    ← Supabase client, models, all database calls (Repo.kt)
│   ├── ui/screens/              ← Auth, Home, Channels, Shows, Title details, Player, Profile
│   ├── ui/admin/                ← Admin sign-in, set password, dashboard, upload/edit form
│   └── ui/theme, ui/components  ← dark + red theme, poster cards, chips, badges
└── local.properties.example     ← where your Supabase keys go
```

---

## Step 1 — Install the tools (one time)

1. Install **Android Studio** from https://developer.android.com/studio and open it once so it downloads the Android SDK.
2. Get this code on your computer: on GitHub, open the branch `claude/practical-ptolemy-cuvsp8`, click **Code → Download ZIP**, and unzip it. (Or `git clone` and check out that branch.)

## Step 2 — Create the backend on Supabase (free)

1. Go to https://supabase.com → **New project**. Pick a region close to India (e.g. Mumbai). Save the database password somewhere safe.
2. Open `supabase/schema.sql` in any text editor. Near the bottom, replace
   `YOUR_OWNER_EMAIL@example.com` with **your own email** (the one you'll use for admin).
3. In Supabase: **SQL Editor → New query**, paste the whole file, click **Run**. It should say "Success".
   This creates every table, all the security rules, the 6 plans (₹1 for 1 hour / ₹99 / ₹149 / ₹259 / ₹599 / ₹999), your UPI ID `2728412a@bandhan`, and the two storage buckets.
4. (Optional but recommended) **Database → Extensions** → enable `pg_cron`, then run the two commented lines at the very end of `schema.sql`. This marks expired plans every 30 minutes. (Expired plans stop working immediately even without it — this only tidies up the status.)

## Step 3 — Sign-in settings in Supabase

**Authentication → URL Configuration**
- Site URL: `streams://login-callback`
- Redirect URLs — add both:
  - `streams://login-callback`
  - `streams://login-callback/**`

**Authentication → Providers → Email**: make sure it is **enabled**. (This covers magic links and the admin password.)

**Authentication → Providers → Google** — use **your own** Google credentials:
1. Go to https://console.cloud.google.com → create a project called "Streams".
2. **APIs & Services → OAuth consent screen** → External → fill in app name "Streams", your email → Save. Then **Publish app** (otherwise only test users can sign in).
3. **APIs & Services → Credentials → Create credentials → OAuth client ID** → type **Web application**.
   - Authorized redirect URI: `https://YOUR-PROJECT-REF.supabase.co/auth/v1/callback`
     (copy the exact value shown on the Supabase Google provider page).
4. Copy the **Client ID** and **Client secret** into Supabase's Google provider and **Save**.

**Email sending (important before launch):** Supabase's built-in email is heavily rate-limited (only a few emails per hour). Before real customers use magic links, set up your own SMTP under **Project Settings → Authentication → SMTP** (Resend, Brevo, Zoho, Gmail SMTP, etc.).

**Video size:** on the free plan, each uploaded file is limited to 50 MB. For full-length videos, upgrade to Pro and raise **Storage → Settings → Upload file size limit**.

## Step 4 — Keys (already done)

The app is already connected to the Streams Supabase project (`kpnfncydvpzlrcjplazh`, Mumbai): its URL and public anon key are built in via `app/build.gradle.kts`. To point the app at a different project, put `SUPABASE_URL=` and `SUPABASE_ANON_KEY=` lines in `local.properties` (or GitHub Actions secrets with the same names) — those override the built-in values.

## Step 5 — Open and run in Android Studio

1. Android Studio → **File → Open** → select the `streams-android` folder (the one containing `settings.gradle.kts`).
2. Wait for **Gradle sync** to finish (bottom bar). The first sync downloads libraries and takes a few minutes. If Android Studio offers to upgrade the Android Gradle Plugin, you can accept.
3. Plug in your Android phone with **USB debugging** on (Settings → About phone → tap Build number 7 times → Developer options → USB debugging), or create an emulator via **Device Manager**.
4. Press the green **Run ▶** button.

> If Gradle sync or the build shows a red error, copy the error text and send it to me — I'll fix it.

## Step 6 — Set your admin password (first time) and log in to admin

The admin area isn't linked anywhere in the app. To open it:

1. Go to **Profile** and tap the small **"Streams 1.0.0"** text at the bottom **7 times** → the **Admin sign in** page opens.
2. Type your owner email, then tap **"Forgot password? / Set first password"**.
3. Open the email **on the same phone** and tap the link → the app opens **Set admin password**. Choose a password.
4. You're in the admin dashboard. Next time, just use email + password on the Admin sign in page.

If someone signs in there who is not on the admin list, they are signed straight back out and see "This account doesn't have admin access".

## Step 7 — Add your content

In **Admin**:
1. **Channels** → *New channel* (e.g. Drama, Comedy). Tick "Contains premium content" if it applies.
2. **Content** → *Upload* → follow the 8 steps: type (single video / series), name & description, channel, thumbnail, optional trailer, main video, who can watch (Free / Premium / Hidden Premium), and Publish. New uploads are **drafts** by default.
3. In the Content list: tap **DRAFT/LIVE** to publish/unpublish, tap the **tier badge** to cycle Free → Premium → Exclusive, ⭐ to feature a title in the Home hero, ✏️ to edit (and add more episodes to a series), 🗑 to delete.
4. **Campaigns** → a short banner shown at the top of Home while active.
5. **Team** (owner only) → add manager emails. **Settings** (owner only) → change the UPI ID / payee name.

## Web admin panel (for computers)

`docs/index.html` in this repository is a browser version of the admin panel (same features: stats, payments, uploads with a progress bar, channels, campaigns, team, settings). It is published with GitHub Pages:

1. GitHub repo → **Settings → Pages** → Source: **Deploy from a branch** → Branch: the branch containing `docs/` (e.g. `main` after merging) → Folder: **/docs** → Save.
2. After ~1 minute it is live at `https://sahilchandpara008-pixel.github.io/shopify-mcp-v1/`.
3. For “Forgot password?” on the web panel, add `https://sahilchandpara008-pixel.github.io/**` to Supabase → Authentication → URL Configuration → Redirect URLs.

Only owner/manager accounts can sign in; everyone else is signed straight back out, and the database rejects admin actions from anyone else anyway.

## How payments work

One app does everything: customers use it normally, and the owner opens the admin panel by tapping the version number on Profile 7 times. The app asks for no SMS or notification access, so it installs without Play Protect blocking it.

1. Customer picks a plan on **Profile** → taps **Pay via UPI**. The server creates an order for **exactly the plan price** (order number `UPI…`, sent to the UPI app as `tr`).
2. Their UPI app (GPay, PhonePe, Paytm…) opens with that amount. Whatever the UPI app answers is stored for reference only — **it never activates a plan**.
3. The order stays **Pending** (the customer can tap *Check payment status*; it stays open for 24 h).
4. You match it with your bank statement: **Admin → Payments → Pending → Mark as paid**, type that credit's UTR. The server checks the amount and that the UTR was never used, then applies the plan: `new expiry = later of (now, current expiry) + plan length`.
5. Fully automatic activation needs a trusted status source from the bank (UPI transaction-status API or payment callback for the merchant ID). That plugs into `apply_verified_payment` with source `provider_api` from a server-side function.

**Manual UTR.** A customer can also type their UTR; it waits in **Payments → UTR review** for you to Approve or Reject.

Built-in protections (all enforced in the database, not in the app): the price always comes from the plans table; a customer can only create *pending*/*initiated* payments for themselves; an auto confirmation needs a SUCCESS status, must arrive within 30 minutes of the order, and each transaction ID works once; one pending payment per customer; a UTR can only be used once; only owner/manager accounts can approve; a double-tap cannot approve twice; customers can never write to their own subscription.

## Step 8 — Test checklist

- [ ] Fresh install → the app opens straight on **Home**; Home/Channels/Shows can be browsed without signing in.
- [ ] Tap **Play** on anything while signed out → the sign-in screen appears; after signing in the video starts.
- [ ] Signed in without a plan: free titles play fully; premium titles show trailer / 30-sec preview then "See plans".
- [ ] **Continue with Google** works.
- [ ] **Email me a sign-in link** → open link on the phone → signed in.
- [ ] Admin: set password, sign in, create a channel, upload a free title and a premium title, turn them LIVE.
- [ ] Upload a **Hidden Premium** title → it must NOT appear for a customer without a plan.
- [ ] As a customer, pick a plan → submit a test UTR → shows "Waiting".
- [ ] Admin → Payments → Approve → customer's Profile shows "Active · until …", premium plays fully, hidden title appears.
- [ ] Try to submit the same UTR again → blocked.

**Sign-in safety rule:** whenever you change anything in Supabase Authentication or Google Cloud, immediately test both Google sign-in and email sign-in on a real phone. If people report login problems, first check the Google provider still has its Client ID/secret and that the redirect URLs above are still present.

## Step 9 — Publish to the Play Store (later)

**Build → Generate Signed App Bundle / APK → Android App Bundle** → create a new keystore (**back it up — you can't update the app without it**) → release. Upload the `.aab` in Google Play Console.

Note: Google Play's payment policy requires Play Billing for digital content bought inside apps distributed on Play. Manual UPI is fine for direct APK distribution or your website; check Play's current policy before publishing there.

## Known limits

- **30-second previews** (premium titles with no trailer) are stopped by the app at 30 s. The video file itself has to be streamable for that, so a technical user could watch past 30 s. Uploading a trailer for every premium title avoids this — then non-subscribers only ever get the trailer file.
- Video is streamed as a single MP4 file. For large audiences, a video service with adaptive streaming (e.g. Cloudflare Stream, Mux) is the next upgrade.

## Credits

The app uses the **Poppins** font (© Indian Type Foundry, SIL Open Font License 1.1), bundled in `app/src/main/res/font/`.

## Meta Ads attribution & STREAMS_SPECIAL content

**How it works**
1. Your Meta ad links to the app's **Google Play** page with a `referrer` parameter (build it in web admin → *Attribution → Meta campaign link builder*), e.g.
   `https://play.google.com/store/apps/details?id=com.streams.app&referrer=utm_source%3Dmeta%26utm_medium%3Dpaid_social%26utm_campaign%3Dstreams_special%26utm_content%3Dad_01`
   Meta *app-install* ads add their own referrer (`utm_source=apps.facebook.com…`) automatically — those are recognised as Meta too.
2. On first launch the app reads the **Google Play Install Referrer** (official `com.android.installreferrer` library) and sends it with a random per-install id to `record_install`. The **server** parses it into source / medium / campaign / content / term.
3. When someone signs in, `attribute_user` links that install to the account. **First touch is set once and never overwritten**; it's only given to an account created on/after that install and only to the first account that claims it.
4. Titles tagged **STREAMS_SPECIAL** (admin → content editor → Tags) are returned by the database **only** to accounts whose first-touch source is Meta (RLS on `titles` + `can_stream`). Everyone else gets the normal catalogue. Payments, plans and premium rules are identical for everyone.
5. When a payment is verified (approved by admin, UPI app SUCCESS, or bank-credit match) the buyer's first-touch source/campaign is copied onto the payment (`payments.attribution_*`).
6. Admin → **Attribution** (app and web): installs, registrations, purchasers and revenue by source and by campaign, conversion rates, filters (dates, source, campaign, plan, purchase status), and a per-user detail view.

**Important limits**
- The Install Referrer exists **only for installs from Google Play**. APKs shared over WhatsApp/links always show as *Unknown*.
- Meta app-install ads encrypt campaign details inside `utm_content`; without decrypting them (needs your Meta *install referrer decryption key*) those users show as *Meta* with no campaign name. Use tracked Play Store links (above) for named campaigns.
- The referrer is read on the user's phone. A modified app could send a fake one, which would at most unlock STREAMS_SPECIAL discovery for that one account — never premium access or payments, which stay fully server-verified.

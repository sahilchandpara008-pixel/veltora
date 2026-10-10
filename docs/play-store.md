# Veltora on Google Play — step-by-step

App: **Veltora** · package `com.veltora.app` · free · no in-app purchases · no ads SDK.

## 1. One-time setup

1. **Developer account:** create one at https://play.google.com/console ($25 one-time). Complete the identity verification.
2. **Upload key.** Play needs your own private key; the GitHub build can't use its test key.
   - Create the key on your computer (Android Studio: *Build → Generate Signed App Bundle → Create new…*, or run the command below). Use alias `veltora-upload`.
     ```
     keytool -genkeypair -keystore veltora-upload.jks -storetype PKCS12 -alias veltora-upload -keyalg RSA -keysize 2048 -validity 10000
     ```
   - Keep `veltora-upload.jks` and its password safe (Google Drive + pen drive). Never upload it to GitHub as a file.
   - Turn the file into text (Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("veltora-upload.jks")) | Set-Clipboard`; Mac/Linux: `base64 -w0 veltora-upload.jks`).
   - In GitHub, go to **veltora → Settings → Secrets and variables → Actions → New repository secret** and add these four secrets:

     | Name | Value |
     |---|---|
     | `UPLOAD_KEYSTORE_BASE64` | the base64 text |
     | `UPLOAD_KEYSTORE_PASSWORD` | keystore password |
     | `UPLOAD_KEY_PASSWORD` | key password (same, if you used one password) |
     | `UPLOAD_KEY_ALIAS` | `veltora-upload` |
   - From then on, every build (GitHub → Actions → latest run → **Artifacts**) contains `Veltora-PlayStore-v1.1.N` with the `.aab` file to upload.
3. **Play App Signing:** accept it when you create the first release. Google keeps the app signing key. If you ever lose the upload key, Play support can reset it.

## 2. Create the app in Play Console

- App name **Veltora**, default language English (India), type **App**, **Free**.
- **Testing first:** new personal developer accounts must run a **closed test with at least 12 testers for 14 days** before Production.
  - Go to *Testing → Closed testing → Create track*.
  - Add the testers' Gmail addresses and upload the `.aab`.
  - Share the opt-in link with the testers, and ask them to open the app on several days.
- Then go to *Production → Create release* and upload the newest `.aab`.

## 3. Store listing (copy/paste)

**Short description (≤80):**
Watch movies, series & creator videos free. 15 GB cloud storage included.

**Full description:**
Veltora is a free streaming app for movies, series and videos from creators.

• Watch the full catalog free — just sign in
• Channels from Veltora and from creators, all reviewed by our team
• Start your own channel: add a photo, upload videos and manage them
• 15 GB of private cloud storage for your own files
• Sign in with email and password or a one-time link

Every channel and video from creators is checked before it appears. See something wrong? Tap Report on any video or channel.

Privacy: https://veltora-app.lovable.app/privacy

**Category:** Entertainment · **Contact email:** perfumwala@gmail.com · **Privacy policy:** https://veltora-app.lovable.app/privacy

**Graphics needed:**
- 512×512 icon
- 1024×500 feature graphic
- at least 2 phone screenshots (Home, Title page, Channels, My channels, Cloud)

## 4. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://veltora-app.lovable.app/privacy |
| App access | *All or some functionality is restricted* → add a **normal test account** (email + password; create one in the app, not the admin account) with the instruction "Sign in with email and password on the Profile tab." |
| Ads | **No**, the app doesn't contain ads. |
| Content rating | Fill in the IARC questionnaire honestly for your catalog. Say **Yes** to "users can share content with each other" (moderated: admin approval, reporting). No gambling, no purchases. |
| Target audience | **18 and over** (user-generated videos; keeps the app out of the Families program). |
| News app | No |
| Data safety | see below |
| Government app / Financial features / Health | No / None / No |
| Account deletion | In-app (Profile → Delete account) and web URL: https://veltora-app.lovable.app/delete-account |
| Child safety standards (for apps with user content) | https://veltora-app.lovable.app/child-safety · contact perfumwala@gmail.com |
| Advertising ID | **No**, the app doesn't use the advertising ID. |

### Data safety answers
General answers:
- Data collected: **Yes**
- Shared with third parties: **No**
- Encrypted in transit: **Yes**
- Users can request deletion: **Yes**

| Data type | Collected | Optional? | Purpose |
|---|---|---|---|
| Personal info → **Email address** | Yes | Required | Account management, App functionality |
| Photos and videos → **Photos**, **Videos** | Yes | Optional (creators / Cloud) | App functionality |
| Files and docs | Yes | Optional (Cloud Storage) | App functionality |
| App activity → **App interactions** (what is watched, view counts) | Yes | Required | Analytics, App functionality |
| App activity → **Other user-generated content** (channel names/descriptions, reports) | Yes | Optional | App functionality |
| Device or other IDs (random install ID made by the app) | Yes | Required | Analytics |

Not collected: location, contacts, financial info, messages, audio, health, web history. (Free app: no payment data. Turning paid plans on later means updating this form and using Google Play Billing or Play's alternative billing.)

## 5. Ads vs organic on Play

Users who install from Google Play keep the same flow as before:
- Meta install campaigns put `utm_source` (for example `apps.facebook.com`) in the Play install referrer. The app reads the referrer, and the server marks the install as **ads**.
- Everyone else is **organic**.
- What each group sees is decided by the channel and video audience you set in Admin → Creators.

`Veltora-ads.apk` (website download) stays on your website. Don't upload it to Play.

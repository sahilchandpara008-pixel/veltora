import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Your Supabase keys live in local.properties (never committed to git).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String) = (localProps.getProperty(name) ?: "").trim()

// The Streams Supabase project. The anon key is public by design (every APK contains it);
// all protection comes from the row-level security rules in supabase/schema.sql.
// local.properties values, when set, override these.
val supabaseUrl = prop("SUPABASE_URL").ifEmpty { "https://wyhexbcmidcclfqngvxz.supabase.co" }
val supabaseAnonKey = prop("SUPABASE_ANON_KEY").ifEmpty {
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Ind5aGV4YmNtaWRjY2xmcW5ndnh6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE1Mjg2MzEsImV4cCI6MjEwNzEwNDYzMX0.-CSbNfP9VrRb5u-z4mmxL-LmHpjUwdg1ujgBTSN9uck"
}

android {
    namespace = "com.streams.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veltora.app"
        minSdk = 26
        targetSdk = 36
        // Every GitHub build gets its own number, so a newer APK always installs as an update
        // and Profile shows exactly which build is on the phone.
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + buildNumber
        versionName = "1.1.$buildNumber"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        // Set only for the ads download (Streams-ads.apk, built with -PapkReferrer=...): APKs
        // installed from a link get no Google Play install referrer, so the file carries its source.
        val apkReferrer = (project.findProperty("apkReferrer") as String?).orEmpty().replace("\"", "")
        buildConfigField("String", "APK_REFERRER", "\"$apkReferrer\"")
    }

    // Fixed test-signing key (app/debug.keystore, standard Android debug passwords) so every
    // GitHub-built APK installs over the previous one instead of failing with "App not installed".
    // Not for Play Store releases — those need your own private upload key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Google Play upload key. CI writes the keystore from the repo secrets
        // (UPLOAD_KEYSTORE_BASE64 etc.); it's never stored in the repo.
        val uploadKeystore = System.getenv("UPLOAD_KEYSTORE")
        if (!uploadKeystore.isNullOrBlank()) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Android + Compose
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("com.android.installreferrer:installreferrer:2.2") // Google Play Install Referrer (ad attribution)
    implementation("com.facebook.android:facebook-core:18.2.3")         // Meta app events (IDs set in Admin → Settings)

    // Supabase (auth, database, file storage)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.6"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Video player + images
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}

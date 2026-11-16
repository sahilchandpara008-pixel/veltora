// Veltora Pay Listener: a separate small app for the owner's phone only (not for customers).
// It forwards "money received" notifications of UPI apps to the bank-sms function.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.veltora.paylistener"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veltora.paylistener"
        minSdk = 26
        targetSdk = 36
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Same Google Play upload key as the main app (CI writes it from the repo secrets).
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
            // Signed, non-debuggable build: the one published for download (Play Protect trusts it more).
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

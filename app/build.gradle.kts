plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Reads app/google-services.json, which names the Firebase project that delivers draft notifications.
    id("com.google.gms.google-services")
}

android {
    namespace = "com.brewprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.brewprint"
        minSdk = 26
        targetSdk = 35
        // The release workflow sets these from the run number and the tag, so each release installs over the last.
        versionCode = System.getenv("BREWPRINT_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("BREWPRINT_VERSION") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Public by design: Supabase row-level security protects the data, not these values.
        buildConfigField("String", "SUPABASE_URL", "\"https://ftwdmparhchrafsbmplv.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_GfVfBdnCKM-z6p7joMfvEw_KfOgUUTj\"")
        // The Web OAuth client from Google Cloud; Supabase checks Google's tokens against it.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"728373646004-3f5dv4dfkg5v3goivnufkcp1k5kvoqks.apps.googleusercontent.com\"")
    }
    // Release builds are signed in CI with the keystore from the repository's Actions secrets.
    val releaseKeystore = System.getenv("BREWPRINT_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("BREWPRINT_KEYSTORE_PASSWORD")
            keyAlias = "brewprint"
            keyPassword = System.getenv("BREWPRINT_KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // supabase-kt 3.1 is the newest line built with Kotlin 2.1, which this project uses.
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-okhttp:3.1.2")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // Firebase Cloud Messaging only: the server's "drafts changed" message. See docs/adr/0006.
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

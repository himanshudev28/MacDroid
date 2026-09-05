plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── Version, derived from the release tag ─────────────────────────────────
//
// `versionCode` used to be a hand-edited literal, which is a problem now that
// the app updates itself: the installer refuses an APK whose code is not
// greater than the installed one, so a forgotten bump silently breaks updates
// for everyone and looks like the update checker is broken.
//
// CI passes `-PversionTag=v1.2.3` from the git tag. Everything else — local
// builds, Android Studio — falls back to the literals below, so nothing about
// day-to-day development changes.
val fallbackVersionName = "2.0.0"
val fallbackVersionCode = 20000

val releaseTag: String? = (findProperty("versionTag") as String?)?.takeIf { it.isNotBlank() }

val resolvedVersionName: String = releaseTag?.removePrefix("v") ?: fallbackVersionName

/** `1.2.3` → `10203`. Minor and patch get two digits each, so 1.2.10 > 1.2.9. */
val resolvedVersionCode: Int = releaseTag?.let { tag ->
    val parts = tag.removePrefix("v").split('.')
    val major = parts.getOrNull(0)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
    major * 10000 + minor * 100 + patch
}?.takeIf { it > 0 } ?: fallbackVersionCode

android {
    namespace = "com.droiddock.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droiddock.app"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    // ── Release signing ──────────────────────────────────────────────────
    //
    // Not cosmetic: Android only lets an APK replace one signed by the SAME
    // certificate. CI used to publish `assembleDebug`, signed with whatever
    // throwaway debug keystore the runner generated that morning — a different
    // key every release, so an in-place update was never going to install.
    //
    // The keystore arrives from GitHub secrets (see .github/workflows/release.yml).
    // When it's absent — every local build — release falls back to the debug
    // key, so contributors can build and install without any secrets. Left to
    // itself Gradle would emit `app-release-unsigned.apk`, which installs
    // nowhere; the fallback is what makes a local release build useful.
    //
    // CI must never take that fallback silently, which is why the workflow
    // fails the job outright if the keystore secret is missing rather than
    // shipping another unupgradeable debug-signed APK.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    val hasKeystore = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (hasKeystore) "release" else "debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // Only for VERSION_NAME, in the Settings footer. It was hardcoded there
        // and had drifted three releases behind the manifest.
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Stubbed android.jar methods throw by default, which would fail a
            // test the moment it brushed a framework class it never actually
            // uses. The logic under test here is plain Kotlin.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // FileProvider, for clipboard images (ClipImage.kt). It was already on the
    // classpath transitively through the Compose/lifecycle artifacts, but a
    // compile-time API resolved by accident is one dependency bump away from
    // disappearing — declared here so it can't. Gradle resolves to the highest
    // requested version, so naming a floor cannot downgrade anything.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation("junit:junit:4.13.2")
    // A real org.json on the unit-test classpath.
    //
    // `isReturnDefaultValues` above turns android.jar's stubbed methods into
    // null/0 rather than exceptions, which is right for framework classes the
    // logic never touches — but org.json is not one of those. UpdateChecker
    // parses GitHub's release JSON with it, and against the stub every parse
    // silently returns null and the test NPEs on the next line.
    //
    // This is Android's own JSON implementation republished under Apache-2.0
    // (the same artifact Robolectric uses); `org.json:json` itself carries the
    // non-OSI "not for Evil" clause and is deliberately not used.
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
}

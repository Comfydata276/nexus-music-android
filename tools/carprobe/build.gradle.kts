// The car probe: a small app that connects to another app's media service the way Android
// Auto does and checks what it finds. See src/main/kotlin/.../Probe.kt and docs/TESTING.md.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.medianexus.carprobe"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.medianexus.carprobe"
        minSdk = 28
        // Android 14 on purpose. From Android 15 an app in the background may not ask for
        // audio focus, and the probe has to, to stand in for a navigation prompt.
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // The support library Android Auto itself is built on: the same MediaBrowserCompat and
    // MediaControllerCompat, speaking the same protocol to the app under test.
    implementation("androidx.media:media:1.7.1")
    // For `--mode outsider` only: a Media3 controller, to see what one that isn't the app's
    // own is allowed, and Media3's own way of writing a media item into an intent.
    implementation(libs.media3.session)
}

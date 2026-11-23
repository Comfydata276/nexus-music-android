// FIXTURE. A stand-in media app that exists only so the test rig can be proved to work
// before the real app has a playback service. It is never part of the app, never shipped.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.medianexus.standin"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.medianexus.standin"
        minSdk = 28
        targetSdk = 37
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
    // The same Media3 the app is built on (the versions come from the app's catalogue),
    // so what the rig learns about the stand-in holds for the app's service too.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}

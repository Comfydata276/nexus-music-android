import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.medianexus.music"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.medianexus.music"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"
    }

    signingConfigs {
        val handed = file("/signing/signing.properties")
        if (handed.isFile) {
            val props = Properties().apply { handed.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The project's own key when it has been handed to the build (scripts/dx does so
            // only when asked, with NEXUS_SIGN=1); the debug key otherwise, so that anyone
            // can build and the test rig can install over its own builds. A phone only takes
            // an update signed with the key the installed app was signed with.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    testOptions {
        // The JVM tests cover code that doesn't touch Android; this keeps a stray call into
        // the framework (a log line, say) from failing them.
        unitTests.isReturnDefaultValues = true
        // The tests that are about Media3's own behaviour (its cache, its media sources, its
        // player) run its real classes on Robolectric's copy of Android, with the app's
        // manifest and resources.
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    // Already brought in by the two above; named because the app uses them directly (the
    // player's data sources, and the kept songs' index).
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.coil.compose)
    // The app's own back stack: Navigation 3 draws it and runs predictive back.
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    // Test only, never in the app: Android itself on the JVM, and Media3's own test kit (a
    // clock the player can be run against, stand-in decoders), so that what Media3 really
    // does with our data source and queue is what is tested, not a guess at it.
    testImplementation(libs.robolectric)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
}

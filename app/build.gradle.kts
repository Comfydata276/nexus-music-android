import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Whether jams and linked devices (M6: `jam/`, `ui/jam/`) exist in this build. **They do not.**
 * A security review found that the way linked devices prove they are the person's own leaks a
 * guessable fingerprint of the music-server password to the relay, and ways a guest or
 * another app can interfere with playback; none of it goes to a phone until that is fixed.
 * This is the one switch: it gives the app `BuildConfig.JAMS` (read through
 * `jam/JamBuild.kt`), and decides whether `src/jam/AndroidManifest.xml` (the activity that
 * takes a jam link from other apps, `JamService`, and the permission it needs) is merged into
 * the APK at all, so that with it off the phone offers other apps nothing for it.
 */
val jamsExist = false

/**
 * Whether linking the person's devices (`link/`, `playback/Linking`, `playback/LinkSide`:
 * the scheme of the Windows repository's `docs/linking-design.md`) exists in this build.
 * **It does not**, in any build that is released. It gives the app `BuildConfig.LINK`
 * (read through `link/LinkBuild.kt`); with it off nothing of linking is made, shown or
 * asked for. A build for trying it out is this same source built with
 * `-PlinkExists=true`, and is the same app otherwise.
 */
val linkExists = providers.gradleProperty("linkExists").orNull == "true"

/**
 * The shared core: the Windows app's own code for what every app on an account must do
 * identically, compiled into a library the app carries (`native/`, docs/DESIGN.md "The
 * shared core"). **Every build of the app builds it first, and none can leave it out**:
 *
 * - `buildCore` runs `native/build`, which compiles the library for the two kinds of device
 *   and for this machine (the unit tests load that one). Gradle runs it again whenever
 *   anything it is made from has changed, and the build fails if it fails.
 * - What it makes is the app's `jniLibs`, and the tests' library path.
 * - `assemble` then opens the APK it made and fails unless the library is in it for both.
 */
val coreAbis = listOf("arm64-v8a", "x86_64")
val coreOut = layout.buildDirectory.dir("core")
val coreLocal = providers.environmentVariable("NEXUS_CORE_LOCAL").orElse("")
val buildCore = tasks.register<Exec>("buildCore") {
    description = "Builds the shared core (native/) for the phone, the emulator and this machine."
    val crate = rootProject.layout.projectDirectory.dir("native")
    inputs.files(crate.file("Cargo.toml"), crate.file("Cargo.lock"), crate.file("build"))
    // (Which Rust it is built with is the image's: a change there builds it again.)
    inputs.file(rootProject.layout.projectDirectory.file("docker/build.Dockerfile"))
    inputs.dir(crate.dir("src"))
    // (Working on the core and the phone at once: the core as it lies, see native/build.)
    inputs.property("local", coreLocal)
    if (coreLocal.get().isNotEmpty()) inputs.dir(coreLocal.get())
    outputs.dir(coreOut.map { it.dir("jniLibs") })
    outputs.dir(coreOut.map { it.dir("host") })
    commandLine(crate.file("build").asFile.path, coreOut.get().asFile.path)
}

android {
    namespace = "org.medianexus.music"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "org.medianexus.music"
        minSdk = 31
        targetSdk = 37
        // A trial build can be given its own version from the command line
        // (-PversionCode=12 -PversionName=0.6.2-link1); without them, the app's own.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 11
        versionName = providers.gradleProperty("versionName").orNull ?: "0.6.2"
        buildConfigField("boolean", "JAMS", jamsExist.toString())
        buildConfigField("boolean", "LINK", linkExists.toString())
        // The app carries a library of its own, compiled for these two kinds of processor
        // and no other (see "The shared core", below): an arm64 phone, and the x86_64
        // emulators. Naming them here leaves every other kind out of the APK altogether,
        // so that a 32-bit phone is told the app is not for it when it tries to install,
        // instead of installing an app that cannot load its library.
        //
        // The x86_64 library is in the release APK too, though no phone uses it (751 KB of
        // it, uncompressed): so that the build that ships is the very build that was car
        // checked and measured on the emulators, not one made differently for release.
        ndk {
            abiFilters += coreAbis
        }
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

// (See jamsExist, above.)
androidComponents {
    onVariants { variant ->
        if (jamsExist) variant.sources.manifests.addStaticManifestFile("src/jam/AndroidManifest.xml")
    }
}

// A build against a core that isn't the pinned commit is for working on the two at once: it
// is never the one that ships. Refused before anything of a release is put together,
// whichever task was asked for (an APK, a bundle, or only the packaging of one).
val refuseLocalCoreInARelease = tasks.register("refuseLocalCoreInARelease") {
    val local = coreLocal
    doLast {
        if (local.get().isNotEmpty()) throw GradleException("NEXUS_CORE_LOCAL is set: a release build is made from the core at its pinned commit, never from a copy as it lies. Unset it.")
    }
}
buildCore.configure { mustRunAfter(refuseLocalCoreInARelease) }

// The shared core, into every build (see buildCore, above).
android.sourceSets.getByName("main").jniLibs.directories.add(coreOut.get().dir("jniLibs").asFile.path)
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(buildCore)
    if (Regex("(merge|strip|package|bundle|sign|assemble)[A-Za-z]*Release[A-Za-z]*").matches(name)) dependsOn(refuseLocalCoreInARelease)
}
tasks.withType<Test>().configureEach {
    dependsOn(buildCore)
    // The library the tests load is what they test: a change to it (native/src, or the
    // commit of the Windows repository it is pinned to) runs them again. Handed over only
    // as a path, it was no input of theirs, and after such a change Gradle said the tests
    // were up to date, or took their last results from its cache.
    inputs.dir(coreOut.map { it.dir("host") }).withPropertyName("sharedCoreForThisMachine").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("java.library.path", coreOut.get().dir("host").asFile.path)
}
androidComponents {
    onVariants { variant ->
        val named = variant.name.replaceFirstChar { it.uppercase() }
        val apks = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
        val abis = coreAbis
        val release = variant.buildType == "release"
        val local = coreLocal
        val carries = tasks.register("check${named}CarriesCore") {
            description = "Fails unless the APK just built has the shared core in it."
            inputs.dir(apks)
            doLast {
                // A build against a core that isn't the pinned commit is for working on
                // the two at once: it is never the one that ships.
                if (release && local.get().isNotEmpty()) throw GradleException("NEXUS_CORE_LOCAL is set: a release build is made from the core at its pinned commit, never from a copy as it lies. Unset it.")
                val built = apks.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty()
                if (built.isEmpty()) throw GradleException("No APK was built in ${apks.get().asFile}.")
                for (apk in built) {
                    ZipFile(apk).use { zip ->
                        for (abi in abis) {
                            val entry = zip.getEntry("lib/$abi/libnexus_core_android.so")
                            if (entry == null || entry.size < 100_000) {
                                throw GradleException("${apk.name} does not carry the shared core for $abi. It must: the app cannot run without it. (native/build makes it; see app/build.gradle.kts.)")
                            }
                        }
                        val others = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.map { it.split('/')[1] }.toSet() - abis.toSet()
                        if (others.isNotEmpty()) throw GradleException("${apk.name} holds libraries for $others, where the shared core is not built: such a device would install an app it cannot run.")
                    }
                }
            }
        }
        tasks.configureEach { if (name == "assemble$named") dependsOn(carries) }
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

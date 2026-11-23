// The test rig's own tools, built apart from the app so that nothing here can ever end up
// inside it:  scripts/dx ./gradlew --no-daemon -p tools assembleDebug
// (the rig's scripts run that for you when an APK is missing or out of date).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // The app's own list of versions, so the stand-in is built on exactly the Media3 the
    // app uses and the two cannot drift apart.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "nexus-music-android-tools"
include(":carprobe", ":standin")

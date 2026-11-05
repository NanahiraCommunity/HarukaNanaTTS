pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "nana-tts"

include("core", "bankc", "cli", "wasi")

// The Android TTS engine app needs an Android SDK, so it is only part of the build when one is configured.
val localSdk = file("local.properties").takeIf { it.isFile }?.let { f ->
    java.util.Properties().apply { f.reader().use { load(it) } }.getProperty("sdk.dir")
}
if (localSdk != null || System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null) {
    include("android")
}

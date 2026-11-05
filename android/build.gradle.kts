// Android TTS engine app for the 春歌ナナ voice: see README.md in this folder.
plugins {
    // AGP 9 has built-in Kotlin
    id("com.android.application")
}

repositories {
    google()
    mavenCentral()
}

/** Copies the compiled voice pack into the app's generated assets. */
abstract class CopyVoicePack : DefaultTask() {
    @get:InputFile
    abstract val voicePack: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        voicePack.get().asFile.copyTo(dir.resolve("nana.vpk"))
    }
}

val copyVoicePack = tasks.register<CopyVoicePack>("copyVoicePack") {
    dependsOn(":bankc:compileVoice")
    voicePack.set(rootProject.file("bankc/build/voice/nana.vpk"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyVoicePack, CopyVoicePack::outputDir)
    }
}

android {
    namespace = "dev.nanatts.android"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.nanatts.android"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // R8 for every build type. It was introduced because core used Java records, and incremental D8
        // dexing dropped their RecordTag synthetic; core is Kotlin now, so this could be revisited.
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // nana.vpk is gzip-compressed already
        noCompress += "vpk"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
}

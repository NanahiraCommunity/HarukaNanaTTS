plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "dev.nanatts.bankc.VoiceCompiler"
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}

val voicePack = layout.buildDirectory.file("voice/nana.vpk")

/** Compiles the UTAU voicebank in data/ into the compact voice pack used at runtime. */
val compileVoice by tasks.registering(JavaExec::class) {
    description = "Analyses data/ and writes build/voice/nana.vpk"
    group = "build"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.nanatts.bankc.VoiceCompiler"
    maxHeapSize = "3g"
    inputs.dir(rootProject.file("data"))
    inputs.files(sourceSets["main"].runtimeClasspath)
    outputs.file(voicePack)
    args(rootProject.file("data").absolutePath, voicePack.get().asFile.absolutePath)
}

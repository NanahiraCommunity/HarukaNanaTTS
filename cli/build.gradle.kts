plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "dev.nanatts.cli.Say"
}

tasks.named<JavaExec>("run") {
    dependsOn(":bankc:compileVoice")
    workingDir = rootProject.projectDir
    systemProperty("nanatts.pack", project(":bankc").layout.buildDirectory.file("voice/nana.vpk").get().asFile.absolutePath)
}

/** Renders the style comparison set to samples/tuning/ (one folder per variant). */
val audition by tasks.registering(JavaExec::class) {
    description = "Renders test sentences in several speaking styles to samples/tuning"
    group = "application"
    dependsOn(":bankc:compileVoice")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.nanatts.cli.Audition"
    workingDir = rootProject.projectDir
    systemProperty("nanatts.pack", project(":bankc").layout.buildDirectory.file("voice/nana.vpk").get().asFile.absolutePath)
    args(rootProject.file("samples/tuning").absolutePath)
}

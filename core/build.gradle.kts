import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }
}

val jvmMainCompilation = kotlin.jvm().compilations["main"]
val jvmTestCompilation = kotlin.jvm().compilations["test"]

// Tests are a dependency-free self-test runner (no JUnit) on the JVM.
val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the self-test suite (also verifies Android-safety of the compiled classes)."
    group = "verification"
    dependsOn(jvmTestCompilation.compileTaskProvider, tasks.named("jvmTestProcessResources"), ":bankc:compileVoice")
    classpath = files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
    mainClass = "dev.nanatts.SelfTests"
    systemProperty("nanatts.dataDir", rootProject.file("data").absolutePath)
    systemProperty("nanatts.classesDir", jvmMainCompilation.output.classesDirs.asPath)
    systemProperty("nanatts.pack", rootProject.file("bankc/build/voice/nana.vpk").absolutePath)
    systemProperty("nanatts.only", providers.gradleProperty("only").getOrElse(""))
    systemProperty("nanatts.outDir", layout.buildDirectory.dir("selftest").get().asFile.absolutePath)
}

tasks.named<Test>("jvmTest") {
    failOnNoDiscoveredTests = false
    dependsOn(selfTest)
}

/**
 * Regenerates the Unicode normalisation tables from the JDK running the build. The generator is a single-file
 * Java program, so it runs without compiling (and thus without needing) the tables it produces.
 */
val generateUnicodeData by tasks.registering(Exec::class) {
    description = "Writes src/commonMain/kotlin/dev/nanatts/text/UnicodeData.kt from java.text.Normalizer"
    group = "build"
    commandLine(
        "java",
        "--add-exports", "java.base/jdk.internal.icu.text=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.icu.lang=ALL-UNNAMED",
        file("tools/GenerateUnicodeData.java").absolutePath,
        file("src/commonMain/kotlin/dev/nanatts/text/UnicodeData.kt").absolutePath,
    )
}

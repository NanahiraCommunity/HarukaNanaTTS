import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        wasmWasiMain.dependencies {
            implementation(project(":core"))
        }
    }
}

val dist = layout.buildDirectory.dir("dist")

/**
 * The standalone WebAssembly (WASI) build in one folder: module, voice pack and dictionary.
 * Run with: wasmtime run --dir build/dist::/ build/dist/nana-tts.wasm -o /out.wav "Hello!"
 */
val assembleWasi by tasks.registering(Copy::class) {
    description = "Builds the WASI module and collects it with the voice pack and dictionary into build/dist"
    group = "build"
    dependsOn("compileProductionExecutableKotlinWasmWasiOptimize", ":bankc:compileVoice")
    from(layout.buildDirectory.file("compileSync/wasmWasi/main/productionExecutable/optimized/nana-tts-wasi.wasm")) {
        rename { "nana-tts.wasm" }
    }
    from(project(":bankc").layout.buildDirectory.file("voice/nana.vpk"))
    from(project(":core").file("src/jvmMain/resources/dev/nanatts/g2p")) {
        include("cmudict.tsv.gz", "CMUDICT-LICENSE.txt")
    }
    into(dist)
}

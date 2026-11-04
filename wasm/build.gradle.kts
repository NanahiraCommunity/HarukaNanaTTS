plugins {
    java
    id("org.teavm") version "0.16.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(teavm.libs.jsoApis)
}

tasks.named<JavaCompile>("compileJava") {
    // TeaVM reads @JSExport from the bytecode; its jar's annotation processor is not needed (and warns under -Werror)
    options.compilerArgs.add("-proc:none")
}

teavm {
    wasmGC {
        mainClass = "dev.nanatts.wasm.NanaTts"
        targetFileName = "nana-tts.wasm"
        modularRuntime = true // ES module runtime, loadable from Node
        copyRuntime = true
        obfuscated = true
    }
}

val dist = layout.buildDirectory.dir("dist")

/** Everything Node needs in one folder: wasm module, TeaVM runtime, loader script, voice pack, dictionary. */
val assembleNode by tasks.registering(Copy::class) {
    description = "Builds the WebAssembly module and collects it with the Node loader into build/dist"
    group = "build"
    dependsOn(tasks.named("buildWasmGC"), ":bankc:compileVoice")
    from(layout.buildDirectory.dir("generated/teavm/wasm-gc"))
    from("node")
    from(project(":bankc").layout.buildDirectory.file("voice/nana.vpk"))
    from(rootProject.file("core/src/main/resources/dev/nanatts/g2p")) {
        include("cmudict.tsv.gz", "CMUDICT-LICENSE.txt")
    }
    into(dist)
}

plugins {
    `java-library`
}

tasks.named<JavaCompile>("compileJava") {
    // plain StringBuilder concatenation instead of invokedynamic: no desugaring needed on Android
    options.compilerArgs.add("-XDstringConcat=inline")
}

// Tests are a dependency-free self-test runner (no JUnit), so the build works fully offline.
val selfTest by tasks.registering(JavaExec::class) {
    description = "Runs the self-test suite (also verifies Android-safety of the compiled classes)."
    group = "verification"
    dependsOn(tasks.named("classes"), tasks.named("testClasses"), ":bankc:compileVoice")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.nanatts.SelfTests"
    systemProperty("nanatts.dataDir", rootProject.file("data").absolutePath)
    systemProperty("nanatts.classesDir", sourceSets["main"].output.classesDirs.asPath)
    systemProperty("nanatts.pack", rootProject.file("bankc/build/voice/nana.vpk").absolutePath)
    systemProperty("nanatts.only", providers.gradleProperty("only").getOrElse(""))
    systemProperty("nanatts.outDir", layout.buildDirectory.dir("selftest").get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    failOnNoDiscoveredTests = false
    dependsOn(selfTest)
}

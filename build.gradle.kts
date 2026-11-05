import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    // declared here so AGP and the Kotlin plugin share a class loader (AGP 9's built-in Kotlin uses it)
    id("com.android.application") version "9.5.0" apply false
}

subprojects {
    group = "dev.nanatts"
    version = "0.1.0"

    // :android's Kotlin is configured by AGP
    if (name != "android") {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
            compilerOptions {
                allWarningsAsErrors = true
                if (this is KotlinJvmCompilerOptions) {
                    // Java 17 bytecode and API: consumable by Android Gradle Plugin 8.x+
                    jvmTarget = JvmTarget.JVM_17
                    freeCompilerArgs.addAll("-Xjdk-release=17", "-Xstring-concat=inline")
                }
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release = 17
        }
    }
}

subprojects {
    group = "dev.nanatts"
    version = "0.1.0"

    tasks.withType<JavaCompile>().configureEach {
        // Java 17 bytecode: consumable by Android Gradle Plugin 8.x (D8 desugars records etc.)
        options.release = 17
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-options", "-Werror"))
    }
}

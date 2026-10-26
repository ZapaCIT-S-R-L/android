// Used only by scripts/update-verification-metadata.sh: resolves aapt2 for every build platform (Linux CI, macOS,
// Windows) so gradle/verification-metadata.xml holds all their checksums, not just the current machine's.
gradle.rootProject {
    afterEvaluate {
        val agp =
            file("gradle/libs.versions.toml").readLines()
                .first { it.startsWith("agp = ") }.substringAfter('"').substringBefore('"')
        val props = java.util.Properties()
        buildscript.classLoader
            .getResourceAsStream("com/android/build/gradle/internal/res/aapt2_version.properties")!!
            .use { props.load(it) }
        val coordinates = listOf("linux", "osx", "windows").map {
            "com.android.tools.build:aapt2:$agp-${props.getProperty("aapt2Version")}:$it"
        }
        val config = configurations.detachedConfiguration(*coordinates.map { dependencies.create(it) }.toTypedArray())
        tasks.register("resolveAapt2AllPlatforms") { doLast { config.resolve() } }
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // libsignal is published only to Signal's own repository. Exclusive: org.signal comes from nowhere else,
        // and that repository serves nothing else.
        exclusiveContent {
            forRepository { maven("https://build-artifacts.signal.org/libraries/maven/") }
            filter { includeGroup("org.signal") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ZapaKid"
include(":app")

// Security floors for transitive build-tool dependencies (AGP, lint, detekt, ktlint), raised when OSV-Scanner reports
// a high or critical advisory and the tool hasn't caught up yet. A floor only ever raises a version. Remove an entry
// once the tools ship the fixed version themselves.
val securityFloors =
    mapOf(
        "com.fasterxml.jackson.core:jackson-core" to "2.22.4", // GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89
        "com.fasterxml.jackson.core:jackson-databind" to "2.22.4", // GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54
        "org.bouncycastle:bcprov-jdk18on" to "1.86", // GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26
        "org.bouncycastle:bcpkix-jdk18on" to "1.86", // kept in line with bcprov
        "org.bouncycastle:bcutil-jdk18on" to "1.86", // kept in line with bcprov
        "org.bitbucket.b_c:jose4j" to "0.9.7", // GHSA-3677-xxcr-wjqv
        "org.jdom:jdom2" to "2.0.6.1", // GHSA-2363-cqg2-863c
    )

fun isOlder(
    version: String,
    floor: String,
): Boolean {
    val a = version.split('.', '-').map { it.toIntOrNull() ?: 0 }
    val b = floor.split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

gradle.allprojects {
    val raise: (DependencyResolveDetails) -> Unit = { details ->
        val floor = securityFloors["${details.requested.group}:${details.requested.name}"]
        val version = details.requested.version
        if (floor != null && version != null && isOlder(version, floor)) {
            details.useVersion(floor)
            details.because("security floor (settings.gradle.kts)")
        }
    }
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency(raise) }
    configurations.configureEach { resolutionStrategy.eachDependency(raise) }
}

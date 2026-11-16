// The PWA's data pump: a scheduled GitHub Actions job runs it to read the site's public JSON for a
// fixed list of groups and publish static JSON for the PWA (the site sends no CORS headers, so a
// browser cannot read it). It reuses :core — parser, merge, refresh policy, standings — so the
// battle-tested logic is not written twice. See .github/workflows/pwa-data.yml.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.jdcm.pump.MainKt")
    applicationName = "pump"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The real answers of the site that :core is tested against are the pump's fixtures too.
sourceSets {
    test {
        resources.srcDir("../core/src/test/resources")
    }
}

tasks.test {
    useJUnitPlatform()
}

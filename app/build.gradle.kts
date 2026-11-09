import java.util.Properties

/**
 * Release notes (`release-notes/<versionName>.json`, `{"es","en"}`) bundled as one asset,
 * `changelog.json` = {"<versionName>": {…}}, for «Novedades». Each file is copied as it is; the
 * app reads them one by one, so one that does not parse costs only itself (znhr's recipe).
 */
abstract class ChangelogAsset : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notes: DirectoryProperty

    @get:OutputDirectory
    abstract val out: DirectoryProperty

    @TaskAction
    fun write() {
        val files = notes.get().asFile.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        val body = files.joinToString(",\n") { f -> "\"" + f.name.removeSuffix(".json") + "\": " + f.readText(Charsets.UTF_8).trim() }
        val dir = out.get().asFile
        dir.mkdirs()
        File(dir, "changelog.json").writeText("{\n$body\n}\n", Charsets.UTF_8)
    }
}

// Kotlin is compiled by AGP's built-in support (AGP 9+): no kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: env vars (CI) or ~/.jdcm-signing/keystore.properties (dev machine).
// The key never lives in the repo; losing it breaks the in-place update chain.
val signingProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".jdcm-signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(prop)

fun git(vararg args: String): String = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("")

android {
    namespace = "dev.jdcm"
    // AndroidX from mid-2026 (core 1.19, lifecycle 2.11, Compose 1.12) is compiled against API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.jdcm"
        minSdk = 29
        targetSdk = 36
        // release.yml reads these two lines out of this file: keep exactly one of each, and never
        // write either name followed by an equals sign anywhere else in here.
        versionCode = 3
        versionName = "0.2.1-alpha"

        val sha = git("rev-parse", "--short=10", "HEAD").ifEmpty { "unknown" }
        val dirty = if (git("status", "--porcelain").isNotEmpty()) "+dirty" else ""
        buildConfigField("String", "GIT_SHA", "\"$sha$dirty\"")
        // The commit's date, not the build time: reproducible builds, valid configuration cache.
        buildConfigField("String", "GIT_DATE", "\"${git("show", "-s", "--format=%cs", "HEAD").ifEmpty { "-" }}\"")
    }

    signingConfigs {
        create("release") {
            signingValue("RELEASE_STORE_FILE", "storeFile")?.let { storeFile = file(it) }
            storePassword = signingValue("RELEASE_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("RELEASE_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("RELEASE_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            // Shrunk, not obfuscated (proguard-rules.pro): the diagnostic log's stack traces stay readable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Coexists with the real app and never self-updates (its package is not the channel's).
            applicationIdSuffix = ".debug"
        }
    }

    androidResources {
        localeFilters += listOf("en", "es")
    }

    packaging {
        resources.excludes += listOf("DebugProbesKt.bin", "kotlin/**", "META-INF/*.version", "META-INF/version-control-info.textproto")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isReturnDefaultValues = true
    }
}

val changelogAsset = tasks.register<ChangelogAsset>("changelogAsset") {
    notes.set(rootProject.layout.projectDirectory.dir("release-notes"))
}
androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(changelogAsset, ChangelogAsset::out) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    // Crests: the schools' emblems come from the site; Coil caches them on disk.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

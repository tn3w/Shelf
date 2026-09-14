import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val catalogueRelease = "db-2026-09"
val bundledPacks = listOf("core", "ranks")

android {
    namespace = "dev.tn3w.shelf"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.tn3w.shelf"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("fdroid") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += "bin"
        generateLocaleConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.all {
            val directory = layout.buildDirectory.dir("testCatalogue").get().asFile
            it.systemProperty("catalogue", directory.absolutePath)
            it.dependsOn("downloadTestCatalogue")
        }
    }
}

abstract class DownloadCatalogue : DefaultTask() {
    @get:Input
    abstract val release: Property<String>

    @get:Input
    abstract val filter: ListProperty<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun download() {
        val base = "https://github.com/tn3w/Shelf/releases/download/${release.get()}"
        val manifest = URI("$base/manifest.json").toURL().readText()
        val entries = Regex("\"id\": \"([^\"]+)\"[^}]*?\"sha256\": \"([0-9a-f]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (id, _) -> filter.get().any { Regex(it).matches(id) } }
        val directory = output.get().asFile
        for ((id, sha256) in entries) {
            val target = directory.resolve("$id.bin")
            if (target.exists() && digest(target.readBytes()) == sha256) continue
            val bytes = URI("$base/$id.bin").toURL().readBytes()
            check(digest(bytes) == sha256) { "checksum mismatch for $id" }
            target.writeBytes(bytes)
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

val downloadCatalogue = tasks.register<DownloadCatalogue>("downloadCatalogue") {
    release = catalogueRelease
    filter = bundledPacks.map { "[a-z]{2}-$it-.*" }
    output = layout.buildDirectory.dir("generated/catalogue")
}

tasks.register<DownloadCatalogue>("downloadTestCatalogue") {
    release = catalogueRelease
    filter = listOf("en-.*", "de-.*")
    output = layout.buildDirectory.dir("testCatalogue")
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        downloadCatalogue,
        DownloadCatalogue::output,
    )
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation.suite)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.jsoup)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
}

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val catalogueRelease = "catalogue-2026-09-18"
val catalogueFormat = 2
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
            buildConfigField("boolean", "BUNDLED_CATALOGUE", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "BUNDLED_CATALOGUE", "false")
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
}

abstract class DownloadCatalogue : DefaultTask() {
    @get:Input
    abstract val release: Property<String>

    @get:Input
    abstract val format: Property<Int>

    @get:Input
    abstract val filter: ListProperty<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun download() {
        val base = "https://github.com/tn3w/Shelf/releases/download/${release.get()}"
        val manifest = URI("$base/manifest.json").toURL().readText()
        val found = Regex("\"format\": (\\d+)").find(manifest)?.groupValues?.get(1)
        check(found == format.get().toString()) {
            "catalogue format $found is not supported"
        }
        val directory = output.get().asFile
        directory.resolve("manifest.json").writeText(manifest)
        val entryHead = "\"id\": \"([^\"]+)\"[^}]*?"
        val entryTail = "\"sha256\": \"([0-9a-f]+)\"[^}]*?\"url\": \"([^\"]+)\""
        val entries = Regex(entryHead + entryTail)
            .findAll(manifest)
            .map { it.destructured }
            .filter { (id) -> filter.get().any { Regex(it).matches(id) } }
        for ((id, sha256, url) in entries) {
            val target = directory.resolve("$id.bin")
            if (target.exists() && digest(target.readBytes()) == sha256) continue
            val bytes = URI(url).toURL().readBytes()
            check(digest(bytes) == sha256) { "checksum mismatch for $id" }
            target.writeBytes(bytes)
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

fun bundleCatalogue(flavor: String, taskName: String, packs: List<String>) {
    val task = tasks.register<DownloadCatalogue>(taskName) {
        release = catalogueRelease
        format = catalogueFormat
        filter = packs.map { "[a-z]{2}-$it-.*" }
        output = layout.buildDirectory.dir("generated/$taskName")
    }
    androidComponents.onVariants(
        androidComponents.selector().withFlavor("distribution" to flavor)
    ) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            task,
            DownloadCatalogue::output,
        )
    }
}

bundleCatalogue("github", "downloadCatalogue", bundledPacks)
bundleCatalogue("fdroid", "downloadManifest", emptyList())

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
}

import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val catalogueRelease = "catalogue-2026-10-17"
val catalogueManifestSha256 =
    "2ed459dcc1d45cb2ea33eaa7fcea0cf3374802f281e81bc3c113a6e19a3edefc"
val catalogueFormat = 2
val bundledLanguage = "en"
val bundledPacks = listOf("core", "ranks")
val localCatalogue = providers.gradleProperty("localCatalogue")

data class Distribution(
    val catalogue: Boolean,
    val scanModel: Boolean,
    val updater: Boolean,
    val transferService: Boolean = updater,
)

val distributions = mapOf(
    "github" to Distribution(catalogue = true, scanModel = true, updater = true),
    "update" to Distribution(catalogue = false, scanModel = false, updater = true),
    "fdroid" to Distribution(catalogue = false, scanModel = true, updater = false),
)

android {
    namespace = "dev.tn3w.shelf"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.tn3w.shelf"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "1.4.1"
        buildConfigField("int", "CATALOGUE_FORMAT", "$catalogueFormat")
    }

    flavorDimensions += "distribution"
    productFlavors {
        distributions.forEach { (name, distribution) ->
            create(name) {
                dimension = "distribution"
                buildConfigField("boolean", "BUNDLED_CATALOGUE", "${distribution.catalogue}")
                buildConfigField("boolean", "BUNDLED_SCAN_MODEL", "${distribution.scanModel}")
                buildConfigField("boolean", "UPDATER", "${distribution.updater}")
                buildConfigField(
                    "boolean", "TRANSFER_SERVICE", "${distribution.transferService}",
                )
            }
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

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += "org/apache/commons/codec/**"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warning += "MissingTranslation"
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
    abstract val manifestSha256: Property<String>

    @get:Input
    abstract val format: Property<Int>

    @get:Input
    abstract val filter: ListProperty<String>

    @get:Input
    @get:Optional
    abstract val local: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun download() {
        val base = "https://github.com/tn3w/Shelf/releases/download/${release.get()}"
        val manifestBytes = fetch("$base/manifest.json")
        check(local.isPresent || digest(manifestBytes) == manifestSha256.get()) {
            "manifest checksum mismatch"
        }
        val manifest = JsonSlurper().parse(manifestBytes) as Map<*, *>
        check(manifest["format"] == format.get()) {
            "catalogue format ${manifest["format"]} is not supported"
        }
        val directory = output.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve("manifest.json").writeBytes(manifestBytes)
        val segments = (manifest["segments"] as List<*>).map { it as Map<*, *> }
        for (segment in segments.filter { wanted(it["id"] as String) }) {
            val id = segment["id"]
            val bytes = fetch(segment["url"] as String)
            check(digest(bytes) == segment["sha256"]) { "checksum mismatch for $id" }
            directory.resolve("$id.bin").writeBytes(bytes)
        }
    }

    private fun wanted(id: String) = filter.get().any { Regex(it).matches(id) }

    private fun fetch(url: String): ByteArray {
        val directory = local.orNull ?: return URI(url).toURL().readBytes()
        return File(directory, url.substringAfterLast('/')).readBytes()
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

fun catalogueTask(taskName: String, packs: List<String>) =
    tasks.register<DownloadCatalogue>(taskName) {
        release = catalogueRelease
        manifestSha256 = catalogueManifestSha256
        format = catalogueFormat
        filter = packs.map { "$bundledLanguage-$it-.*" }
        local = localCatalogue
        output = layout.buildDirectory.dir("generated/$taskName")
    }

val catalogueAssets = catalogueTask("downloadCatalogue", bundledPacks)
val manifestAssets = catalogueTask("downloadManifest", emptyList())

androidComponents.onVariants { variant ->
    val distribution = distributions.getValue(variant.flavorName.orEmpty())
    val task = if (distribution.catalogue) catalogueAssets else manifestAssets
    variant.sources.assets?.addGeneratedSourceDirectory(task, DownloadCatalogue::output)
    if (!distribution.scanModel) {
        variant.androidResources.ignoreAssetsPatterns.add("scan.bin")
    }
}

tasks.register<JavaExec>("scanBench") {
    val unitTest = tasks.named<Test>("testGithubDebugUnitTest")
    dependsOn("downloadCatalogue", unitTest.map { it.taskDependencies })
    classpath = files(unitTest.map { it.classpath })
    mainClass = "dev.tn3w.shelf.data.ScanBenchKt"
    workingDir = rootDir.parentFile
    maxHeapSize = "6g"
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation.suite)
    implementation(libs.compose.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.appcompat)
    implementation(libs.splashscreen)
    implementation(libs.datastore)
    implementation(libs.datastore.preferences)
    implementation(libs.collection)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.jsoup)
    implementation(libs.zxing.core)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
}

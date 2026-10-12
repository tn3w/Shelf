import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val catalogueRelease = "catalogue-2026-10-10"
val catalogueManifestSha256 =
    "2ca03c17a7fced3d013862d2d31c196debde894f0d54242e559fd239be6bb4ea"
val catalogueFormat = 2
val bundledPacks = listOf("core", "ranks")
val localCatalogue = providers.gradleProperty("localCatalogue")

android {
    namespace = "dev.tn3w.shelf"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.tn3w.shelf"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "1.3.1"
        buildConfigField("int", "CATALOGUE_FORMAT", "$catalogueFormat")
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

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

fun bundleCatalogue(flavor: String, taskName: String, packs: List<String>) {
    val task = tasks.register<DownloadCatalogue>(taskName) {
        release = catalogueRelease
        manifestSha256 = catalogueManifestSha256
        format = catalogueFormat
        filter = packs.map { "[a-z]{2}-$it-.*" }
        local = localCatalogue
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
    implementation(libs.datastore.preferences)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.jsoup)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
}

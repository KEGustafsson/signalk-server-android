import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("com.android.library")
}

// The Node runtime as an Android library (docs/PLAN.md §4): nodejs-mobile's libnode.so for each
// ABI, libnodeexec.so, a launcher executable that calls node::Start, npm as an asset, and
// NodeRuntime, which lays out the files, unpacks npm and starts processes. The app execs the
// launcher from nativeLibraryDir like `node` on a Pi; nothing here is called through JNI.
//
// libnode.so is not built here. It comes from nodejs-mobile's release zip, pinned in
// gradle.properties by version and SHA-256, downloaded once into the Gradle user home and
// checked before anything is unpacked. A zip that does not match stops the build: a runtime that
// executes every plugin a boat installs is the last thing to take on trust.

val nodejsMobileVersion = providers.gradleProperty("nodejsMobile.version").get()
val nodejsMobileSha256 = providers.gradleProperty("nodejsMobile.sha256").get()
val nodejsMobileZip = "nodejs-mobile-android-$nodejsMobileVersion.zip"
val npmVersion = providers.gradleProperty("npm.version").get()
val npmSha256 = providers.gradleProperty("npm.sha256").get()

/**
 * The NDK libnode.so was built with (nodejs-mobile's docs/BUILDING.md, and the .comment section of
 * the library). The launcher links libnode.so and ships libc++_shared.so beside it, so both have to
 * come from this NDK: a libc++ from another release is a different ABI under the same file name.
 */
val nodejsMobileNdk = "27.3.13750724"

/** Downloads [url] to [dest] unless a file with [sha256] is already there, and fails on any other content. */
abstract class FetchVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = dest.get().asFile
        if (out.isFile && digest(out) == sha256.get()) return
        out.parentFile.mkdirs()
        val part = File(out.parentFile, out.name + ".part")
        logger.lifecycle("Downloading ${url.get()}")
        // Timeouts, so a stalled mirror fails the build instead of holding a CI runner for hours.
        val conn = URI(url.get()).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        conn.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val got = digest(part)
        if (got != sha256.get()) {
            part.delete()
            throw GradleException("${url.get()} has SHA-256 $got, not the pinned ${sha256.get()} (gradle.properties)")
        }
        if (!part.renameTo(out)) throw GradleException("Could not move $part to $out")
    }

    private fun digest(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Unpacks the headers and each ABI's libnode.so from the verified zip, for CMake to compile and link against. */
abstract class UnpackNodejsMobile : DefaultTask() {
    @get:InputFile abstract val zip: RegularFileProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        val wanted = abis.get()
        fs.sync {
            from(archives.zipTree(zip)) {
                include("include/**")
                wanted.forEach { include("bin/$it/libnode.so") }
            }
            into(outputDir)
            includeEmptyDirs = false
        }
        val missing = wanted.filterNot { outputDir.file("bin/$it/libnode.so").get().asFile.isFile }
        if (missing.isNotEmpty()) throw GradleException("${zip.get().asFile.name} has no libnode.so for $missing")
    }
}

val fetchNodejsMobile = tasks.register<FetchVerified>("fetchNodejsMobile") {
    url.set("https://github.com/digidem/nodejs-mobile/releases/download/v$nodejsMobileVersion/$nodejsMobileZip")
    sha256.set(nodejsMobileSha256)
    // In the Gradle user home rather than build/, so a clean build does not fetch 78 MB again.
    dest.set(File(gradle.gradleUserHomeDir, "caches/pocketsk/$nodejsMobileZip"))
}

val nodejsMobileDir = layout.buildDirectory.dir("nodejs-mobile/$nodejsMobileVersion")

// npm from the registry rather than out of a Node release tarball: the same npm, published with
// its dependencies bundled, 3 MB instead of 30. Same rule as the zip: pinned, checked, cached.
val fetchNpm = tasks.register<FetchVerified>("fetchNpm") {
    url.set("https://registry.npmjs.org/npm/-/npm-$npmVersion.tgz")
    sha256.set(npmSha256)
    dest.set(File(gradle.gradleUserHomeDir, "caches/pocketsk/npm-$npmVersion.tgz"))
}

/** Puts the verified npm tarball into a generated assets directory as NodeRuntime.NPM_ASSET. */
abstract class NpmAsset : DefaultTask() {
    @get:InputFile abstract val tarball: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(tarball) { rename { "npm.tgz" } }
            into(outputDir)
        }
    }
}

val npmAsset = tasks.register<NpmAsset>("npmAsset") {
    tarball.set(fetchNpm.flatMap { it.dest })
    outputDir.set(layout.buildDirectory.dir("generated/npm-asset"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(npmAsset, NpmAsset::outputDir) }
}

val unpackNodejsMobile = tasks.register<UnpackNodejsMobile>("unpackNodejsMobile") {
    zip.set(fetchNodejsMobile.flatMap { it.dest })
    // The ABIs the build packages (abiFilters below) are the ones unpacked.
    abis.set(provider { android.defaultConfig.ndk.abiFilters.sorted() })
    outputDir.set(nodejsMobileDir)
}

android {
    namespace = "fi.pocketsk.node"
    compileSdk = 37
    ndkVersion = nodejsMobileNdk
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 29
        // What NodeRuntime stamps an unpacked npm with, so it can tell when the APK carries another.
        buildConfigField("String", "NPM_VERSION", "\"$npmVersion\"")
        buildConfigField("String", "NPM_SHA256", "\"$npmSha256\"")
        // The three ABIs nodejs-mobile's zip ships: the two phone ABIs, and x86_64 for the
        // emulator and ChromeOS. Written out here, not in a variable, so Lint can see x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DNODEJS_MOBILE_DIR=${nodejsMobileDir.get().asFile.absolutePath}",
                    // libnode.so needs libc++_shared.so; this makes the build package the NDK's copy.
                    "-DANDROID_STL=c++_shared",
                    // 16 KB pages (Pixel 9 and later, spike R6). The default from NDK r28 on; r27 asks.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // NDK r27's own toolchain files declare cmake_minimum_required(3.6), which
                    // CMake 3.31 reports as deprecated on every configure. They are the NDK's
                    // files, not this project's, whose CMake files require 3.31.
                    "-DCMAKE_WARN_DEPRECATED=OFF",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            // Not AGP's default 3.22.1, which writes the launcher's `$ORIGIN` RPATH as a bare `$`
            // (native/launcher/CMakeLists.txt). From the SDK repository, like the NDK.
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // The same gate as the app (app/build.gradle.kts): an error or a warning fails the build.
        abortOnError = true
        warningsAsErrors = true
    }
}

// CMake reads the headers and libnode.so while configuring, so every native task waits for them.
tasks.configureEach {
    if (name.contains("CMake") || name.startsWith("generateJsonModel")) dependsOn(unpackNodejsMobile)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// TarTest also unpacks the real npm tarball the APK carries, not only archives it builds itself.
tasks.withType<Test>().configureEach {
    val tarball = fetchNpm.flatMap { it.dest }
    inputs.file(tarball)
    doFirst { systemProperty("pocketsk.npmTarball", tarball.get().asFile.absolutePath) }
}

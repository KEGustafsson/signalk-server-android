import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

plugins {
    id("com.android.application")      // brings the Kotlin compiler with it (AGP built-in Kotlin)
    id("org.jetbrains.kotlin.plugin.compose")  // the Dock and the setup wizard (docs/PLAN.md §5.3, §8.1)
}

/** Output of a git command run in the repository, or empty when git is unavailable (a source download, say). */
fun git(vararg args: String): String = try {
    providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
} catch (_: Exception) { "" }

// Versions come from git, so every merge is a new version without anyone editing a number:
// versionCode is the commit count on the current branch (monotonic on main), versionName is
// 1.<count> and the short commit hash is shown in the Dock. The Signal K server itself is not
// versioned here: it is installed from npm at run time (docs/PLAN.md §5).
val commitCount = git("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val commitSha = git("rev-parse", "--short", "HEAD").ifEmpty { "local" }
val dirty = git("status", "--porcelain").isNotEmpty()
val appVersion = "1.$commitCount"

// The Node runtime and the npm node-runtime packages, as pinned in gradle.properties; the SBOM lists them.
val nodejsMobileVersion = providers.gradleProperty("nodejsMobile.version").get()
val nodejsMobileSha256 = providers.gradleProperty("nodejsMobile.sha256").get()
val npmVersion = providers.gradleProperty("npm.version").get()
val npmSha256 = providers.gradleProperty("npm.sha256").get()

// Release signing: a keystore named by SIGNALK_ANDROID_KEYSTORE (CI decodes it from a secret) or
// app/release.keystore locally, with its passwords from the environment. Without one the
// release build is signed with the debug key so `assembleRelease` always works; the README
// explains why a phone then cannot upgrade between a CI build and a local one.
//
// A keystore named explicitly but unusable is a mistake to stop on, not a reason to fall back:
// a typo in the path or a forgotten password would otherwise sign with the debug key without a
// word (and skip the shallow-clone guard below). CI's certificate check would catch it there;
// a local build has nothing else that would.
val keystoreEnv: String? = System.getenv("SIGNALK_ANDROID_KEYSTORE")
val keystoreFile = file(keystoreEnv ?: "release.keystore")
val keystorePassword: String? = System.getenv("SIGNALK_ANDROID_KEYSTORE_PASSWORD")
if (keystoreEnv != null) {
    if (!keystoreFile.isFile) throw GradleException("SIGNALK_ANDROID_KEYSTORE names $keystoreFile, which is not a file")
    if (keystorePassword.isNullOrEmpty()) throw GradleException("SIGNALK_ANDROID_KEYSTORE is set but SIGNALK_ANDROID_KEYSTORE_PASSWORD is empty")
}
val hasReleaseKey = keystoreFile.isFile && !keystorePassword.isNullOrEmpty()
// One line naming the key, so nobody reads "BUILD SUCCESSFUL" as "release-signed".
logger.lifecycle(
    when {
        hasReleaseKey -> "Release signing: project release key $keystoreFile"
        keystoreFile.isFile -> "Release signing: debug key ($keystoreFile found, but SIGNALK_ANDROID_KEYSTORE_PASSWORD is not set)"
        else -> "Release signing: debug key (no release keystore; see README, Releases)"
    }
)

// A shallow clone counts fewer commits, so a release-signed build from one could carry a lower
// versionCode than the last Release and be refused by the phone as a downgrade. Refuse first.
if (hasReleaseKey && git("rev-parse", "--is-shallow-repository") == "true") {
    throw GradleException("Release-signed builds need the full git history: run `git fetch --unshallow` first")
}

android {
    namespace = "fi.pocketsk"
    compileSdk = 37
    defaultConfig {
        applicationId = "fi.pocketsk"
        minSdk = 29
        targetSdk = 37
        versionCode = commitCount
        versionName = appVersion
        buildConfigField("String", "GIT_SHA", "\"$commitSha${if (dirty) "+" else ""}\"")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = System.getenv("SIGNALK_ANDROID_KEY_ALIAS") ?: "signalk-android"
                keyPassword = System.getenv("SIGNALK_ANDROID_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }
    buildTypes {
        release {
            // R8 shrinks and optimises the release build: unused AndroidX code and resources leave
            // the APK, and with them the surface a phone carries for nothing. Names are kept
            // (proguard-rules.pro) so a crash trace reads without a mapping file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    splits {
        // Most of the APK is libnode.so, once per ABI. Every build also makes an arm64-only APK,
        // which fits nearly every phone at minSdk 29 at a fraction of the size, next to the
        // universal one for 32-bit phones, the emulator and ChromeOS. Both carry the same
        // versionCode and signer, so a phone can move from one to the other. The x86_64 split is
        // built for Lint's ChromeOsAbiSupport check, which wants it in the list; it is not
        // published, since the universal APK covers ChromeOS already.
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        // The Node runtime is exec'd as a file from nativeLibraryDir (docs/PLAN.md §4.1), which
        // needs the .so files extracted at install time rather than mapped from the APK. This is
        // what puts extractNativeLibs="true" in the merged manifest; the manifest does not say it.
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17      // built-in Kotlin takes its jvmTarget from this
    }
    lint {
        // `gradlew lintRelease` is a CI gate: an error fails the build, and a warning is an error
        // (the report is app/build/reports/lint-results-release.html). A finding is fixed, or
        // suppressed inline with a comment saying why; it is never left standing.
        abortOnError = true
        warningsAsErrors = true
    }
}

// The build compiles and tests on a JDK 17 wherever it runs: Gradle looks for one on the machine
// (JAVA_HOME, the usual install locations, or -Porg.gradle.java.installations.paths=...) and
// refuses to download one (gradle.properties), so the toolchain is what the machine provides,
// not a silent download from wherever the current foojay index points.
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// The Android bridge's server half, sk-plugin/android-bridge (docs/PLAN.md §7.2), goes into the
// APK as a plain tar, its tests left out: it has no dependencies, so BridgePlugin can put it in
// place for the server before every start with no npm and no network.
val bridgePluginTar = tasks.register<Tar>("bridgePluginTar") {
    from(rootProject.file("sk-plugin/android-bridge")) {
        include("package.json", "index.js", "lib/**", "README.md", "CHANGELOG.md", "LICENSE")
    }
    archiveFileName.set("android-bridge.tar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/bridge-plugin"))
    compression = Compression.NONE
    // The same tar for the same sources, so BridgePlugin's checksum changes only with them.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

/** Puts the bridge plugin's tar into a generated assets directory as BridgePlugin.ASSET. */
abstract class BridgePluginAsset : DefaultTask() {
    @get:InputFile abstract val tarball: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(tarball)
            into(outputDir)
        }
    }
}

val bridgePluginAsset = tasks.register<BridgePluginAsset>("bridgePluginAsset") {
    tarball.set(bridgePluginTar.flatMap { it.archiveFile })
    outputDir.set(layout.buildDirectory.dir("generated/bridge-plugin-asset"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(bridgePluginAsset, BridgePluginAsset::outputDir) }
}

// BridgePluginTarTest unpacks the very tar the APK carries with the app's own reader.
tasks.withType<Test>().configureEach {
    val tarball = bridgePluginTar.flatMap { it.archiveFile }
    inputs.file(tarball)
    doFirst { systemProperty("pocketsk.bridgeTar", tarball.get().asFile.absolutePath) }
}

/** Hex SHA-256 of a file, for the SBOM. */
fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * `gradlew sbom` writes app/build/reports/bom.json: a CycloneDX 1.5 software bill of materials
 * of everything on the release runtime classpath, attached to every Release. Written here
 * rather than by a plugin so the build itself pulls in nothing extra. Each component carries the
 * SHA-256 of its artifact(s), so a reader can check the jar or aar against Maven, and the
 * `dependencies` graph says who pulled in what; the serial number is new for every run.
 */
tasks.register("sbom") {
    val out = layout.buildDirectory.file("reports/bom.json")
    outputs.file(out)
    outputs.upToDateWhen { false }         // timestamped and classpath-dependent: never reuse a stale one
    doLast {
        val cfg = configurations.getByName("releaseRuntimeClasspath")
        val appRef = "pkg:generic/SignalKServerAndroid@$appVersion"
        fun purl(id: ComponentIdentifier): String? =
            (id as? ModuleComponentIdentifier)?.let { "pkg:maven/${it.group}/${it.module}@${it.version}" }

        // The graph from the resolution result: every module component and what it depends on.
        val dependsOn = sortedMapOf<String, List<String>>()
        val modules = sortedMapOf<String, ModuleComponentIdentifier>()
        fun walk(c: ResolvedComponentResult, ref: String) {
            if (dependsOn.containsKey(ref)) return
            val next = c.dependencies.filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { d -> purl(d.selected.id)?.let { it to d.selected } }
            dependsOn[ref] = next.map { it.first }.distinct().sorted()
            next.forEach { (r, sel) -> modules[r] = sel.id as ModuleComponentIdentifier; walk(sel, r) }
        }
        walk(cfg.incoming.resolutionResult.rootComponent.get(), appRef)

        // The files behind them, hashed. A module without an artifact (a BOM, a Kotlin
        // multiplatform umbrella pointing at its -jvm variant) is listed without hashes. Only
        // Maven modules are asked for files: this build's own modules (node-runtime) are not
        // downloads, and what they carry is listed below.
        val files = cfg.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }
            .artifacts.artifacts
            .groupBy({ purl(it.id.componentIdentifier)!! }, { it.file })
        val mavenComps = modules.entries.map { (ref, id) ->
            val hashes = (files[ref] ?: emptyList()).distinct().sortedBy { it.name }
                .joinToString(", ") { """{"alg": "SHA-256", "content": "${sha256(it)}"}""" }
            """    {"type": "library", "group": "${id.group}", "name": "${id.module}", "version": "${id.version}", "purl": "$ref", "bom-ref": "$ref", "hashes": [$hashes]}"""
        }
        // The Node runtime is not a Maven artifact: node-runtime downloads nodejs-mobile's release
        // zip, pinned by SHA-256 in gradle.properties, and packages its libnode.so files.
        val nodeRef = "pkg:github/digidem/nodejs-mobile@v$nodejsMobileVersion"
        val nodeComp = """    {"type": "library", "name": "nodejs-mobile", "version": "$nodejsMobileVersion", "purl": "$nodeRef", "bom-ref": "$nodeRef", "licenses": [{"license": {"id": "MIT"}}], "hashes": [{"alg": "SHA-256", "content": "$nodejsMobileSha256"}], "externalReferences": [{"type": "distribution", "url": "https://github.com/digidem/nodejs-mobile/releases/download/v$nodejsMobileVersion/nodejs-mobile-android-$nodejsMobileVersion.zip"}]}"""
        // npm, the registry tarball node-runtime puts into the assets and NodeRuntime unpacks.
        val npmRef = "pkg:npm/npm@$npmVersion"
        val npmComp = """    {"type": "application", "name": "npm", "version": "$npmVersion", "purl": "$npmRef", "bom-ref": "$npmRef", "licenses": [{"license": {"id": "Artistic-2.0"}}], "hashes": [{"alg": "SHA-256", "content": "$npmSha256"}], "externalReferences": [{"type": "distribution", "url": "https://registry.npmjs.org/npm/-/npm-$npmVersion.tgz"}]}"""
        dependsOn[appRef] = (dependsOn.getValue(appRef) + nodeRef + npmRef).sorted()
        val comps = (mavenComps + nodeComp + npmComp).joinToString(",\n")
        val deps = dependsOn.entries.joinToString(",\n") { (ref, on) ->
            """    {"ref": "$ref", "dependsOn": [${on.joinToString(", ") { "\"$it\"" }}]}"""
        }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "serialNumber": "urn:uuid:${UUID.randomUUID()}",
  "version": 1,
  "metadata": {
    "timestamp": "${Instant.now()}",
    "tools": {
      "components": [
        {"type": "application", "name": "Gradle", "version": "${gradle.gradleVersion}"},
        {"type": "application", "name": "signalk-server-android sbom task (app/build.gradle.kts)", "version": "$appVersion"}
      ]
    },
    "component": {"type": "application", "name": "SignalKServerAndroid", "version": "$appVersion", "bom-ref": "$appRef", "licenses": [{"license": {"id": "EUPL-1.2"}}]}
  },
  "components": [
$comps
  ],
  "dependencies": [
$deps
  ]
}
"""
        )
    }
}

/**
 * `gradlew printVersion` writes the versionName to app/build/version.txt (and prints it), for the
 * release workflow to name the APK and the tag. The file, not Gradle's last stdout line, is what
 * the workflow reads: anything else printed during the build would otherwise become the tag.
 */
tasks.register("printVersion") {
    val out = layout.buildDirectory.file("version.txt")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText("$appVersion\n")
        println(appVersion)
    }
}

dependencies {
    implementation(project(":node-runtime"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    // Compose for the Dock and the setup wizard; the BOM keeps its libraries on one release.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    testImplementation("junit:junit:4.13.2")
}

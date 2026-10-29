package fi.pocketsk.update

import fi.pocketsk.bridge.Json

/**
 * This app's own releases on GitHub, as the self-update reads them (docs/PLAN.md §10): which
 * release is newer than the installed build, which of its APKs this phone takes, the checksum
 * published beside it, whether a downloaded APK may be handed to the system's installer, and
 * whether the app updates itself at all.
 *
 * The release workflow (`.github/workflows/build.yml`) tags each build `v1.<commit count>`, the
 * same count that is the build's versionCode, and attaches `SignalKServer-<version>.apk` (every
 * ABI), `SignalKServer-<version>-arm64-v8a.apk` and a `.sha256` file for each, the line
 * `sha256sum` writes. Both APKs carry the same versionCode and signer, so a phone may move from
 * one to the other.
 *
 * Pure, so the rules are unit-tested; [AppUpdater] fetches, downloads and installs.
 */
internal object AppRelease {

    /** One file of a release: its name, where it is downloaded from, its size in bytes. */
    data class Asset(val name: String, val url: String, val size: Long)

    /** A release this phone can install: its build number, its name ("1.193"), its page, its APK and checksum. */
    data class Release(
        val versionCode: Long,
        val versionName: String,
        val page: String,
        val apk: Asset,
        val checksum: Asset,
    )

    /**
     * GitHub's answer to `GET /repos/<owner>/<repo>/releases/latest`, as a release this phone can
     * install: the arm64 APK when [abis] (the phone's, most preferred first) has arm64-v8a, the
     * one for every ABI otherwise. Null when it is a draft or a prerelease, its tag is not one the
     * workflow makes, or the APK or its checksum is missing. Throws [Json.ParseException] when the
     * answer is not an object.
     */
    fun parse(json: String, abis: List<String>): Release? {
        val r = Json.parse(json) as? Map<*, *> ?: throw Json.ParseException("not a release")
        if (r["draft"] == true || r["prerelease"] == true) return null
        val tag = TAG.matchEntire(r["tag_name"] as? String ?: return null) ?: return null
        val versionName = "1.${tag.groupValues[1]}"
        val code = tag.groupValues[1].toLongOrNull() ?: return null
        val assets = (r["assets"] as? List<*>).orEmpty().mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            val url = m["browser_download_url"] as? String ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            Asset(name, url, (m["size"] as? Double)?.toLong() ?: -1L)
        }.associateBy { it.name }
        val apk = assets[apkName(versionName, abis)] ?: return null
        val checksum = assets["${apk.name}.sha256"] ?: return null
        return Release(code, versionName, r["html_url"] as? String ?: "", apk, checksum)
    }

    /** The APK this phone takes from release [versionName]: the arm64 one when it runs arm64 code. */
    fun apkName(versionName: String, abis: List<String>): String =
        if (ARM64 in abis) "SignalKServer-$versionName-$ARM64.apk" else "SignalKServer-$versionName.apk"

    /** Whether [release] is newer than the build installed, whose versionCode is [installed]. */
    fun isNewer(release: Release, installed: Long): Boolean = release.versionCode > installed

    /**
     * The SHA-256 a `.sha256` file gives for [fileName], lower case, or null when it names none:
     * `sha256sum`'s line, 64 hex digits, two spaces (or a space and `*`), the file's name.
     */
    fun checksumOf(text: String, fileName: String): String? = text.lineSequence()
        .mapNotNull { SUM_LINE.matchEntire(it.trim()) }
        .firstOrNull { it.groupValues[2] == fileName }
        ?.groupValues?.get(1)?.lowercase()

    /**
     * Why a downloaded APK may not be handed to the installer, or null when it may: its checksum
     * is the one published ([expectedSha256], [actualSha256]), the system could read it as this
     * app ([apkPackage] is [ownPackage]) of a newer build ([apkVersionCode] above [installed]),
     * and [sameSigner] says it is signed by this app's own key. Null [sameSigner] is a phone that
     * cannot read an archive's signer: the system's installer still refuses an update signed by
     * another key, so the check is left to it. The first reason found is given.
     */
    fun refusal(
        expectedSha256: String,
        actualSha256: String,
        apkPackage: String?,
        apkVersionCode: Long?,
        ownPackage: String,
        installed: Long,
        sameSigner: Boolean?,
    ): Refusal? = when {
        !expectedSha256.equals(actualSha256, ignoreCase = true) -> Refusal.CHECKSUM
        apkPackage == null || apkVersionCode == null -> Refusal.UNREADABLE
        apkPackage != ownPackage -> Refusal.OTHER_APP
        apkVersionCode <= installed -> Refusal.NOT_NEWER
        sameSigner == false -> Refusal.OTHER_SIGNER
        else -> null
    }

    /** Why a downloaded APK was not installed; the Dock words each one. */
    enum class Refusal { CHECKSUM, UNREADABLE, OTHER_APP, NOT_NEWER, OTHER_SIGNER }

    /**
     * Whether the app offers to update itself when [installer] (the package that installed it, as
     * the system says; null when it does not know) put it on the phone. Not when a store did that
     * updates it itself: an F-Droid client, whose builds are signed by F-Droid and could not take
     * this repository's APK anyway, or Google Play.
     */
    fun selfUpdates(installer: String?): Boolean = installer !in STORES

    /** Installers that keep the app up to date themselves. */
    val STORES = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "com.looker.droidify",
        "com.machiav3lli.fdroid",
        "com.android.vending",
    )

    private const val ARM64 = "arm64-v8a"

    /** The workflow's tags: `v1.<commit count>`. */
    private val TAG = Regex("""v1\.(\d{1,9})""")

    private val SUM_LINE = Regex("""([0-9a-fA-F]{64}) [ *](.+)""")
}

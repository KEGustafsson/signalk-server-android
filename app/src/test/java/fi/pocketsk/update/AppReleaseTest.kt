package fi.pocketsk.update

import fi.pocketsk.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** This app's GitHub releases as the self-update reads them. */
class AppReleaseTest {
    private val base = "https://github.com/KEGustafsson/signalk-server-android/releases/download/v1.193"

    private fun asset(name: String, size: Long = 100) =
        """{"name":${Json.quote(name)},"size":$size,"browser_download_url":${Json.quote("$base/$name")}}"""

    /** A release as the workflow publishes it. */
    private fun release(
        tag: String = "v1.193",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<String> = listOf(
            asset("SignalKServer-1.193-arm64-v8a.apk", 29993089),
            asset("SignalKServer-1.193-arm64-v8a.apk.sha256"),
            asset("SignalKServer-1.193.apk", 75300435),
            asset("SignalKServer-1.193.apk.sha256"),
            asset("SignalKServer-1.193.sbom.cdx.json"),
        ),
    ) = """{"tag_name":${Json.quote(tag)},"draft":$draft,"prerelease":$prerelease,""" +
        """"html_url":"https://github.com/KEGustafsson/signalk-server-android/releases/tag/$tag",""" +
        """"assets":${assets.joinToString(",", "[", "]")}}"""

    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test
    fun anArm64PhoneTakesTheArm64ApkAndItsChecksum() {
        val r = AppRelease.parse(release(), arm64)!!
        assertEquals(193L, r.versionCode)
        assertEquals("1.193", r.versionName)
        assertEquals("SignalKServer-1.193-arm64-v8a.apk", r.apk.name)
        assertEquals(29993089L, r.apk.size)
        assertEquals("$base/SignalKServer-1.193-arm64-v8a.apk", r.apk.url)
        assertEquals("SignalKServer-1.193-arm64-v8a.apk.sha256", r.checksum.name)
        assertTrue(r.page.endsWith("/releases/tag/v1.193"))
    }

    @Test
    fun anyOtherPhoneTakesTheApkForEveryAbi() {
        assertEquals("SignalKServer-1.193.apk", AppRelease.parse(release(), listOf("x86_64", "x86"))!!.apk.name)
        assertEquals("SignalKServer-1.193.apk", AppRelease.parse(release(), listOf("armeabi-v7a", "armeabi"))!!.apk.name)
    }

    @Test
    fun aReleaseWithoutItsApkOrChecksumIsNoReleaseForThisPhone() {
        assertNull(AppRelease.parse(release(assets = listOf(asset("SignalKServer-1.193.apk"), asset("SignalKServer-1.193.apk.sha256"))), arm64))
        assertNull(AppRelease.parse(release(assets = listOf(asset("SignalKServer-1.193-arm64-v8a.apk"))), arm64))
        assertNull(AppRelease.parse(release(assets = emptyList()), arm64))
    }

    @Test
    fun draftsPrereleasesAndOtherTagsAreNotOffered() {
        assertNull(AppRelease.parse(release(draft = true), arm64))
        assertNull(AppRelease.parse(release(prerelease = true), arm64))
        assertNull(AppRelease.parse(release(tag = "prebuilds"), arm64))
        assertNull(AppRelease.parse(release(tag = "v2.0"), arm64))
        assertNull(AppRelease.parse(release(tag = "v1.193-rc1"), arm64))
    }

    @Test
    fun anAssetNotServedOverHttpsIsLeftOut() {
        val plain = """{"name":"SignalKServer-1.193-arm64-v8a.apk","size":1,"browser_download_url":"http://example.com/x.apk"}"""
        assertNull(AppRelease.parse(release(assets = listOf(plain, asset("SignalKServer-1.193-arm64-v8a.apk.sha256"))), arm64))
    }

    @Test(expected = Json.ParseException::class)
    fun anAnswerThatIsNotAReleaseIsAnError() {
        AppRelease.parse("[]", arm64)
    }

    @Test
    fun onlyANewerBuildIsOffered() {
        val r = AppRelease.parse(release(), arm64)!!
        assertTrue(AppRelease.isNewer(r, 192))
        assertFalse(AppRelease.isNewer(r, 193))
        assertFalse(AppRelease.isNewer(r, 200))
    }

    @Test
    fun theChecksumIsReadFromSha256sumsLine() {
        val sum = "ab".repeat(32)
        assertEquals(sum, AppRelease.checksumOf("$sum  SignalKServer-1.193.apk\n", "SignalKServer-1.193.apk"))
        assertEquals(sum, AppRelease.checksumOf("${sum.uppercase()} *SignalKServer-1.193.apk", "SignalKServer-1.193.apk"))
        assertNull("another file's sum", AppRelease.checksumOf("$sum  SignalKServer-1.192.apk\n", "SignalKServer-1.193.apk"))
        assertNull(AppRelease.checksumOf("abc  SignalKServer-1.193.apk", "SignalKServer-1.193.apk"))
        assertNull(AppRelease.checksumOf("", "SignalKServer-1.193.apk"))
    }

    private val sum = "cd".repeat(32)

    private fun refusal(
        actual: String = sum,
        pkg: String? = "fi.pocketsk",
        code: Long? = 193,
        installed: Long = 180,
        sameSigner: Boolean? = true,
    ) = AppRelease.refusal(sum, actual, pkg, code, "fi.pocketsk", installed, sameSigner)

    @Test
    fun anApkIsInstalledOnlyWhenEveryCheckPasses() {
        assertNull(refusal())
        assertNull("a phone that cannot read the signer leaves it to the installer", refusal(sameSigner = null))
        assertEquals(AppRelease.Refusal.CHECKSUM, refusal(actual = "00".repeat(32)))
        assertEquals("the case of the hex does not matter", null, refusal(actual = sum.uppercase()))
        assertEquals(AppRelease.Refusal.UNREADABLE, refusal(pkg = null, code = null))
        assertEquals(AppRelease.Refusal.OTHER_APP, refusal(pkg = "com.example.other"))
        assertEquals(AppRelease.Refusal.NOT_NEWER, refusal(code = 180))
        assertEquals(AppRelease.Refusal.OTHER_SIGNER, refusal(sameSigner = false))
        assertEquals("the checksum is checked first", AppRelease.Refusal.CHECKSUM, refusal(actual = "00".repeat(32), pkg = "x", sameSigner = false))
    }

    @Test
    fun theArchiveIsReadWithBothSignatureFlags() {
        // PackageManager's own, from the SDK: GET_SIGNATURES and GET_SIGNING_CERTIFICATES.
        assertEquals(0x40, AppRelease.GET_SIGNATURES)
        assertEquals(0x08000040, AppRelease.archiveFlags(0x08000000))
    }

    @Test
    fun storesThatUpdateTheAppThemselvesTurnTheSelfUpdateOff() {
        assertTrue(AppRelease.selfUpdates(null))
        assertTrue(AppRelease.selfUpdates("com.android.packageinstaller"))
        assertTrue(AppRelease.selfUpdates("com.google.android.packageinstaller"))
        assertTrue(AppRelease.selfUpdates("dev.imranr.obtainium"))
        assertFalse(AppRelease.selfUpdates("org.fdroid.fdroid"))
        assertFalse(AppRelease.selfUpdates("com.looker.droidify"))
        assertFalse(AppRelease.selfUpdates("com.android.vending"))
    }
}

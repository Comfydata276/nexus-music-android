package org.medianexus.music.playback

import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.medianexus.music.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Who counts as a known host of media apps: Google's list, read from the app's own copy of
 * it, and checked against what the system says about the caller (Robolectric's package
 * manager standing in for the phone's).
 */
@RunWith(RobolectricTestRunner::class)
class KnownHostsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val listed = context.resources.getXml(R.xml.allowed_media_browser_callers).use { KnownHosts.parse(it) }

    private val auto = "com.google.android.projection.gearhead"

    /** The certificate Android Auto's release builds are signed with, from the list. */
    private val autoKey = "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf"

    @Test
    fun theListIsGooglesAndNothingElse() {
        assertEquals(
            setOf(
                "com.google.android.projection.gearhead",
                "com.google.android.wearable.app",
                "com.google.android.autosimulator",
                "com.google.android.googlequicksearchbox",
                "com.google.android.carassistant",
            ),
            listed.packages,
        )
        assertEquals(4, listed.keysOf(auto).size)
        assertTrue(autoKey in listed.keysOf(auto))
        // Written with capitals in the file; read the same as the rest.
        assertTrue("74b6fbf710e8d90d44d340125889b42306a62c4379d0e5a66220e3a68abf90e2" in listed.keysOf("com.google.android.carassistant"))
        assertTrue(listed.keysOf("org.medianexus.carprobe").isEmpty())
    }

    @Test
    fun aPackageNameAloneIsNeverEnough() {
        val signedRight = { name: String, sha256: ByteArray -> name == auto && sha256.contentEquals(KnownHosts.bytes(autoKey)) }
        // The real thing: the name the system gives for the caller, signed as listed.
        assertTrue(listed.matches(auto, listOf(auto), signedRight))
        // An app that calls itself Android Auto and is signed by someone else.
        assertFalse(listed.matches(auto, listOf(auto)) { _, _ -> false })
        // An app that only says it is Android Auto: the system knows the caller by another name.
        assertFalse(listed.matches(auto, listOf("com.example.pretender"), signedRight))
        // Google's certificate on a package the list doesn't name.
        assertFalse(listed.matches("com.example.other", listOf("com.example.other")) { _, _ -> true })
        // A certificate listed for one host, on another host's name.
        assertFalse(listed.matches("com.google.android.wearable.app", listOf("com.google.android.wearable.app")) { _, sha256 -> sha256.contentEquals(KnownHosts.bytes(autoKey)) })
        assertFalse(listed.matches(null, listOf(auto), signedRight))
    }

    @Test
    fun aKeyThatIsNotAKeyAdmitsNobody() {
        assertNull(KnownHosts.tidy("fd:b0:0c"))
        assertNull(KnownHosts.tidy(""))
        assertNull(KnownHosts.tidy("zz".repeat(32)))
        assertEquals(autoKey, KnownHosts.tidy("FD:B0:0C:43:DB:DE:8B:51:CB:31:2A:A8:1D:3B:5F:A1:\n  77:13:AD:B9:4B:28:F5:98:D7:7F:8E:B8:9D:AC:EE:DF "))
        val none = KnownHosts(emptyMap()) + mapOf("some.app" to setOf("not a key"))
        assertTrue(none.keysOf("some.app").isEmpty())
    }

    /** Installs an app on the stand-in phone, signed with `certificate`; returns its uid. */
    private fun install(name: String, certificate: ByteArray, uid: Int): Int {
        val info = PackageInfo().apply {
            packageName = name
            @Suppress("DEPRECATION")
            signatures = arrayOf(Signature(certificate))
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = name; this.uid = uid }
        }
        shadowOf(context.packageManager).installPackage(info)
        shadowOf(context.packageManager).setPackagesForUid(uid, name)
        return uid
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theSystemIsAskedAboutTheCallersOwnCertificate() {
        val impostorsCertificate = "a certificate that is not Google's".toByteArray()
        val callers = Callers(context, listed)
        // Something installed under Android Auto's name with its own signature.
        val impostor = install(auto, impostorsCertificate, 10_123)
        assertFalse(callers.knownHost(auto, impostor))
        assertFalse(callers.standing(auto, 1, impostor).admitted)
        // An app the list doesn't name at all.
        val other = install("com.example.player", impostorsCertificate, 10_124)
        assertFalse(callers.knownHost("com.example.player", other))
        // Saying it is Android Auto from another app's uid.
        assertFalse(callers.knownHost(auto, other))
    }

    /** A phone with these apps on it: each a uid, a name and the certificate it is signed with. */
    private class Phone(apps: List<Triple<Int, String, ByteArray>>, private val trusts: Set<Int> = emptySet()) : Callers.Phone {
        var apps = apps
        /** The system can't say who a uid is just now (the lookup throws). */
        var failing = false
        var lookups = 0
        private var told: (String) -> Unit = {}

        override val ownUid = 10_001
        override fun packagesOf(uid: Int): List<String> {
            lookups++
            if (failing) throw IllegalStateException("Package manager has died")
            return apps.filter { it.first == uid }.map { it.second }
        }
        override fun signedWith(packageName: String, sha256: ByteArray) =
            apps.any { it.second == packageName && MessageDigest.getInstance("SHA-256").digest(it.third).contentEquals(sha256) }
        override fun trustedForMedia(packageName: String, pid: Int, uid: Int) = uid in trusts
        /** Apps that carry a history of certificates rotated from. */
        var rotated = emptySet<String>()
        override fun signing(packageName: String) =
            apps.filter { it.second == packageName }.map { it.third }.takeIf { it.isNotEmpty() }?.let { Callers.Signing(it, packageName in rotated) }
        override fun onPackageChanged(changed: (packageName: String) -> Unit) {
            told = changed
        }

        /** The system's broadcast that an app has been installed, updated or removed. */
        fun changed(packageName: String) = told(packageName)
    }

    @Test
    fun whatWasWorkedOutForAnAppIsForgottenWhenThatAppIsInstalledUpdatedOrRemoved() {
        val googles = KnownHosts.bytes(autoKey)
        // Android Auto as a phone might ship it: a build signed with a key the list doesn't
        // have. Not a known host.
        val phone = Phone(listOf(Triple(10_200, auto, "a key the list does not have".toByteArray())))
        val asGoogle = object : Callers.Phone by phone {
            // (The list holds SHA-256s; this stand-in "signs" an app with whatever bytes it
            // is given, so Google's key is stood for by an app whose bytes hash to nothing
            // listed, and by a flag for the real thing.)
            override fun signedWith(packageName: String, sha256: ByteArray) = packageName == auto && updated && sha256.contentEquals(googles)
            var updated = false
        }
        val callers = Callers(asGoogle, listed)
        assertFalse(callers.knownHost(auto, 10_200))
        // It is updated from the Play Store to Google's own build, same uid. The system says
        // so; the answer worked out before is forgotten. It used to be kept for the life of
        // the process: the car went on being refused until the app was restarted.
        asGoogle.updated = true
        phone.changed(auto)
        assertTrue(callers.knownHost(auto, 10_200))
        // Removed, and something else installed under its name, which Android gives the
        // same uid as it happens.
        asGoogle.updated = false
        phone.changed(auto)
        assertFalse(callers.knownHost(auto, 10_200))
        // Another app's coming and going changes nothing, and nothing is asked again.
        val before = phone.lookups
        phone.changed("com.example.other")
        assertFalse(callers.knownHost(auto, 10_200))
        assertEquals(before, phone.lookups)
    }

    @Test
    fun aNoThatCameFromALookupFailingIsNotRemembered() {
        val certificate = "the probe's certificate".toByteArray()
        val phone = Phone(listOf(Triple(10_200, "org.medianexus.carprobe", certificate)))
        // (No extra hosts are remembered apart; this is the release path, with the probe on
        // a list of its own for the test.)
        val callers = Callers(phone, KnownHosts(mapOf("org.medianexus.carprobe" to setOf(sha256(certificate)))))
        // The system can't answer just now (the package manager is restarting, the app is
        // mid-update): not a known host, this time.
        phone.failing = true
        assertFalse(callers.knownHost("org.medianexus.carprobe", 10_200))
        // Asked again when it can: it is one. The "no" used to be kept.
        phone.failing = false
        assertTrue(callers.knownHost("org.medianexus.carprobe", 10_200))
        // Nor is a no kept that came from the system not knowing the uid at all.
        assertFalse(callers.knownHost("org.medianexus.carprobe", 10_999))
        phone.apps = phone.apps + Triple(10_999, "org.medianexus.carprobe", certificate)
        assertTrue(callers.knownHost("org.medianexus.carprobe", 10_999))
        // A real yes, and a real no, are remembered: nothing is asked twice.
        val asked = phone.lookups
        assertTrue(callers.knownHost("org.medianexus.carprobe", 10_200))
        assertEquals(asked, phone.lookups)
    }

    @Test
    fun aNameIsOnlyTakenFromACallerTheSystemHasCheckedOrThatReallyHasIt() {
        val phone = Phone(listOf(Triple(10_300, "com.example.watch", ByteArray(4)), Triple(10_400, "com.example.pretender", ByteArray(4))))
        val callers = Callers(phone, listed)
        // The system checked the name against the caller (Android 13 and later do).
        assertTrue(callers.namedTruly("com.example.watch", 10_400, checkedBySystem = true))
        // It didn't (Android 12, on the platform's own transport): the name is the
        // caller's only if the system says that uid is that package.
        assertTrue(callers.namedTruly("com.example.watch", 10_300, checkedBySystem = false))
        assertFalse("another app's name was taken on its say-so", callers.namedTruly("com.example.watch", 10_400, checkedBySystem = false))
        assertFalse(callers.namedTruly(null, 10_400, checkedBySystem = false))
        // This app, and the system itself, are who the uid says they are.
        assertTrue(callers.namedTruly("anything", phone.ownUid, checkedBySystem = false))
        assertTrue(callers.namedTruly("android", android.os.Process.SYSTEM_UID, checkedBySystem = false))
        // If the system can't say, it isn't taken on trust.
        phone.failing = true
        assertFalse(callers.namedTruly("com.example.watch", 10_300, checkedBySystem = false))
    }

    @Test
    fun aCallerOnTheListWithItsOwnCertificateIsLetIn() {
        // The test rig's probe, added to a debug build's list by its certificate (a release
        // build is given no such list: see DebugHosts). (Android's own answer to "is this
        // package signed with that certificate" is not something Robolectric can give: the
        // real one is asked on the emulator, by `scripts/carcheck --known-host`.)
        val certificate = "the probe's certificate".toByteArray()
        val phone = Phone(
            listOf(
                Triple(10_200, "org.medianexus.carprobe", certificate),
                Triple(10_300, "com.example.other", certificate),
                Triple(10_400, "com.example.watch", "a watch app's certificate".toByteArray()),
            ),
            trusts = setOf(10_400),
        )
        val release = Callers(phone, listed)
        assertFalse(release.knownHost("org.medianexus.carprobe", 10_200))
        assertFalse(release.standing("org.medianexus.carprobe", 1, 10_200).admitted)
        val debug = Callers(phone, listed) { mapOf("org.medianexus.carprobe" to setOf(sha256(certificate))) }
        assertTrue(debug.knownHost("org.medianexus.carprobe", 10_200))
        assertEquals(Callers.Standing(own = false, trusted = false, knownHost = true), debug.standing("org.medianexus.carprobe", 1, 10_200))
        // The same certificate under another name; the listed name claimed from another uid.
        assertFalse(debug.knownHost("com.example.other", 10_300))
        assertFalse(debug.knownHost("org.medianexus.carprobe", 10_300))
        // The same name, signed with something else.
        val forged = Callers(Phone(listOf(Triple(10_200, "org.medianexus.carprobe", "another certificate".toByteArray()))), listed) {
            mapOf("org.medianexus.carprobe" to setOf(sha256(certificate)))
        }
        assertFalse(forged.knownHost("org.medianexus.carprobe", 10_200))
        // Who may read covers is the same: this app, what Android trusts, a known host.
        assertTrue(release.standing("org.medianexus.music", 1, 10_001).admitted)
        assertTrue(release.standing("com.example.watch", 1, 10_400).admitted)
        assertFalse(release.standing("com.example.other", 1, 10_300).admitted)
    }

    @Test
    fun aKnownHostIsLetInWhateverAndroidHasGrantedIt() {
        fun caller(trusted: Boolean, knownHost: Boolean) =
            Access.caller(trusted = trusted, knownHost = knownHost, sameUid = false, ownPackage = false, legacy = true, notification = false)
        // Android Auto on a phone where it holds neither notification access nor the
        // system's permission to control media: it was refused, and the car showed an app
        // that would not open.
        assertEquals(Caller.TRUSTED, caller(trusted = false, knownHost = true))
        assertEquals(Caller.TRUSTED, caller(trusted = true, knownHost = false))
        assertEquals(Caller.REFUSED, caller(trusted = false, knownHost = false))
        // A known host is a car, not the app: it gets none of the app's own commands.
        assertFalse(Access.mayCustom(caller(trusted = false, knownHost = true), AppActions.CLEAR))
        assertTrue(Access.mayCustom(caller(trusted = false, knownHost = true), "org.medianexus.music.LIKE"))
    }
    /** Diagnostics says who a refused caller is signed by, so it can be let in by name and
     *  certificate (a watch's phone-side plug-in): every certificate, the form Google's list
     *  is written in, whether there is a history; and nothing for a name not the caller's. */
    @Test
    fun aRefusedCallerIsSaidToBeSignedByEachOfItsCertificates() {
        val one = "the plug-in's certificate".toByteArray()
        val other = "a second signer's certificate".toByteArray()
        val phone = Phone(
            listOf(
                Triple(10_500, "com.example.plugin", one),
                Triple(10_600, "com.example.twosigners", one),
                Triple(10_600, "com.example.twosigners", other),
            ),
        )
        val callers = Callers(phone, listed)
        val colons = { bytes: ByteArray -> sha256(bytes).chunked(2).joinToString(":") }
        assertEquals(
            listOf("refused caller com.example.plugin is signed with SHA-256 ${colons(one)}; signing history: no"),
            callers.signedBy("com.example.plugin", 10_500),
        )
        // Read back as the list reads a key: what is said is what would be listed.
        assertEquals(sha256(one), KnownHosts.tidy(colons(one)))
        phone.rotated = setOf("com.example.plugin")
        assertTrue(callers.signedBy("com.example.plugin", 10_500).single().endsWith("; signing history: yes"))
        assertEquals(
            listOf(
                "refused caller com.example.twosigners is signed with certificate 1 of 2, SHA-256 ${colons(one)}; signing history: no",
                "refused caller com.example.twosigners is signed with certificate 2 of 2, SHA-256 ${colons(other)}; signing history: no",
            ),
            callers.signedBy("com.example.twosigners", 10_600),
        )
        // Every line fits in one of Diagnostics' (a fingerprint is 95 characters).
        assertTrue(callers.signedBy("com.example.twosigners", 10_600).all { it.length <= org.medianexus.music.Diagnostics.MOST_CHARS })
        // A name the caller only gave: another app's certificate says nothing of the caller.
        assertEquals(
            listOf("refused caller com.example.plugin: its signing certificate not looked up (the system doesn't say the name is the caller's)"),
            callers.signedBy("com.example.plugin", 10_600),
        )
        assertTrue(callers.signedBy(null, 10_500).isEmpty())
        // And it lets nobody in.
        assertFalse(callers.standing("com.example.plugin", 1, 10_500).admitted)
    }
}

package org.medianexus.music.playback

import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser

/**
 * The apps known to host media apps (Android Auto, the Google app and its Assistant, Wear
 * OS and the like), each by its package name **and** the certificates it is signed with:
 * Google's own list, as its Universal Android Music Player sample ships it
 * (`res/xml/allowed_media_browser_callers.xml`, which says where it came from).
 *
 * A package name is only what an app calls itself. The certificate is what makes it the
 * app Google ships, so a caller is one of these only if both match.
 */
class KnownHosts(private val keys: Map<String, Set<String>>) {
    /** The SHA-256 of each signing certificate the app with this package name may carry, as
     *  64 hex digits; empty for a package that isn't a known host. */
    fun keysOf(packageName: String): Set<String> = keys[packageName].orEmpty()

    val packages: Set<String> get() = keys.keys

    /**
     * Whether a caller is one of the known hosts. `packageName` is what the caller says it
     * is; `ofCaller` the packages the system says the calling uid really is; `signedWith`
     * asks the system whether a package is signed with the certificate that has this
     * SHA-256. All three must agree: the name must be the caller's own, be on the list, and
     * be signed with one of the certificates listed for that name.
     */
    fun matches(packageName: String?, ofCaller: Collection<String>, signedWith: (packageName: String, sha256: ByteArray) -> Boolean): Boolean {
        if (packageName == null || packageName !in ofCaller) return false
        return keysOf(packageName).any { signedWith(packageName, bytes(it)) }
    }

    operator fun plus(more: Map<String, Set<String>>): KnownHosts {
        if (more.isEmpty()) return this
        val all = HashMap(keys)
        for ((name, added) in more) all[name] = all[name].orEmpty() + added.mapNotNull(::tidy)
        return KnownHosts(all)
    }

    companion object {
        /**
         * Reads Google's file: `<signature package="…">` entries, each with `<key>` elements
         * holding a certificate's SHA-256 as hex pairs with colons between. Anything else in
         * it is passed over, and a key that isn't 64 hex digits is left out (so a damaged
         * entry admits nobody, rather than somebody).
         */
        fun parse(parser: XmlPullParser): KnownHosts {
            val keys = HashMap<String, MutableSet<String>>()
            var packageName: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "signature" -> packageName = parser.getAttributeValue(null, "package")?.trim()?.ifEmpty { null }
                        "key" -> {
                            val key = tidy(parser.nextText())
                            val name = packageName
                            if (key != null && name != null) keys.getOrPut(name) { HashSet() } += key
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "signature") packageName = null
                }
                event = parser.next()
            }
            return KnownHosts(keys)
        }

        /** A certificate's SHA-256 as 64 lower-case hex digits, however it was written
         *  (colons, spaces, new lines, capitals); null if it isn't one. */
        fun tidy(key: String): String? =
            key.filterNot { it == ':' || it.isWhitespace() }.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

        /** The SHA-256 of a certificate's bytes as lower-case hex pairs with colons between,
         *  as Google's list writes a key (which [tidy] reads back). */
        fun fingerprint(certificate: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02x".format(it) }

        fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/**
 * Who is calling, as far as the phone can say: for the playback service's session and for
 * the cover provider, which let in the same callers and no others.
 *
 * A caller is let in if it is this app; or Android trusts it to control media (the system
 * itself, an app allowed to read notifications, an app holding the system's permission to
 * control media: what Media3 calls trusted); or it is one of the [KnownHosts], by package
 * name and signing certificate. The last is what makes sure the real Android Auto is never
 * turned away on a phone where it happens to hold neither of the second.
 */
class Callers(private val phone: Phone, private val hosts: KnownHosts, private val more: () -> Map<String, Set<String>> = { emptyMap() }) {
    constructor(context: Context, hosts: KnownHosts, more: () -> Map<String, Set<String>> = { emptyMap() }) : this(AndroidPhone(context.applicationContext), hosts, more)

    /** What the system says about a caller. Android behind it in the app; in tests, a stand-in. */
    interface Phone {
        val ownUid: Int

        /** The packages that really are the calling uid. */
        fun packagesOf(uid: Int): List<String>

        /** Whether the package is signed with the certificate that has this SHA-256 (now, or
         *  earlier in a history of certificates the system has verified). */
        fun signedWith(packageName: String, sha256: ByteArray): Boolean

        /** Whether Android trusts the caller to control media. */
        fun trustedForMedia(packageName: String, pid: Int, uid: Int): Boolean

        /** Has `changed` told, from now on, the name of each app that is installed, updated
         *  or removed. */
        fun onPackageChanged(changed: (packageName: String) -> Unit) {}

        /** How the package is signed now, as the system says; null if it can't say. */
        fun signing(packageName: String): Signing? = null
    }

    /** The certificates an app is signed with now (each one's bytes; more than one only for
     *  an app with several signers), and whether it carries a history of certificates it
     *  has rotated from. */
    class Signing(val certificates: List<ByteArray>, val history: Boolean)

    private class AndroidPhone(private val app: Context) : Phone {
        private val packages: PackageManager = app.packageManager
        override val ownUid: Int get() = android.os.Process.myUid()

        override fun packagesOf(uid: Int): List<String> = packages.getPackagesForUid(uid)?.toList().orEmpty()

        // The system's own answer, which follows a rotated certificate back through the
        // history it has verified: what Google's sample does by hand with the certificate's
        // bytes, and its list names Android Auto's old and rotated keys both.
        override fun signedWith(packageName: String, sha256: ByteArray): Boolean =
            packages.hasSigningCertificate(packageName, sha256, PackageManager.CERT_INPUT_SHA256)

        // Asked of the system the way Media3 asks it for a controller.
        override fun trustedForMedia(packageName: String, pid: Int, uid: Int): Boolean =
            uid == android.os.Process.SYSTEM_UID ||
                app.getSystemService(MediaSessionManager::class.java).isTrustedForMediaControl(MediaSessionManager.RemoteUserInfo(packageName, pid, uid))

        override fun signing(packageName: String): Signing? = try {
            @Suppress("DEPRECATION")
            val info = packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val now = info?.apkContentsSigners?.takeIf { it.isNotEmpty() }
            if (info == null || now == null) null else Signing(now.map { it.toByteArray() }, !info.hasMultipleSigners() && info.hasPastSigningCertificates())
        } catch (_: Exception) {
            // (Not installed, or the package manager couldn't answer: not known.)
            null
        }

        // The system's own word that an app has come, changed or gone. (It tells an app only
        // about apps it may see: the known hosts are named in the manifest's <queries>.)
        override fun onPackageChanged(changed: (packageName: String) -> Unit) {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
                addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
                addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
                addAction(android.content.Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: android.content.Intent) {
                    intent.data?.schemeSpecificPart?.let(changed)
                }
            }
            try {
                androidx.core.content.ContextCompat.registerReceiver(app, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (_: RuntimeException) {
                // Never told, then: nothing is remembered wrongly for longer than this run.
            }
        }
    }

    /**
     * What was worked out for a caller, by uid and name. Kept until the system says that app
     * has been installed, updated or removed: an update can change what it is signed with
     * (Android Auto's stub replaced by the real one, on the same uid), and a name can pass to
     * another app. It used to be kept for the life of the process. (Not kept at all when
     * there are extra hosts, which a debug build can be given at any time.)
     */
    private val known = ConcurrentHashMap<String, Boolean>()

    init {
        phone.onPackageChanged { name -> known.keys.removeAll { it.substringAfter('/') == name } }
    }

    /** How many apps are listed as known hosts. */
    val listed: Int get() = (hosts + more()).packages.size

    /** Whether the caller is a known host: its package name and its certificate both. */
    fun knownHost(packageName: String?, uid: Int): Boolean {
        if (packageName == null) return false
        val extra = more()
        val list = hosts + extra
        if (list.keysOf(packageName).isEmpty()) return false
        val key = "$uid/$packageName"
        if (extra.isEmpty()) known[key]?.let { return it }
        // What the system could not say is "no" for now, and is asked again next time: a
        // lookup that fails (the package manager restarting, the app half-way through an
        // update) or a uid it knows nothing of is not an answer about the app.
        val ofCaller = try {
            phone.packagesOf(uid)
        } catch (_: RuntimeException) {
            return false
        }
        if (ofCaller.isEmpty()) return false
        val result = try {
            list.matches(packageName, ofCaller, phone::signedWith)
        } catch (_: RuntimeException) {
            return false
        }
        if (extra.isEmpty()) known[key] = result
        return result
    }

    /**
     * Whether the package name a caller gave is its own. On Android 13 and later the system
     * has checked it against the calling uid before the app is told (`checkedBySystem`). On
     * Android 12, over the platform's own media-session transport, it has not: the name
     * is whatever the caller wrote, and Media3's "trusted" then looks that name up among the
     * apps with notification access, by name alone. So there the name is only taken if the
     * system says the calling uid is that package: the same binding the browser path gets
     * from Android itself. This app and the system are who their uid says they are.
     */
    fun namedTruly(packageName: String?, uid: Int, checkedBySystem: Boolean): Boolean {
        if (checkedBySystem || uid == phone.ownUid || uid == android.os.Process.SYSTEM_UID) return true
        if (packageName == null) return false
        return try {
            packageName in phone.packagesOf(uid)
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Whether Android trusts the caller to control media. */
    fun trusted(packageName: String?, pid: Int, uid: Int): Boolean {
        if (packageName == null) return false
        return try {
            phone.trustedForMedia(packageName, pid, uid)
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * Who a refused caller is signed by, for Diagnostics: a line for each certificate its
     * package is signed with now, beside its name, as the SHA-256 in hex pairs with colons
     * between (the form Google's list of known hosts is written in), and whether it has a
     * history of certificates rotated from. It is what letting an app in by package and
     * certificate, as the known hosts are, needs to be known; it lets nobody in. A signing
     * certificate's fingerprint is public (it is in every copy of the app, and the same on
     * every phone) and identifies no person: only whoever publishes the app.
     *
     * Only for a name the system says is the caller's own: the certificate of a package the
     * caller only named would say nothing about the caller.
     */
    fun signedBy(packageName: String?, uid: Int): List<String> {
        if (packageName == null) return emptyList()
        val who = "refused caller $packageName"
        val own = try {
            packageName in phone.packagesOf(uid)
        } catch (_: RuntimeException) {
            false
        }
        if (!own) return listOf("$who: its signing certificate not looked up (the system doesn't say the name is the caller's)")
        val signing = try {
            phone.signing(packageName)
        } catch (_: RuntimeException) {
            null
        } ?: return listOf("$who: the system couldn't say what it is signed with")
        val history = "signing history: ${if (signing.history) "yes" else "no"}"
        val all = signing.certificates
        return all.mapIndexed { i, certificate ->
            val which = if (all.size > 1) "certificate ${i + 1} of ${all.size}, " else ""
            "$who is signed with ${which}SHA-256 ${KnownHosts.fingerprint(certificate)}; $history"
        }
    }

    /** How a caller that isn't the session's (the cover provider's) stands. */
    fun standing(packageName: String?, pid: Int, uid: Int): Standing = Standing(
        own = uid == phone.ownUid,
        trusted = trusted(packageName, pid, uid),
        knownHost = knownHost(packageName, uid),
    )

    data class Standing(val own: Boolean, val trusted: Boolean, val knownHost: Boolean) {
        val admitted: Boolean get() = own || trusted || knownHost
    }
}

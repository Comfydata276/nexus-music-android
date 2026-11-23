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
    }

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
    }

    /** What was worked out for a caller, by uid and name: an app's certificate doesn't
     *  change while it keeps its uid. (Not kept when there are extra hosts, which a debug
     *  build can be given at any time.) */
    private val known = ConcurrentHashMap<String, Boolean>()

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
        val result = try {
            list.matches(packageName, phone.packagesOf(uid), phone::signedWith)
        } catch (_: RuntimeException) {
            false
        }
        if (extra.isEmpty()) known[key] = result
        return result
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

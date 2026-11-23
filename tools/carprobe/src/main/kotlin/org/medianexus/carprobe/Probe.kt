package org.medianexus.carprobe

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Connects to another app's media service the way Android Auto does, uses it the way a
 * car would, and says what worked.
 *
 * Android Auto is, to a media app, a `MediaBrowserCompat` and a `MediaControllerCompat`
 * living in another app: it finds the service, asks for the root with a set of hints,
 * lists children, loads artwork through content addresses, searches, and sends transport
 * commands, among them "play from search", which is how a spoken request arrives. This
 * does each of those from its own process and checks the answers against what the car
 * needs and what docs/DESIGN.md promises.
 *
 * It runs as an instrumentation so that one `am instrument` command starts it, prints its
 * findings and ends; scripts/carcheck wraps that. What it cannot tell you (how Google
 * draws the result, what the Assistant hears) is set out in docs/TESTING.md.
 */
class Probe : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var target: String
    private val results = mutableListOf<JSONObject>()
    private var limitMs = 10_000L

    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    @Volatile private var state: PlaybackStateCompat? = null
    @Volatile private var metadata: MediaMetadataCompat? = null
    @Volatile private var queue: List<MediaSessionCompat.QueueItem>? = null
    @Volatile private var stateChanges = 0
    // Some errors are shown for a moment and withdrawn at once (Media3 does this for errors
    // that do not stop playback), so the last one seen is kept, with when it was seen.
    @Volatile private var lastError: String? = null
    @Volatile private var lastErrorAt = 0L

    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val out = Bundle()
        try {
            target = args.getString("target") ?: throw IllegalArgumentException("no target package given")
            limitMs = args.getString("limit_ms")?.toLong() ?: limitMs
            run()
        } catch (e: Throwable) {
            record("probe", "FAIL", "The probe itself", "stopped early: $e\n" + e.stackTrace.take(6).joinToString("\n") { "    at $it" })
        }
        runCatching { onMain { browser?.disconnect() } }
        val failed = results.count { it.getString("status") == "FAIL" }
        val warned = results.count { it.getString("status") == "WARN" }
        val passed = results.count { it.getString("status") == "PASS" }
        val report = JSONObject().put("target", args.getString("target")).put("passed", passed)
            .put("failed", failed).put("warnings", warned).put("results", JSONArray(results))
        out.putString(REPORT_KEY_STREAMRESULT, "\nRESULT: $passed passed, $failed failed, $warned warnings\nPROBE_JSON:$report\n")
        finish(Activity.RESULT_OK, out)
    }

    // --- the run ---------------------------------------------------------------------------

    private fun run() {
        val mode = args.getString("mode") ?: "full"
        say("Car probe against $target (mode: $mode)")
        val trusted = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true
        record("probe.trusted", "INFO", "Connecting as",
            if (trusted) "a trusted caller, as Android Auto is (this probe has notification access)"
            else "an untrusted caller: an ordinary app with no special access. Android Auto is not one of these.")
        val service = discover() ?: return
        if (mode == "play" || mode == "pause") {
            // For scripts/measure: start (or stop) whatever the app would resume, nothing else.
            if (!connect(service, "connect", "Connects")) return
            if (mode == "pause") { quiet(); return }
            // "-e media_id <id>": choose that item, as a car does from a list; otherwise
            // just press play.
            val chosen = args.getString("media_id")
            onMain { if (chosen != null) controls.playFromMediaId(chosen, null) else controls.play() }
            val ms = waitFor { playing() }
            check("play", if (chosen != null) "Choosing an item starts playback" else "Play with nothing chosen starts playback", ms >= 0, whyNotPlaying(),
                "now ${nowPlaying()}; the car is shown a queue of ${queue?.size}", ms)
            return
        }
        if (args.getString("cold") != "false") {
            shell("am force-stop $target")
            SystemClock.sleep(500)
        }
        if (mode == "outsider") {
            outsider(service, trusted)
            return
        }
        if (!connect(service, "connect.cold", "Starts cold and connects, as when a car asks first")) return
        val root = browser!!.root
        val rootExtras = browser!!.extras
        say("  root: \"$root\"  extras: ${describe(rootExtras)}")

        if (mode == "signed_out") {
            signedOut(root)
            return
        }

        check("root.search", "Says it can search (the car shows a search button)",
            rootExtras?.getBoolean(SEARCH_SUPPORTED) == true,
            "root extras lack $SEARCH_SUPPORTED=true")

        val tree = browse(root)
        artwork(tree)
        search(tree)
        if (mode == "browse") return
        playback(tree)
        voice(tree)
        interruptions()
        resume(service)
    }

    /** What Android Auto looks for before it will list an app at all. */
    private fun discover(): ComponentName? {
        val pm = context.packageManager
        val found = pm.queryIntentServices(Intent(BROWSER_SERVICE).setPackage(target), PackageManager.GET_META_DATA)
        if (found.isEmpty()) {
            // An app with no such service is invisible to this probe, as it is to Android
            // Auto, so "not installed" and "nothing for a car" look the same from here;
            // scripts/carcheck has already made sure the app is installed.
            record("discover.service", "FAIL", "Has a media browser service the car can find",
                "no service in $target answers $BROWSER_SERVICE, so Android Auto cannot see the app at all")
            return null
        }
        val info = found[0].serviceInfo
        check("discover.service", "Has a media browser service the car can find", info.exported,
            "${info.name} is not exported, so no other app can connect", "${info.name}")

        val app = pm.getApplicationInfo(target, PackageManager.GET_META_DATA)
        val descId = app.metaData?.getInt(CAR_APPLICATION, 0) ?: 0
        var usesMedia = false
        if (descId != 0) {
            val parser = pm.getResourcesForApplication(app).getXml(descId)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "uses" &&
                    parser.getAttributeValue(null, "name") == "media") usesMedia = true
            }
        }
        check("discover.declared", "Declares itself to Android Auto as a media app", usesMedia,
            if (descId == 0) "the manifest's <application> has no $CAR_APPLICATION meta-data; Android Auto will not list the app"
            else "the automotive description lacks <uses name=\"media\"/>")
        val icon = app.metaData?.getInt(CAR_NOTIFICATION_ICON, 0) ?: 0
        record("discover.icon", if (icon != 0) "PASS" else "INFO", "Names a small icon for the car",
            if (icon != 0) "" else "no $CAR_NOTIFICATION_ICON meta-data; the car falls back to the app icon")
        return ComponentName(info.packageName, info.name)
    }

    private fun connect(service: ComponentName, id: String, title: String, recent: Boolean = false, expectRefusal: Boolean = false): Boolean {
        // The hints Android Auto sends when it asks for the root.
        val hints = Bundle().apply {
            putInt(ROOT_CHILDREN_LIMIT, 4)
            putInt(ROOT_CHILDREN_SUPPORTED_FLAGS, MediaItem.FLAG_BROWSABLE)
            putInt(MEDIA_ART_SIZE_HINT, 512)
            putInt(CUSTOM_BROWSER_ACTION_LIMIT, 2)
            if (recent) putBoolean(EXTRA_RECENT, true)
        }
        val connected = CompletableFuture<String>()
        val t0 = SystemClock.elapsedRealtime()
        onMain {
            browser = MediaBrowserCompat(context, service, object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() { connected.complete("") }
                override fun onConnectionFailed() { connected.complete("the service refused the connection (onGetRoot returned nothing?)") }
                override fun onConnectionSuspended() { connected.complete("the connection was suspended") }
            }, hints).also { it.connect() }
        }
        val error = runCatching { connected.get(limitMs, TimeUnit.MILLISECONDS) }.getOrElse { "no answer within ${limitMs / 1000}s" }
        val ms = SystemClock.elapsedRealtime() - t0
        if (error.isNotEmpty()) {
            record(id, if (expectRefusal) "INFO" else "FAIL", title, error, ms)
            return false
        }
        record(id, "PASS", title, "", ms)
        onMain {
            controller = MediaControllerCompat(context, browser!!.sessionToken).also { c ->
                state = c.playbackState
                metadata = c.metadata
                queue = c.queue
                c.registerCallback(object : MediaControllerCompat.Callback() {
                    override fun onPlaybackStateChanged(s: PlaybackStateCompat?) {
                        state = s
                        stateChanges++
                        s?.errorMessage?.toString()?.takeIf { it.isNotBlank() }?.let { lastError = it; lastErrorAt = SystemClock.elapsedRealtime() }
                    }
                    override fun onMetadataChanged(m: MediaMetadataCompat?) { metadata = m }
                    override fun onQueueChanged(q: MutableList<MediaSessionCompat.QueueItem>?) { queue = q }
                })
            }
        }
        return true
    }

    // --- browsing --------------------------------------------------------------------------

    private class Node(val item: MediaItem?, val id: String, val depth: Int, val path: String) {
        var children: List<MediaItem>? = null
        var error: String? = null
        var ms = 0L
    }

    private fun children(parent: String): Triple<List<MediaItem>?, String?, Long> {
        val done = CompletableFuture<Pair<List<MediaItem>?, String?>>()
        val t0 = SystemClock.elapsedRealtime()
        val callback = object : MediaBrowserCompat.SubscriptionCallback() {
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) { done.complete(children.toList() to null) }
            override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>, options: Bundle) { done.complete(children.toList() to null) }
            override fun onError(parentId: String) { done.complete(null to "the service reported an error") }
            override fun onError(parentId: String, options: Bundle) { done.complete(null to "the service reported an error") }
        }
        onMain { browser!!.subscribe(parent, callback) }
        val answer = runCatching { done.get(limitMs, TimeUnit.MILLISECONDS) }.getOrElse { null to "no answer within ${limitMs / 1000}s" }
        onMain { browser!!.unsubscribe(parent, callback) }
        return Triple(answer.first, answer.second, SystemClock.elapsedRealtime() - t0)
    }

    /** How many children a folder has when asked for a page at a time. */
    private fun pagedCount(parent: String, pageSize: Int = 100): Int {
        var total = 0
        var page = 0
        while (page < 400) {
            val done = CompletableFuture<Int>()
            val options = Bundle().apply {
                putInt(MediaBrowserCompat.EXTRA_PAGE, page)
                putInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, pageSize)
            }
            val callback = object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>, options: Bundle) { done.complete(children.size) }
                override fun onChildrenLoaded(parentId: String, children: MutableList<MediaItem>) { done.complete(children.size) }
                override fun onError(parentId: String, options: Bundle) { done.complete(0) }
                override fun onError(parentId: String) { done.complete(0) }
            }
            onMain { browser!!.subscribe(parent, options, callback) }
            val got = runCatching { done.get(limitMs, TimeUnit.MILLISECONDS) }.getOrDefault(0)
            onMain { browser!!.unsubscribe(parent, callback) }
            total += got
            if (got < pageSize) break
            page++
        }
        return total
    }

    private fun browse(root: String): List<Node> {
        val maxDepth = args.getString("depth")?.toInt() ?: 4
        val maxNodes = args.getString("nodes")?.toInt() ?: 80
        val nodes = mutableListOf<Node>()
        val pending = ArrayDeque(listOf(Node(null, root, 0, "")))
        var skipped = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (nodes.size >= maxNodes) { skipped++; continue }
            val (kids, error, ms) = children(node.id)
            node.children = kids
            node.error = error
            node.ms = ms
            nodes += node
            if (node.depth < maxDepth) {
                kids?.filter { it.isBrowsable && it.mediaId != null }?.forEach {
                    pending += Node(it, it.mediaId!!, node.depth + 1, node.path + "/" + (it.description.title ?: "?"))
                }
            }
        }

        // The tabs.
        val tabs = nodes[0].children.orEmpty()
        say("  tabs: " + tabs.joinToString { "\"${it.description.title}\"" })
        check("browse.tabs", "Offers one to four tabs, each a folder", tabs.isNotEmpty() && tabs.size <= 4 && tabs.all { it.isBrowsable },
            when {
                nodes[0].error != null -> "the root did not load: ${nodes[0].error}"
                tabs.isEmpty() -> "the root has no children, so the car shows an empty screen"
                tabs.size > 4 -> "${tabs.size} root items; the car shows four at most and drops the rest"
                else -> "not folders: " + tabs.filter { !it.isBrowsable }.joinToString { "\"${it.description.title}\"" } + "; the car only makes tabs of browsable items"
            }, "${tabs.size} tabs", nodes[0].ms)
        val bare = tabs.filter { it.description.iconUri == null && it.description.iconBitmap == null }
        check("browse.tab_icons", "Every tab has an icon", bare.isEmpty() && tabs.isNotEmpty(),
            "no icon: " + bare.joinToString { "\"${it.description.title}\"" }, warnOnly = true)
        args.getString("expect_tabs")?.let { want ->
            val have = tabs.map { it.description.title.toString() }
            check("browse.tab_names", "The tabs are the ones the design names", have == want.split(","), "expected $want, found ${have.joinToString(",")}")
        }

        // Everything below them.
        val failed = nodes.filter { it.error != null }
        val slowest = nodes.maxByOrNull { it.ms }
        check("browse.loads", "Every folder opens within ${limitMs / 1000}s", failed.isEmpty(),
            failed.joinToString("; ") { "\"${it.path.ifEmpty { "(root)" }}\": ${it.error}" },
            "${nodes.size} folders opened" + (if (skipped > 0) ", $skipped more not visited" else "") +
                "; slowest ${slowest?.ms} ms (\"${slowest?.path?.ifEmpty { "(root)" }}\")")
        val items = nodes.flatMap { n -> n.children.orEmpty().map { n to it } }
        val untitled = items.filter { it.second.description.title.isNullOrBlank() }
        check("browse.titles", "Every item has a title", untitled.isEmpty(),
            "${untitled.size} without a title, e.g. id \"${untitled.firstOrNull()?.second?.mediaId}\" in \"${untitled.firstOrNull()?.first?.path}\"")
        val clashes = nodes.mapNotNull { n ->
            val ids = n.children.orEmpty().map { it.mediaId }
            if (ids.any { it.isNullOrEmpty() } || ids.toSet().size != ids.size) n.path.ifEmpty { "(root)" } else null
        }
        check("browse.ids", "Every item has its own id", clashes.isEmpty(), "missing or repeated ids in: " + clashes.joinToString())
        val playable = items.count { it.second.isPlayable }
        check("browse.playable", "There is something to play", playable > 0, "no playable item in the ${nodes.size} folders visited", "$playable playable items seen")
        val empty = nodes.filter { it.error == null && it.children.isNullOrEmpty() }
        record("browse.empty", if (empty.isEmpty()) "PASS" else "WARN", "No folder is empty",
            if (empty.isEmpty()) "" else "empty (the car says \"No items\"): " + empty.joinToString { "\"${it.path}\"" })

        // What arrives whole. A car asks for a folder in one go, and what is sent back has
        // a size limit (Media3 cuts the list to fit, silently): a folder that is longer when
        // asked for a page at a time than when asked for whole has lost its tail.
        val long = nodes.filter { (it.children?.size ?: 0) >= 40 }
        val cut = long.mapNotNull { n ->
            val whole = n.children!!.size
            val paged = pagedCount(n.id)
            if (paged > whole) "\"${n.path}\" shows $whole of $paged" else null
        }
        check("browse.complete", "Every folder arrives whole when asked for in one go", cut.isEmpty(),
            "cut short, with no way to the rest: " + cut.joinToString("; "),
            "${long.size} folders of 40 or more items checked; the longest has ${nodes.maxOfOrNull { it.children?.size ?: 0 }}")

        // How the car is asked to lay things out.
        val styled = nodes.filter { n ->
            val e = n.item?.description?.extras
            e != null && (e.containsKey(STYLE_BROWSABLE) || e.containsKey(STYLE_PLAYABLE) || e.containsKey(STYLE_SINGLE))
        }
        val rootStyled = browser!!.extras?.let { it.containsKey(STYLE_BROWSABLE) || it.containsKey(STYLE_PLAYABLE) } == true
        val singles = items.count { it.second.description.extras?.containsKey(STYLE_SINGLE) == true }
        record("browse.styles", if (rootStyled || styled.isNotEmpty() || singles > 0) "PASS" else "WARN", "Says how lists and grids should look",
            if (rootStyled || styled.isNotEmpty() || singles > 0)
                "root: ${styleOf(browser!!.extras)}; folders with their own style: ${styled.size}; items with their own style: $singles"
            else "no content style hints anywhere; the car will draw everything as plain lists")

        // The tree, for whoever reads the report.
        say("  tree (first levels):")
        for (n in nodes.take(30)) {
            val kids = n.children.orEmpty()
            val style = n.item?.description?.extras?.let { styleOf(it) }?.takeIf { it != "-" }?.let { "  [$it]" } ?: ""
            say("    ${"  ".repeat(n.depth)}${n.path.substringAfterLast('/').ifEmpty { "(root)" }}  ${kids.count { it.isBrowsable }} folders, ${kids.count { it.isPlayable }} playable, ${n.ms} ms$style")
        }
        return nodes
    }

    private fun styleOf(extras: Bundle?): String {
        if (extras == null) return "-"
        fun name(v: Int) = when (v) { 1 -> "list"; 2 -> "grid"; 3 -> "category list"; 4 -> "category grid"; else -> "?$v" }
        val parts = mutableListOf<String>()
        if (extras.containsKey(STYLE_BROWSABLE)) parts += "folders as " + name(extras.getInt(STYLE_BROWSABLE))
        if (extras.containsKey(STYLE_PLAYABLE)) parts += "playables as " + name(extras.getInt(STYLE_PLAYABLE))
        if (extras.containsKey(STYLE_SINGLE)) parts += "this item as " + name(extras.getInt(STYLE_SINGLE))
        extras.getString(GROUP_TITLE)?.let { parts += "group \"$it\"" }
        return parts.joinToString(", ").ifEmpty { "-" }
    }

    // --- artwork ---------------------------------------------------------------------------

    /** Null if another app (this one) can read the picture at [uri]; otherwise why not. */
    private fun unreadable(uri: Uri): String? {
        try {
            if (uri.scheme == "android.resource") {
                // An icon inside the app: usually a vector, which is loaded as a drawable, not decoded.
                val resources = context.packageManager.getResourcesForApplication(uri.authority ?: "")
                val path = uri.pathSegments
                val id = if (path.size == 1) path[0].toIntOrNull() ?: 0 else resources.getIdentifier(path.last(), path.first(), uri.authority)
                return if (id != 0 && resources.getDrawable(id, null) != null) null else "no such drawable in ${uri.authority}"
            }
            val stream = context.contentResolver.openInputStream(uri) ?: return "the provider returned nothing"
            return stream.use {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(it, null, bounds)
                if (bounds.outWidth > 0) null else "not a picture"
            }
        } catch (e: Throwable) {
            return "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun artwork(tree: List<Node>) {
        val items = tree.flatMap { it.children.orEmpty() }
        val uris = items.mapNotNull { it.description.iconUri }.distinct()
        val withArt = items.count { it.description.iconUri != null || it.description.iconBitmap != null }
        record("art.present", if (withArt > 0) "PASS" else "WARN", "Items carry artwork",
            "$withArt of ${items.size} items have a picture (${uris.size} different addresses)")
        // The car cannot fetch from the web or read the app's files: only content and
        // resource addresses work.
        val wrong = uris.filter { it.scheme != "content" && it.scheme != "android.resource" }
        check("art.addresses", "Artwork addresses are ones the car can open", wrong.isEmpty(),
            "${wrong.size} of ${uris.size} are neither content:// nor android.resource://, e.g. ${wrong.firstOrNull()}")
        val sample = uris.filter { it !in wrong }.take(args.getString("art")?.toInt() ?: 40)
        val t0 = SystemClock.elapsedRealtime()
        val bad = sample.mapNotNull { uri -> unreadable(uri)?.let { uri to it } }
        val ms = SystemClock.elapsedRealtime() - t0
        if (sample.isNotEmpty()) {
            check("art.readable", "Another app can read the artwork", bad.isEmpty(),
                "${bad.size} of ${sample.size} could not be read, e.g. ${bad.firstOrNull()?.first}: ${bad.firstOrNull()?.second}",
                "${sample.size} pictures read, ${ms / sample.size} ms each on average", ms)
        }
    }

    // --- search ----------------------------------------------------------------------------

    private fun searchFor(query: String): Pair<List<MediaItem>?, String?> {
        val done = CompletableFuture<Pair<List<MediaItem>?, String?>>()
        onMain {
            browser!!.search(query, null, object : MediaBrowserCompat.SearchCallback() {
                override fun onSearchResult(q: String, extras: Bundle?, items: MutableList<MediaItem>) { done.complete(items.toList() to null) }
                override fun onError(q: String, extras: Bundle?) { done.complete(null to "the service reported an error") }
            })
        }
        return runCatching { done.get(limitMs, TimeUnit.MILLISECONDS) }.getOrElse { null to "no answer within ${limitMs / 1000}s" }
    }

    /** A word to search for when the caller gave none: one from a song the tree showed. */
    private fun aWord(tree: List<Node>): String? =
        tree.flatMap { songs(tree, it) }
            .flatMap { it.description.title.toString().split(" ") }.firstOrNull { it.length >= 4 && it.all(Char::isLetter) }

    /**
     * The songs in a folder: its playable items, less any that are an action rather than a
     * song. An app may head every list with a "Shuffle" or "Play all" item; one is told from
     * a song by its title heading three or more folders.
     */
    private fun songs(tree: List<Node>, node: Node): List<MediaItem> {
        val heads = tree.mapNotNull { n -> n.children.orEmpty().firstOrNull()?.takeIf { it.isPlayable }?.description?.title?.toString() }
            .groupingBy { it }.eachCount().filterValues { it >= 3 }.keys
        return node.children.orEmpty().filter { it.isPlayable && it.description.title?.toString() !in heads }
    }

    private fun search(tree: List<Node>) {
        val query = args.getString("query") ?: aWord(tree)
        if (query == null) {
            record("search.finds", "SKIP", "Search finds things", "no query given and no word to borrow from the tree")
            return
        }
        val t0 = SystemClock.elapsedRealtime()
        val (found, error) = searchFor(query)
        check("search.finds", "Search finds things", !found.isNullOrEmpty(),
            error ?: "nothing found for \"$query\"",
            "\"$query\": ${found?.size} results, first \"${found?.firstOrNull()?.description?.title}\"", SystemClock.elapsedRealtime() - t0)
        if (!found.isNullOrEmpty()) {
            val odd = found.filter { it.description.title.isNullOrBlank() || (!it.isPlayable && !it.isBrowsable) }
            check("search.results", "Search results can be shown and chosen", odd.isEmpty(), "${odd.size} results lack a title or are neither playable nor a folder")
        }
        val (none, noneError) = searchFor("zzqx no such thing qxzz")
        check("search.nothing", "A search with no match answers with an empty list", none != null && none.isEmpty(),
            noneError ?: "${none?.size} results for nonsense", warnOnly = true)
    }

    // --- playing ---------------------------------------------------------------------------

    private val controls get() = controller!!.transportControls
    private fun playing() = state?.state == PlaybackStateCompat.STATE_PLAYING
    private fun title() = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: metadata?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
    private fun mediaId() = metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
    private fun nowPlaying() = "\"${title()}\" by \"${metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)}\""

    private fun position(): Long {
        val s = state ?: return -1
        if (s.state != PlaybackStateCompat.STATE_PLAYING) return s.position
        return s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
    }

    private fun waitFor(ms: Long = limitMs, what: () -> Boolean): Long {
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < ms) {
            if (what()) return SystemClock.elapsedRealtime() - t0
            SystemClock.sleep(40)
        }
        return -1
    }

    private fun whyNotPlaying(): String {
        val s = state
        return "state is ${stateName(s?.state)}" + (s?.errorMessage?.let { ", error \"$it\" (code ${s.errorCode})" } ?: "")
    }

    private fun quiet() {
        if (state?.state == PlaybackStateCompat.STATE_PLAYING || state?.state == PlaybackStateCompat.STATE_BUFFERING) {
            onMain { controls.pause() }
            waitFor(4000) { !playing() }
        }
    }

    private fun playback(tree: List<Node>) {
        // A song that sits among others, so there is a queue to look at afterwards.
        val folder = tree.filter { n -> songs(tree, n).size >= 3 }.maxByOrNull { it.depth }
            ?: tree.firstOrNull { n -> songs(tree, n).isNotEmpty() }
        val pick = folder?.let { songs(tree, it).firstOrNull() }
        if (pick == null) {
            record("play.item", "SKIP", "Choosing a song plays it", "nothing playable was found while browsing")
            return
        }
        onMain { controls.playFromMediaId(pick.mediaId, null) }
        var ms = waitFor { playing() }
        check("play.item", "Choosing a song plays it", ms >= 0, whyNotPlaying(),
            "\"${pick.description.title}\" from \"${folder.path}\" -> now playing ${nowPlaying()}", ms)
        if (ms < 0) return
        SystemClock.sleep(1200)

        val audio = context.getSystemService(AudioManager::class.java)
        check("play.sound", "Sound is really coming out", audio.isMusicActive, "the session says playing but the system reports no music playing")
        val service = shell("dumpsys activity services $target")
        check("play.foreground", "Plays as a foreground service with a notification", service.contains("isForeground=true"),
            "no service of $target is in the foreground; the system may stop playback when the screen is off", warnOnly = true)

        val m = metadata
        val missing = listOf("title" to title(), "artist" to m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
            "album" to m?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)).filter { it.second.isNullOrBlank() }.map { it.first }
        val duration = m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0
        waitFor(3000) { (metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0) > 0 }
        check("now.details", "Now playing has title, artist, album and length",
            missing.isEmpty() && (metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0) > 0,
            (if (missing.isNotEmpty()) "missing: ${missing.joinToString()}; " else "") + "length ${metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)} ms",
            "${nowPlaying()}, album \"${m?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)}\", ${duration / 1000}s")
        nowPlayingArt()

        // What the car's buttons can do.
        val actions = state?.actions ?: 0
        val needed = listOf("pause" to (PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE),
            "next" to PlaybackStateCompat.ACTION_SKIP_TO_NEXT, "previous" to PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            "seek" to PlaybackStateCompat.ACTION_SEEK_TO, "play a chosen item" to PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID,
            "play from search (voice)" to PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
        val absent = needed.filter { actions and it.second == 0L }.map { it.first }
        check("now.actions", "Offers pause, next, previous, seek, play-an-item and play-from-search", absent.isEmpty(), "not offered: ${absent.joinToString()}")

        val custom = state?.customActions.orEmpty()
        record("now.buttons", "INFO", "Extra buttons on the car's player",
            if (custom.isEmpty()) "none" else custom.joinToString { "\"${it.name}\" (${it.action}${if (it.icon == 0) ", NO ICON" else ""})" })
        args.getString("expect_buttons")?.let { want ->
            val names = custom.map { it.name.toString().lowercase() }
            val lacking = want.split(",").filter { w -> names.none { it.contains(w.lowercase()) } }
            check("now.buttons_expected", "The extra buttons the design names are there", lacking.isEmpty() && custom.all { it.icon != 0 },
                if (lacking.isNotEmpty()) "missing: ${lacking.joinToString()} (found: ${names.joinToString()})" else "a button has no icon")
        }
        if (args.getString("press_buttons") == "true") {
            for (action in custom) {
                val before = stateChanges
                onMain { controls.sendCustomAction(action, null) }
                val answered = waitFor(3000) { stateChanges != before }
                check("now.button." + action.action.substringAfterLast('.'), "Pressing \"${action.name}\" does something", answered >= 0,
                    "no change in the session within 3s",
                    "buttons now: " + state?.customActions.orEmpty().joinToString { "\"${it.name}\"" }, answered)
            }
        }

        // Pause and play.
        onMain { controls.pause() }
        ms = waitFor { state?.state == PlaybackStateCompat.STATE_PAUSED }
        check("transport.pause", "Pause pauses", ms >= 0, whyNotPlaying(), ms = ms)
        SystemClock.sleep(600)
        record("transport.pause_silent", if (!audio.isMusicActive) "PASS" else "WARN", "Paused means silent",
            if (!audio.isMusicActive) "" else "the system still reports music playing 0.6s after pausing")
        onMain { controls.play() }
        ms = waitFor { playing() }
        check("transport.play", "Play carries on", ms >= 0, whyNotPlaying(), ms = ms)

        // Seek.
        val length = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0
        if (length > 6000) {
            val to = minOf(length / 2, 10_000L)
            onMain { controls.seekTo(to) }
            ms = waitFor(5000) { position() in (to - 300)..(to + 2500) && playing() }
            check("transport.seek", "Seeking moves the position", ms >= 0, "asked for ${to} ms, position is ${position()} ms (${stateName(state?.state)})", "to ${to / 1000}s", ms)
        } else record("transport.seek", "SKIP", "Seeking moves the position", "the song is too short to tell (${length} ms)")

        // The queue.
        waitFor(2000) { !queue.isNullOrEmpty() }
        val q = queue.orEmpty()
        check("queue.shown", "The queue is offered to the car", q.size >= 2, if (q.isEmpty()) "no queue" else "the queue has only ${q.size} item",
            "${q.size} items, title \"${controller!!.queueTitle ?: ""}\"")
        if (q.isNotEmpty()) {
            val active = state?.activeQueueItemId
            check("queue.current", "The queue marks the song that is playing", q.any { it.queueId == active },
                "active queue item id $active is not in the queue")
        }

        // Next and previous.
        var before = title() to mediaId()
        onMain { controls.skipToNext() }
        ms = waitFor { (title() to mediaId()) != before && playing() }
        check("transport.next", "Next goes to the next song", ms >= 0, "still on ${nowPlaying()} (${stateName(state?.state)})", "now ${nowPlaying()}", ms)
        SystemClock.sleep(3500)
        before = title() to mediaId()
        onMain { controls.skipToPrevious() }
        // Either is right: back to the previous song, or back to the start of this one.
        ms = waitFor(5000) { ((title() to mediaId()) != before || position() < 2500) && playing() }
        check("transport.previous", "Previous goes back", ms >= 0, "still at ${position()} ms of ${nowPlaying()}",
            if ((title() to mediaId()) != before) "to the previous song" else "to the start of the song", ms)

        if (q.size >= 3 && (state?.actions ?: 0) and PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM != 0L) {
            val wanted = q[2]
            onMain { controls.skipToQueueItem(wanted.queueId) }
            ms = waitFor { state?.activeQueueItemId == wanted.queueId && playing() }
            check("queue.choose", "Choosing a song in the queue plays it", ms >= 0,
                "asked for \"${wanted.description.title}\", playing ${nowPlaying()}", "\"${wanted.description.title}\"", ms)
        } else if (q.size >= 3) {
            record("queue.choose", "FAIL", "Choosing a song in the queue plays it", "the session does not offer skip-to-queue-item")
        }

        // Shuffle and repeat, where the session offers them as standard actions.
        val a = state?.actions ?: 0
        if (a and PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE != 0L) {
            onMain { controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL) }
            ms = waitFor(3000) { controller!!.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL }
            check("transport.shuffle", "Shuffle can be switched on", ms >= 0, "shuffle mode stayed ${controller!!.shuffleMode}", ms = ms)
            onMain { controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE) }
            waitFor(3000) { controller!!.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE }
        } else record("transport.shuffle", "INFO", "Shuffle can be switched on", "not offered as a standard action")
        if (a and PlaybackStateCompat.ACTION_SET_REPEAT_MODE != 0L) {
            onMain { controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_ALL) }
            ms = waitFor(3000) { controller!!.repeatMode == PlaybackStateCompat.REPEAT_MODE_ALL }
            check("transport.repeat", "Repeat can be switched on", ms >= 0, "repeat mode stayed ${controller!!.repeatMode}", ms = ms)
            onMain { controls.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE) }
            waitFor(3000) { controller!!.repeatMode == PlaybackStateCompat.REPEAT_MODE_NONE }
        } else record("transport.repeat", "INFO", "Repeat can be switched on", "not offered as a standard action")

        // The end of a song: does the next one start by itself?
        val len = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0
        if (len > 8000 && queue.orEmpty().size >= 2 && queue.orEmpty().lastOrNull()?.queueId != state?.activeQueueItemId) {
            before = title() to mediaId()
            onMain { controls.seekTo(len - 2500) }
            ms = waitFor(9000) { (title() to mediaId()) != before && playing() }
            check("queue.runs_on", "When a song ends the next one starts", ms >= 0, "2.5s from the end of ${nowPlaying()}, nothing followed within 9s (${stateName(state?.state)})", "now ${nowPlaying()}", ms)
        } else record("queue.runs_on", "SKIP", "When a song ends the next one starts", "needs a song longer than 8s that is not the last in the queue")

        // "Shuffle" at the head of a list of songs, where the design promises one.
        args.getString("expect_shuffle")?.let { name ->
            val lists = tree.filter { n -> songs(tree, n).size >= 2 }
            fun headed(n: Node) = n.children.orEmpty().firstOrNull()?.let { it.isPlayable && it.description.title?.toString() == name } == true
            // A list too long for one folder is shown in parts: the head is on the list, not
            // on each part of it.
            fun partOfHeaded(n: Node) = tree.any { p -> p.depth == n.depth - 1 && n.path.startsWith(p.path + "/") && headed(p) }
            val without = lists.filter { !headed(it) && !partOfHeaded(it) }
            check("browse.shuffle", "Every list of songs is headed by \"$name\"", lists.isNotEmpty() && without.isEmpty(),
                if (lists.isEmpty()) "no list of songs was found" else "not headed by it: " + without.take(5).joinToString { "\"${it.path}\"" },
                "${lists.size} lists of songs")
            // The deepest such list of a few songs: an album or a playlist, which nothing
            // done earlier in this run (pressing "like") has changed.
            val list = lists.filter { headed(it) && songs(tree, it).size in 4..150 }.maxByOrNull { it.depth } ?: lists.firstOrNull { headed(it) }
            if (list != null) {
                // As the list is now: something done earlier in this run (pressing "like")
                // may have changed it since it was first read.
                val now = children(list.id).first.orEmpty()
                val titles = now.drop(1).filter { it.isPlayable }.map { it.description.title.toString() }
                onMain { controls.playFromMediaId(now.first().mediaId, null) }
                val started = waitFor { playing() && controller!!.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL }
                waitFor(3000) { (queue?.size ?: 0) == titles.size }
                check("play.shuffle", "Choosing \"$name\" plays the whole list, shuffled", started >= 0 && queue.orEmpty().size == titles.size &&
                    queue.orEmpty().map { it.description.title.toString() }.sorted() == titles.sorted(),
                    "${whyNotPlaying()}, shuffle mode ${controller!!.shuffleMode}, queue of ${queue?.size} for a list of ${titles.size}",
                    "\"${list.path}\": ${titles.size} songs, shuffled; now ${nowPlaying()}", started)
                // Leave the list as it was found: not shuffled.
                onMain { controls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE) }
                waitFor(3000) { controller!!.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_NONE }
            }
        }
    }

    private fun nowPlayingArt() {
        val m = metadata ?: return
        val bitmap = listOf(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, MediaMetadataCompat.METADATA_KEY_ART, MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { m.getBitmap(it) }
        val uri = listOf(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, MediaMetadataCompat.METADATA_KEY_ART_URI, MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { m.getString(it) }?.let(Uri::parse)
        when {
            bitmap != null -> record("now.art", "PASS", "Now playing has artwork the car can show", "a ${bitmap.width}x${bitmap.height} picture sent with the details")
            uri == null -> record("now.art", "WARN", "Now playing has artwork the car can show", "no picture and no address")
            uri.scheme != "content" && uri.scheme != "android.resource" ->
                record("now.art", "FAIL", "Now playing has artwork the car can show", "the address is $uri; the car can only open content:// or android.resource://")
            else -> check("now.art", "Now playing has artwork the car can show", unreadable(uri) == null, "$uri: ${unreadable(uri)}", "$uri")
        }
    }

    // --- voice -----------------------------------------------------------------------------

    /** The extras Google Assistant attaches to "play ..." for each kind of thing asked for. */
    private fun voiceExtras(kind: String, query: String, fields: Map<String, String>) = Bundle().apply {
        putString("query", query)
        when (kind) {
            "artist" -> { putString(FOCUS, "vnd.android.cursor.item/artist"); putString(ARTIST, fields["artist"] ?: query) }
            "album" -> { putString(FOCUS, "vnd.android.cursor.item/album"); putString(ALBUM, fields["album"] ?: query); fields["artist"]?.let { putString(ARTIST, it) } }
            "song" -> { putString(FOCUS, "vnd.android.cursor.item/audio"); putString(TITLE, fields["title"] ?: query); fields["artist"]?.let { putString(ARTIST, it) }; fields["album"]?.let { putString(ALBUM, it) } }
            "playlist" -> { putString(FOCUS, "vnd.android.cursor.item/playlist"); putString(PLAYLIST, fields["playlist"] ?: query) }
            "genre" -> { putString(FOCUS, "vnd.android.cursor.item/genre"); putString(GENRE, fields["genre"] ?: query) }
            else -> putString(FOCUS, "vnd.android.cursor.item/*")
        }
    }

    private fun voice(tree: List<Node>) {
        if ((state?.actions ?: 0) and PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH == 0L) {
            record("voice.offered", "FAIL", "Takes spoken requests", "the session does not offer play-from-search, so \"play ... on <app>\" cannot work")
            return
        }
        // Requests come as  kind:words[|field=value...][=>words expected in what plays]  separated by ';'.
        val spec = args.getString("voice") ?: aWord(tree)?.let { "any:$it=>$it" } ?: ""
        spec.split(";").filter { it.isNotBlank() }.forEachIndexed { n, one ->
            val expect = one.substringAfter("=>", "").trim()
            val head = one.substringBefore("=>").split("|")
            val kind = head[0].substringBefore(":", "any").trim()
            val query = head[0].substringAfter(":").trim()
            val fields = head.drop(1).associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
            quiet()
            onMain { controls.playFromSearch(query, voiceExtras(kind, query, fields)) }
            val ms = waitFor { playing() && (expect.isEmpty() || playingMatches(expect)) }
            check("voice.${n + 1}", "\"Play $query\" ($kind)" + if (expect.isNotEmpty()) " plays \"$expect\"" else " plays something", ms >= 0,
                if (playing()) "it played ${nowPlaying()}, album \"${metadata?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)}\"" else whyNotPlaying(),
                "now ${nowPlaying()}", ms)
        }
        // "Play music on <app>", with nothing named.
        quiet()
        onMain { controls.playFromSearch("", Bundle()) }
        var ms = waitFor { playing() }
        check("voice.unnamed", "\"Play music\" with nothing named plays something", ms >= 0, whyNotPlaying(), "now ${nowPlaying()}", ms)
        // Something that is not there: either a fallback plays or the car is told why not.
        quiet()
        val changes = stateChanges
        val asked = SystemClock.elapsedRealtime()
        onMain { controls.playFromSearch("zzqx no such thing qxzz", voiceExtras("any", "zzqx no such thing qxzz", emptyMap())) }
        ms = waitFor(6000) { playing() || lastErrorAt >= asked }
        record("voice.no_match", if (ms >= 0) "PASS" else "WARN", "A request for something that isn't there gets an answer",
            when {
                ms < 0 -> "nothing happened: nothing played and no message for the car to show" + if (stateChanges == changes) " (the session did not react at all)" else ""
                playing() -> "it fell back to playing ${nowPlaying()}"
                else -> "the car is told: \"$lastError\"" + if (state?.state != PlaybackStateCompat.STATE_ERROR) " (shown for a moment, then withdrawn)" else ""
            })
        quiet()
    }

    private fun playingMatches(expect: String): Boolean {
        val m = metadata ?: return false
        return listOf(title(), m.getString(MediaMetadataCompat.METADATA_KEY_ARTIST), m.getString(MediaMetadataCompat.METADATA_KEY_ALBUM),
            m.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST), controller?.queueTitle?.toString())
            .any { it?.contains(expect, ignoreCase = true) == true }
    }

    // --- interruptions, and coming back ----------------------------------------------------

    /** A navigation prompt or a call takes the sound away for a moment; then it comes back. */
    private fun interruptions() {
        onMain { controls.play() }
        if (waitFor { playing() } < 0) {
            record("focus.pauses", "SKIP", "Gives way when another sound takes over", "could not start playback first: ${whyNotPlaying()}")
            return
        }
        SystemClock.sleep(800)
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            record("focus.pauses", "SKIP", "Gives way when another sound takes over", "the system would not give this probe audio focus")
            return
        }
        var ms = waitFor(4000) { !playing() }
        check("focus.pauses", "Gives way when another sound takes over", ms >= 0, "still playing 4s after another app took audio focus", ms = ms)
        audio.abandonAudioFocusRequest(request)
        ms = waitFor(6000) { playing() }
        check("focus.returns", "Carries on when the other sound ends", ms >= 0, "did not resume within 6s of getting audio focus back (${stateName(state?.state)})", ms = ms)
        quiet()
    }

    /** The app is gone from memory; the car connects and presses play with nothing chosen. */
    private fun resume(service: ComponentName) {
        val was = nowPlaying()
        onMain { browser?.disconnect() }
        SystemClock.sleep(300)
        shell("am force-stop $target")
        SystemClock.sleep(700)
        state = null; metadata = null; queue = null
        if (!connect(service, "resume.connect", "Connects again after being removed from memory")) return
        onMain { controls.play() }
        val ms = waitFor { playing() }
        check("resume.play", "Play with nothing chosen picks up where it left off", ms >= 0,
            "nothing played (${whyNotPlaying()}); a car that presses play after connecting gets silence", "before: $was; now ${nowPlaying()}", ms)
        quiet()
    }

    // --- what an app that isn't the car may do ----------------------------------------------

    /**
     * `--mode outsider`. Run untrusted, it checks that an app with no standing gets no
     * connection at all, by either protocol. Run trusted (as Android Auto is), it starts
     * something playing and then tries to change the queue the two ways an app that is not
     * the app's own screens might: the app's own commands sent through a controller, and
     * intents sent to the exported service made to look like taps on the notification
     * (`forge`: the app's own command names; and always Media3's built-in ones, which can
     * carry a media item to play). The queue must be as it was.
     */
    private fun outsider(service: ComponentName, trusted: Boolean) {
        if (!trusted) {
            val refused = !connect(service, "outsider.probe", "(connecting the older way, as any app might)", expectRefusal = true)
            check("outsider.browse", "An app with no standing can't connect to browse the library", refused,
                "it connected: any app on the phone can list and search this library")
            val (controller, why) = media3Controller(service)
            check("outsider.controller", "An app with no standing can't connect a controller either", controller == null,
                "a Media3 controller connected", why)
            controller?.let { c -> onMain { c.release() } }
            return
        }
        if (!connect(service, "outsider.connect", "Connects as a trusted caller, as a car does")) return
        // Something to play that sits among others, found the way a car finds it.
        var pick: MediaItem? = null
        val pending = ArrayDeque(listOf(browser!!.root to 0))
        var opened = 0
        while (pending.isNotEmpty() && pick == null && opened < 40) {
            val (id, depth) = pending.removeFirst()
            val kids = children(id).first.orEmpty()
            opened++
            val songs = kids.filter { it.isPlayable && !it.isBrowsable }
            if (songs.size >= 4) pick = songs[1]
            if (depth < 4) kids.filter { it.isBrowsable && it.mediaId != null }.forEach { pending += it.mediaId!! to depth + 1 }
        }
        if (pick == null) {
            record("outsider.play", "SKIP", "Starts something playing", "no folder of four or more songs was found")
            return
        }
        onMain { controls.playFromMediaId(pick.mediaId, null) }
        val ms = waitFor { playing() }
        waitFor(4000) { (queue?.size ?: 0) > 2 }
        check("outsider.play", "Starts something playing, with a queue", ms >= 0 && (queue?.size ?: 0) > 2, whyNotPlaying() + ", queue of ${queue?.size}",
            "${nowPlaying()}, queue of ${queue?.size}", ms)
        if (ms < 0 || (queue?.size ?: 0) <= 2) return
        SystemClock.sleep(800)

        fun titles() = queue.orEmpty().map { it.description.title.toString() }
        fun place() = queue.orEmpty().indexOfFirst { it.queueId == state?.activeQueueItemId }
        val before = titles()
        val placeBefore = place()

        // 1. The app's own commands, through a controller that is trusted but isn't the app.
        val forge = args.getString("forge").orEmpty().split(';').filter { it.isNotBlank() }.map { spec ->
            val parts = spec.split('|')
            parts[0] to Bundle().apply {
                for (pair in parts.drop(1)) {
                    val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    when {
                        value == "true" || value == "false" -> putBoolean(key, value == "true")
                        value.toIntOrNull() != null -> putInt(key, value.toInt())
                        else -> putString(key, value)
                    }
                }
            }
        }
        val (controller, why) = media3Controller(service)
        if (controller == null) {
            record("outsider.commands", "FAIL", "A trusted controller connects", "a Media3 controller could not connect: $why")
        } else {
            val taken = forge.filter { (action, extras) ->
                val result = onMain { controller.sendCustomCommand(androidx.media3.session.SessionCommand(action, Bundle.EMPTY), extras) }
                runCatching { result.get(3, TimeUnit.SECONDS).resultCode }.getOrDefault(-1) == androidx.media3.session.SessionResult.RESULT_SUCCESS
            }.map { it.first.substringAfterLast('.') }
            SystemClock.sleep(600)
            check("outsider.commands", "The app's own commands are refused from a controller that isn't the app", taken.isEmpty() && titles() == before,
                (if (taken.isNotEmpty()) "accepted: ${taken.joinToString()}; " else "") + "queue was ${before.size} songs, now ${titles().size}",
                "${forge.size} commands sent, all refused")
            onMain { controller.release() }
        }

        // 2. Intents to the exported service, made to look like the notification's own.
        val item = androidx.media3.common.MediaItem.Builder().setMediaId(pick.mediaId.orEmpty()).build()
        fun built(command: Int, parameter: Bundle.() -> Unit) = "$PLAYER_COMMAND_PREFIX$command" to Bundle().apply(parameter)
        val forged = forge + listOf(
            built(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM) { putBundle(COMMAND_PARAMETER, item.toBundle()) },
            built(androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM) { putInt(COMMAND_PARAMETER, before.size - 1) },
            built(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS) { },
        )
        var delivered = 0
        val failures = mutableListOf<String>()
        for ((action, extras) in forged) {
            val intent = Intent(ACTION_CUSTOM).setComponent(service).putExtra(EXTRA_CUSTOM_ACTION, action).putExtra(EXTRA_CUSTOM_ACTION_EXTRAS, extras)
            runCatching { context.startService(intent) }.onSuccess { delivered++ }.onFailure { failures += it.javaClass.simpleName }
            SystemClock.sleep(120)
        }
        SystemClock.sleep(1500)
        val after = titles()
        val placeAfter = place()
        // The song may have run on into the next by itself meanwhile; no further.
        val moved = placeAfter - placeBefore
        check("outsider.forged", "Intents made to look like the notification's can't change the queue", after == before && moved in 0..1,
            "the queue was ${before.size} songs at place $placeBefore (${before.take(4)}…) and is now ${after.size} at place $placeAfter (${after.take(4)}…)",
            "$delivered of ${forged.size} intents reached the service" + (if (failures.isNotEmpty()) " (the system refused the rest: ${failures.distinct().joinToString()})" else "") +
                "; queue unchanged at ${after.size} songs")
        if (delivered == 0) {
            record("outsider.forged_delivery", "WARN", "The forged intents reached the service", "none did, so the check above proves nothing")
        }
        quiet()
    }

    /** A Media3 controller connected to the service, or null and why not. */
    private fun media3Controller(service: ComponentName): Pair<androidx.media3.session.MediaController?, String> {
        val future = runCatching {
            onMain {
                androidx.media3.session.MediaController.Builder(context, androidx.media3.session.SessionToken(context, service)).buildAsync()
            }
        }.getOrElse { return null to "no session token: $it" }
        return runCatching { future.get(limitMs, TimeUnit.MILLISECONDS) }.fold({ it to "" }, { null to (it.cause ?: it).toString().take(160) })
    }

    /** When nobody is signed in, the car should be told so in words, not shown a blank. */
    private fun signedOut(root: String) {
        val asked = SystemClock.elapsedRealtime()
        // Before anything is asked of it: a car showing only its player (the home screen's
        // card, say) opens no folder, and must still be told why nothing plays.
        val unasked = waitFor(4000) { state?.state == PlaybackStateCompat.STATE_ERROR && !state?.errorMessage.isNullOrBlank() }
        check("signed_out.unasked", "Says sign-in is needed without being asked anything", unasked >= 0,
            "four seconds after connecting, with no folder opened, the playback state is ${stateName(state?.state)} with no message",
            "\"${state?.errorMessage}\"", unasked)
        // A search must be answered, not left for the car to give up on.
        val t0 = SystemClock.elapsedRealtime()
        val (found, searchError) = searchFor("anything")
        val took = SystemClock.elapsedRealtime() - t0
        check("signed_out.search", "A search while signed out is answered at once", took < 3000,
            "it took $took ms (${searchError ?: "${found?.size} results"}); the car shows a spinner until its own time limit",
            searchError ?: "${found?.size} results", took)
        val (kids, error, _) = children(root)
        onMain { controls.play() }
        waitFor(5000) { state?.state == PlaybackStateCompat.STATE_ERROR }
        val standing = state?.state == PlaybackStateCompat.STATE_ERROR && !state?.errorMessage.isNullOrBlank()
        check("signed_out.message", "Tells the car, in words, that sign-in is needed", standing,
            (if (lastErrorAt >= asked) "an error was shown only for a moment (\"$lastError\") and withdrawn; the car needs it to stay"
            else "no error message in the playback state") + " (state: ${stateName(state?.state)}; root children: ${kids?.size ?: error})",
            "\"${state?.errorMessage}\" (code ${state?.errorCode})")
        check("signed_out.silent", "Plays nothing while signed out", !playing(), "it started playing ${nowPlaying()}")
        record("signed_out.resolution", "INFO", "Offers the car a sign-in button",
            state?.extras?.keySet()?.filter { it.contains("ERROR_RESOLUTION") }?.joinToString { "${it.substringAfterLast('.')}=${@Suppress("DEPRECATION") state?.extras?.get(it)}" }
                ?.ifEmpty { null } ?: "no error-resolution extras; the message alone is shown")
    }

    // --- plumbing --------------------------------------------------------------------------

    private fun <T> onMain(block: () -> T): T {
        var value: Result<T>? = null
        runOnMainSync { value = runCatching(block) }
        return value!!.getOrThrow()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private fun say(line: String) {
        sendStatus(0, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") })
    }

    private fun record(id: String, status: String, title: String, detail: String = "", ms: Long = -1) {
        results += JSONObject().put("id", id).put("status", status).put("title", title).put("detail", detail).put("ms", ms)
        say("%-5s %-24s %s%s%s".format(status, id, title, if (ms >= 0) "  (${ms} ms)" else "", if (detail.isNotEmpty()) "\n        $detail" else ""))
    }

    private fun check(id: String, title: String, ok: Boolean, problem: String, fine: String = "", ms: Long = -1, warnOnly: Boolean = false) =
        record(id, if (ok) "PASS" else if (warnOnly) "WARN" else "FAIL", title, if (ok) fine else problem, ms)

    private fun describe(b: Bundle?): String = b?.keySet()?.sorted()?.joinToString(", ", "{", "}") { "${it.substringAfterLast('.')}=${@Suppress("DEPRECATION") b.get(it)}" } ?: "none"

    private fun stateName(s: Int?) = when (s) {
        null -> "unknown"; PlaybackStateCompat.STATE_NONE -> "none"; PlaybackStateCompat.STATE_STOPPED -> "stopped"
        PlaybackStateCompat.STATE_PAUSED -> "paused"; PlaybackStateCompat.STATE_PLAYING -> "playing"
        PlaybackStateCompat.STATE_BUFFERING -> "buffering"; PlaybackStateCompat.STATE_ERROR -> "error"
        PlaybackStateCompat.STATE_CONNECTING -> "connecting"; else -> "state $s"
    }

    private companion object {
        const val BROWSER_SERVICE = "android.media.browse.MediaBrowserService"
        const val CAR_APPLICATION = "com.google.android.gms.car.application"
        const val CAR_NOTIFICATION_ICON = "com.google.android.gms.car.notification.SmallIcon"
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_SINGLE = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        const val GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val ROOT_CHILDREN_LIMIT = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
        const val ROOT_CHILDREN_SUPPORTED_FLAGS = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"
        const val MEDIA_ART_SIZE_HINT = "android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS"
        const val CUSTOM_BROWSER_ACTION_LIMIT = "androidx.media.utils.MediaBrowserCompat.extras.CUSTOM_BROWSER_ACTION_LIMIT"
        const val EXTRA_RECENT = "android.service.media.extra.RECENT"
        const val FOCUS = "android.intent.extra.focus"
        const val ARTIST = "android.intent.extra.artist"
        const val ALBUM = "android.intent.extra.album"
        const val TITLE = "android.intent.extra.title"
        const val PLAYLIST = "android.intent.extra.playlist"
        const val GENRE = "android.intent.extra.genre"

        // How Media3 writes a custom notification action into the intent it starts its
        // service with, and how it names its built-in player commands when sent that way
        // (CustomCommandPendingIntentBuilder and CommandButton in media3-session).
        const val ACTION_CUSTOM = "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION"
        const val EXTRA_CUSTOM_ACTION = "androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION"
        const val EXTRA_CUSTOM_ACTION_EXTRAS = "androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION_EXTRAS"
        const val PLAYER_COMMAND_PREFIX = "androidx.media3.session.PLAYER_COMMAND_"
        const val COMMAND_PARAMETER = "androidx.media3.session.CUSTOM_COMMAND_PARAMETER"
    }
}

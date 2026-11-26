package org.medianexus.music.requests

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.medianexus.music.core.Core
import org.medianexus.music.core.StandIn

/**
 * A stand-in sign-in service, and a stand-in Lidarr behind it that lets through only a
 * pass the service handed out.
 *
 * The service **checks the secret half of a sign-in as a real one does** (PKCE, S256):
 * a code is handed out for the check that came with the request for it, and is traded
 * for a token only by whoever then shows the secret that check was made from. An app
 * that sent the wrong secret, or a code brought to another sign-in, gets nothing.
 */
internal class SignInStandIn(private val closing: MutableList<StandIn>, private val key: String) {
    val passes = CopyOnWriteArrayList<String>()
    val refusing = AtomicBoolean(false)

    /** Lidarr answers without a pass (the sign-in in front of it has been taken away). */
    val unguarded = AtomicBoolean(false)
    val revoked = CopyOnWriteArrayList<String>()

    /** The address Lidarr's sign-in names the service by, if not the one it listens at
     *  (the same machine by another name, say: to the app, another host). */
    @Volatile var named: String? = null

    /** Who the service says signed in, as it is written inside its JSON answer. */
    @Volatile var username = "walker"
    private val made = AtomicInteger(0)

    /** Requests the service keeps waiting until the test lets them go, by something in
     *  their address or their body. */
    val held = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>()

    /** The check each code was asked for with. */
    private val checks = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun s256(secret: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
    val service = StandIn { asked, reply ->
        val form = asked.body.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        held.entries.firstOrNull { it.key in asked.target || it.key in asked.body }?.value?.await(30, java.util.concurrent.TimeUnit.SECONDS)
        when {
            asked.target.startsWith("/application/o/authorize/?response_type=code&") -> {
                val query = asked.target.substringAfter('?')
                val check = Core.gateParam(query, "code_challenge").orEmpty()
                val back = Core.gateParam(query, "redirect_uri").orEmpty()
                if (Core.gateParam(query, "code_challenge_method") != "S256" || check.isEmpty() || back.isEmpty()) {
                    reply.send(400)
                } else {
                    val code = "code-${made.incrementAndGet()}"
                    checks[code] = check
                    reply.send(302, "", "Location" to "$back?code=$code&state=${Core.gateParam(query, "state").orEmpty()}")
                }
            }
            asked.target == "/application/o/token/" && refusing.get() -> reply.json("""{"error":"invalid_grant"}""", status = 400)
            asked.target == "/application/o/token/" && form["grant_type"] == "authorization_code" && checks.remove(form["code"].orEmpty()) != s256(form["code_verifier"].orEmpty()) ->
                reply.json("""{"error":"invalid_grant","error_description":"the code and the secret do not belong together"}""", status = 400)
            asked.target == "/application/o/token/" && form["grant_type"] == "client_credentials" -> {
                val pass = "pass-${made.incrementAndGet()}"
                passes += pass
                reply.json("""{"access_token":"$pass","expires_in":600}""")
            }
            asked.target == "/application/o/token/" -> reply.json("""{"access_token":"access-${made.incrementAndGet()}","refresh_token":"refresh-${made.incrementAndGet()}","expires_in":300}""")
            asked.target == "/application/o/userinfo/" -> reply.json("""{"sub":"x","preferred_username":"$username","name":"The Walker"}""")
            asked.target == "/application/o/revoke/" -> {
                revoked += form["token"].orEmpty()
                reply.send(200)
            }
            else -> reply.send(404)
        }
    }.also { closing += it }
    val lidarr: StandIn = StandIn { asked, reply ->
        val pass = asked.header("Authorization").orEmpty().removePrefix("Bearer ")
        when {
            asked.target.startsWith("/outpost.goauthentik.io/start") ->
                reply.send(302, "", "Location" to "${named ?: service.address}/application/o/authorize/?client_id=lidarr-proxy&redirect_uri=x&response_type=code")
            !unguarded.get() && pass !in passes -> reply.send(302, "", "Location" to "/outpost.goauthentik.io/start?rd=x")
            asked.header("X-Api-Key") != key -> reply.send(401)
            asked.target == "/api/v1/system/status" -> reply.json("""{"version":"2.9.6.4552"}""")
            else -> reply.send(404)
        }
    }.also { closing += it }
    val host: String get() = service.address.removePrefix("http://")
}


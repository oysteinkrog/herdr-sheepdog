package io.github.oysteinkrog.sheepdog

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** What the app keeps about the paired computer. Never a secret: the private key stays in the Keystore. */
data class Pairing(
    val baseUrls: List<String>, // https://host:port, the link's own host first
    val daemonId: String,
    val deviceId: String,
    val name: String,
    val pins: List<String>,
)

/** The pairing link from the QR code (docs/dev/ws-contract.md "Pairing"). */
data class PairLink(val host: String?, val daemonId: String, val name: String, val pin: String, val code: String, val addresses: List<String>) {
    companion object {
        fun parse(text: String): PairLink? {
            val s = text.trim()
            val hash = s.indexOf('#')
            if (hash < 0) return null
            val frag = s.substring(hash + 1)
            val kv = mutableMapOf<String, MutableList<String>>()
            for (part in frag.split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val k = URLDecoder.decode(part.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                kv.getOrPut(k) { mutableListOf() }.add(v)
            }
            if (kv["pair"]?.firstOrNull() != "1") return null
            val d = kv["d"]?.firstOrNull() ?: return null
            val k = kv["k"]?.firstOrNull() ?: return null
            val e = kv["e"]?.firstOrNull() ?: return null
            val before = s.substring(0, hash)
            val host = runCatching { Uri.parse(before) }.getOrNull()?.let { u ->
                if (u.scheme == "https" && u.host != null) "https://${u.host}${if (u.port > 0) ":${u.port}" else ""}" else null
            }
            return PairLink(host, d, kv["n"]?.firstOrNull() ?: "Computer", k, e, kv["u"] ?: emptyList())
        }
    }

    fun baseUrls(): List<String> = (listOfNotNull(host) + addresses.map { "https://$it" }).distinct()
}

class DaemonError(val status: Int, val reason: String, message: String) : Exception(message)

object Keys {
    private fun alias(daemonId: String) = "sheepdog-device-$daemonId"

    /** A new P-256 key in the Android Keystore; returns the DER SubjectPublicKeyInfo. */
    fun create(daemonId: String): ByteArray {
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        g.initialize(
            KeyGenParameterSpec.Builder(alias(daemonId), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return g.generateKeyPair().public.encoded
    }

    fun sign(daemonId: String, text: String): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(alias(daemonId), null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(text.toByteArray(Charsets.UTF_8))
            sign()
        }
    }

    fun delete(daemonId: String) {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(daemonId)) }
    }
}

/**
 * TLS: a name with a real certificate (the krog.zone name through Caddy)
 * passes the system's check. A bare address gets the daemon's own
 * self-signed certificate, which passes when its key matches a pin from the
 * pairing code (base64url SHA-256 of the SubjectPublicKeyInfo).
 */
class PinnedTrust(private val pins: () -> List<String>) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }.trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (pinned(chain[0])) return
        system.checkServerTrusted(chain, authType)
    }

    fun pinned(cert: X509Certificate): Boolean = pinOf(cert) in pins()

    companion object {
        fun pinOf(cert: X509Certificate): String {
            val sum = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            return Base64.encodeToString(sum, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}

/** HTTP to the paired computer: sign-in, state, answers. */
class Daemon(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    @Volatile var pairing: Pairing? = load()
        private set
    @Volatile private var ticket: String? = null
    @Volatile var base: String? = pairing?.baseUrls?.firstOrNull()
        private set

    private val trust = PinnedTrust { pairing?.pins ?: pendingPins }
    @Volatile private var pendingPins: List<String> = emptyList()

    val http: OkHttpClient = run {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { host, session ->
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                (cert != null && trust.pinned(cert)) || defaultVerifier.verify(host, session)
            })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    /** For /events: no read timeout between keepalives. */
    val streamHttp: OkHttpClient = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    /** For /ws. */
    val wsHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    private fun load(): Pairing? {
        val d = prefs.getString("daemon_id", null) ?: return null
        return Pairing(
            baseUrls = JSONArray(prefs.getString("base_urls", "[]")).let { a -> List(a.length()) { a.getString(it) } },
            daemonId = d,
            deviceId = prefs.getString("device_id", "")!!,
            name = prefs.getString("name", "Computer")!!,
            pins = JSONArray(prefs.getString("pins", "[]")).let { a -> List(a.length()) { a.getString(it) } },
        )
    }

    private fun save(p: Pairing) {
        prefs.edit()
            .putString("daemon_id", p.daemonId).putString("device_id", p.deviceId).putString("name", p.name)
            .putString("base_urls", JSONArray(p.baseUrls).toString()).putString("pins", JSONArray(p.pins).toString())
            .apply()
        pairing = p
    }

    fun unpair() {
        pairing?.let { p ->
            runCatching { authed("DELETE", "/api/devices/self", null) }
            Keys.delete(p.daemonId)
        }
        prefs.edit().clear().apply()
        pairing = null
        ticket = null
    }

    /** Pairs with the code from the QR: makes the key, enrolls it, signs in. */
    fun pair(link: PairLink) {
        pendingPins = listOf(link.pin)
        val urls = link.baseUrls()
        val pub = Keys.create(link.daemonId)
        val body = JSONObject()
            .put("code", link.code)
            .put("public_key", Base64.encodeToString(pub, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            .put("name", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            .put("kind", "android")
            .put("hardware", true)
        var last: Exception? = null
        for (u in urls) {
            try {
                val out = call(u, "POST", "/api/devices/enroll", body, null)
                save(Pairing(listOf(u) + urls.filter { it != u }, link.daemonId, out.getString("device_id"), link.name, listOf(link.pin)))
                base = u
                signIn()
                return
            } catch (e: DaemonError) {
                Keys.delete(link.daemonId)
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        Keys.delete(link.daemonId)
        throw last ?: Exception("No address to reach the computer")
    }

    /** Signs a fresh challenge with the device key and keeps the ticket. Tries each known pin. */
    @Synchronized
    fun signIn(): String {
        val p = pairing ?: throw DaemonError(0, "not_paired", "Not paired")
        var last: Exception? = null
        for (u in listOfNotNull(base) + p.baseUrls.filter { it != base }) {
            try {
                for ((i, pin) in p.pins.withIndex()) {
                    val nonce = call(u, "GET", "/api/auth/challenge", null, null).getString("nonce")
                    val time = System.currentTimeMillis() / 1000
                    val text = "sheepdog-auth-v1\n${p.daemonId}\n$pin\n${p.deviceId}\n$nonce\n$time\nsession"
                    val sig = Base64.encodeToString(Keys.sign(p.daemonId, text), Base64.NO_WRAP)
                    val body = JSONObject().put("device_id", p.deviceId).put("nonce", nonce).put("time", time)
                        .put("signature", sig).put("client", "app")
                    try {
                        val out = call(u, "POST", "/api/auth/session", body, null)
                        val next = out.optString("next_pin", "")
                        if (next.isNotEmpty() && next !in p.pins) save(p.copy(pins = p.pins + next))
                        base = u
                        val t = out.getString("ticket")
                        ticket = t
                        return t
                    } catch (e: DaemonError) {
                        if (e.reason != "bad_signature" || i == p.pins.lastIndex) throw e
                    }
                }
            } catch (e: DaemonError) {
                throw e
            } catch (e: Exception) {
                last = e // this address is unreachable: try the next
            }
        }
        throw last ?: Exception("Cannot reach the computer")
    }

    fun currentTicket(): String = ticket ?: signIn()

    /** An authenticated call; on an expired session signs in again and retries once. */
    fun authed(method: String, path: String, body: JSONObject?, headers: Map<String, String> = emptyMap()): JSONObject {
        val b = base ?: throw DaemonError(0, "not_paired", "Not paired")
        return try {
            call(b, method, path, body, currentTicket(), headers)
        } catch (e: DaemonError) {
            if (e.status == 401 && (e.reason == "no_session" || e.reason == "session_expired")) {
                ticket = null
                call(base!!, method, path, body, signIn(), headers)
            } else throw e
        }
    }

    fun request(path: String): Request.Builder =
        Request.Builder().url(base + path).header("Authorization", "Bearer sds_${currentTicket()}")

    fun dropTicket() { ticket = null }

    private fun call(base: String, method: String, path: String, body: JSONObject?, ticket: String?, headers: Map<String, String> = emptyMap()): JSONObject {
        val rb = Request.Builder().url(base + path)
        if (ticket != null) rb.header("Authorization", "Bearer sds_$ticket")
        for ((k, v) in headers) rb.header(k, v)
        val reqBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        rb.method(method, reqBody ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null)
        http.newCall(rb.build()).execute().use { r -> return parse(r) }
    }

    private fun parse(r: Response): JSONObject {
        val text = r.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (!r.isSuccessful) {
            throw DaemonError(r.code, json.optString("reason", json.optString("error", "")), json.optString("error", "HTTP ${r.code}"))
        }
        return json
    }

    companion object {
        fun idempotencyKey(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

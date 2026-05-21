package riot

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import riot.models.RiotAccountTokens
import riot.models.RiotFriend
import util.Logging
import util.LogType
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.*

private const val LOG = "[RiotConn]"
private val GSON = Gson()

private val LOCKFILE = File(
    System.getenv("LOCALAPPDATA"),
    "Riot Games/Riot Client/Config/lockfile"
)

private fun trustAllClient(): OkHttpClient {
    val tm = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, t: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, t: String) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }
    val ctx = SSLContext.getInstance("SSL")
    ctx.init(null, arrayOf(tm), SecureRandom())
    return OkHttpClient.Builder()
        .sslSocketFactory(ctx.socketFactory, tm)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}

/** Reads the Riot Client lockfile. Returns (port, password) or null if absent. */
fun readLockfile(): Pair<String, String>? {
    if (!LOCKFILE.exists()) return null
    return runCatching {
        val parts = LOCKFILE.readText().trim().split(":")
        if (parts.size < 5) null else parts[2] to parts[3]
    }.getOrNull()
}

/**
 * Connects to the Riot Client local API via WebSocket and fires callbacks on events.
 * Also provides helpers for HTTP requests to the local API.
 */
class RiotClientConnection(
    val onAccountChange: (tokens: RiotAccountTokens) -> Unit = {},
    val onFriendsSnapshot: (accountName: String, friends: List<RiotFriend>) -> Unit = { _, _ -> },
    val onPresenceUpdate: (puuid: String, friend: RiotFriend) -> Unit = { _, _ -> },
) {
    private val http = trustAllClient()
    private var ws: WebSocket? = null
    private var lastLockfileKey: String? = null
    private var currentAccount: String? = null
    private val watchTimer = Timer("RiotLockfileWatch", true)

    fun start() {
        watchTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { checkLockfile() }
        }, 0, 3_000)
    }

    fun stop() {
        watchTimer.cancel()
        ws?.close(1000, null)
        ws = null
    }

    private fun checkLockfile() {
        val lock = readLockfile()
        val key = lock?.let { "${it.first}:${it.second}" }
        if (key == lastLockfileKey) return
        lastLockfileKey = key
        ws?.close(1000, null)
        ws = null
        if (lock == null) {
            Logging.log("$LOG Riot Client offline", LogType.DEBUG)
            currentAccount = null
            return
        }
        connect(lock.first, lock.second)
    }

    private fun connect(port: String, password: String) {
        val token = Base64.getEncoder().encodeToString("riot:$password".toByteArray())
        val baseUrl = "https://127.0.0.1:$port"
        val headers = mapOf("Authorization" to "Basic $token")

        // Identify current account
        runCatching { fetchAccountIdentity(baseUrl, headers) }.getOrNull()?.let { tokens ->
            if (tokens.accountName != currentAccount) {
                currentAccount = tokens.accountName
                Logging.log("$LOG Active account: ${tokens.accountName}", LogType.DEBUG)
                onAccountChange(tokens)
            }
            // Initial state fetch
            fetchAndEmitFriends(baseUrl, headers, tokens.accountName)
        }

        // WebSocket
        val wsUrl = "wss://127.0.0.1:$port/"
        val req = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Basic $token")
            .build()

        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Logging.log("$LOG WebSocket connected", LogType.DEBUG)
                webSocket.send(GSON.toJson(listOf(5, "OnJsonApiEvent")))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.isEmpty()) return
                runCatching { handleEvent(GSON.fromJson(text, com.google.gson.JsonArray::class.java), baseUrl, headers) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Logging.log("$LOG WebSocket error: ${t.message}", LogType.DEBUG)
                lastLockfileKey = null  // force reconnect on next tick
            }
        })
    }

    private fun handleEvent(msg: com.google.gson.JsonArray, baseUrl: String, headers: Map<String, String>) {
        if (msg.size() < 3) return
        val payload = msg.get(2).asJsonObject
        val uri = payload.get("uri")?.asString ?: return
        val data = payload.get("data")

        when {
            uri == "/rso-auth/v1/authorization" -> {
                val eventType = payload.get("eventType")?.asString
                if (eventType == "Delete") {
                    Logging.log("$LOG Account logged out", LogType.DEBUG)
                    currentAccount = null
                } else {
                    // Re-identify account after login
                    runCatching { fetchAccountIdentity(baseUrl, headers) }.getOrNull()?.let { tokens ->
                        if (tokens.accountName != currentAccount) {
                            currentAccount = tokens.accountName
                            onAccountChange(tokens)
                            fetchAndEmitFriends(baseUrl, headers, tokens.accountName)
                        }
                    }
                }
            }
            uri.startsWith("/chat/v4/presences") || uri.startsWith("/social/v4/presences") -> {
                val account = currentAccount ?: return
                val presences = data?.asJsonObject?.getAsJsonArray("presences") ?: return
                for (el in presences) {
                    runCatching { parsePresenceEvent(el.asJsonObject, account) }
                }
            }
            uri == "/social/v4/friends" || uri == "/chat/v4/friends" -> {
                val account = currentAccount ?: return
                fetchAndEmitFriends(baseUrl, headers, account)
            }
        }
    }


    private fun fetchAccountIdentity(baseUrl: String, headers: Map<String, String>): RiotAccountTokens? {
        // Get entitlement + access_token
        val entResp = get("$baseUrl/entitlements/v2/token", headers) ?: return null
        val entJson = GSON.fromJson(entResp, JsonObject::class.java)
        val accessToken = entJson.getAsJsonObject("authorization")
            ?.getAsJsonObject("accessToken")?.get("token")?.asString ?: return null
        val entitlement = entJson.get("token")?.asString ?: return null

        // Get user display name
        val userInfoResp = get("https://auth.riotgames.com/userinfo",
            mapOf("Authorization" to "Bearer $accessToken")) ?: return null
        val userJson = GSON.fromJson(userInfoResp, JsonObject::class.java)
        val gameName = userJson.getAsJsonObject("acct")?.get("game_name")?.asString ?: return null
        val tagLine = userJson.getAsJsonObject("acct")?.get("tag_line")?.asString ?: ""

        // Get XMPP region from chat session
        val sessionResp = runCatching { get("$baseUrl/chat/v1/session", headers) }.getOrNull()
        val xmppRegion = sessionResp?.let {
            runCatching { GSON.fromJson(it, JsonObject::class.java).get("region")?.asString }.getOrNull()
        }

        return RiotAccountTokens("$gameName#$tagLine", accessToken, entitlement, xmppRegion)
    }

    private fun fetchAndEmitFriends(baseUrl: String, headers: Map<String, String>, accountName: String) {
        runCatching {
            val friendsJson = GSON.fromJson(get("$baseUrl/chat/v4/friends", headers) ?: return, JsonObject::class.java)
            val presencesJson = GSON.fromJson(get("$baseUrl/chat/v4/presences", headers) ?: return, JsonObject::class.java)

            val presenceMap = buildPresenceMap(presencesJson)
            val friends = mutableListOf<RiotFriend>()

            for (el in friendsJson.getAsJsonArray("friends") ?: return) {
                val f = el.asJsonObject
                val puuid = f.get("puuid")?.asString ?: continue
                val friend = RiotFriend(
                    puuid = puuid,
                    gameName = f.get("game_name")?.asString ?: f.get("gameName")?.asString ?: "",
                    gameTag = f.get("game_tag")?.asString ?: f.get("gameTag")?.asString ?: "",
                    lastOnlineTs = f.get("last_online_ts")?.asLong ?: 0L,
                    ownerAccount = accountName,
                )
                presenceMap[puuid]?.let { applyPresenceData(it, friend) }
                friends.add(friend)
            }
            onFriendsSnapshot(accountName, friends)
        }
    }

    private fun parsePresenceEvent(p: JsonObject, accountName: String) {
        val puuid = p.get("puuid")?.asString ?: return
        val gameName = p.get("game_name")?.asString ?: p.get("gameName")?.asString ?: return
        val gameTag = p.get("game_tag")?.asString ?: p.get("gameTag")?.asString ?: ""
        val friend = RiotFriend(puuid = puuid, gameName = gameName, gameTag = gameTag, ownerAccount = accountName)
        applyPresenceData(p, friend)
        onPresenceUpdate(puuid, friend)
    }

    private fun buildPresenceMap(json: JsonObject): Map<String, JsonObject> {
        val map = mutableMapOf<String, JsonObject>()
        for (el in json.getAsJsonArray("presences") ?: return map) {
            val p = el.asJsonObject
            val puuid = p.get("puuid")?.asString ?: continue
            map[puuid] = p
        }
        return map
    }

    fun applyPresenceData(p: JsonObject, friend: RiotFriend) {
        friend.online = true
        val packed = p.get("packedData")?.asString ?: return
        runCatching {
            val padded = packed + "=".repeat((4 - packed.length % 4) % 4)
            val json = GSON.fromJson(String(Base64.getDecoder().decode(padded)), JsonObject::class.java)
            friend.gameState = json.getAsJsonObject("player")?.get("state")?.asString
            val loc = json.getAsJsonObject("activity")?.get("location")?.asString ?: ""
            friend.mapName = loc.substringAfterLast("/").takeIf { it.isNotBlank() }
            friend.gameMode = json.getAsJsonObject("activity")?.get("mode")?.asString
                ?.removePrefix("social_mode_")
            friend.partyId = json.getAsJsonObject("party")?.get("id")?.asString
        }
    }

    // ── Auth server reauth via ssid ────────────────────────────────────────

    private val authClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val tokenPattern = Pattern.compile("access_token=([\\w.\\-_]*).*id_token=([\\w.\\-_]*)")

    fun reauthWithSsid(ssid: String): RiotAccountTokens? {
        val params = mapOf(
            "acr_values" to "urn:riot:bronze", "claims" to "", "client_id" to "riot-client",
            "nonce" to "oYnVwCSrlS5IHKh7iI16oQ", "redirect_uri" to "http://localhost/redirect",
            "response_type" to "token id_token", "scope" to "openid link ban lol_region",
        )
        val body = GSON.toJson(params).toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://auth.riotgames.com/api/v1/authorization")
            .post(body)
            .addHeader("User-Agent", "RiotClient/99.0.0.0 riot-status (Windows;10;;Professional, x64)")
            .addHeader("Cookie", "ssid=$ssid")
            .build()

        return runCatching {
            val resp = authClient.newCall(req).execute()
            val json = GSON.fromJson(resp.body?.string(), JsonObject::class.java)
            if (json.get("type")?.asString != "response") return null
            val uri = json.getAsJsonObject("response")?.getAsJsonObject("parameters")?.get("uri")?.asString ?: return null
            val m = tokenPattern.matcher(uri)
            if (!m.find()) return null
            val accessToken = m.group(1)
            val idToken = m.group(2)

            val entResp = authClient.newCall(
                Request.Builder()
                    .url("https://entitlements.auth.riotgames.com/api/token/v1")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
            ).execute()
            val entitlement = GSON.fromJson(entResp.body?.string(), JsonObject::class.java)
                .get("entitlements_token")?.asString ?: return null

            val userResp = authClient.newCall(
                Request.Builder().url("https://auth.riotgames.com/userinfo")
                    .addHeader("Authorization", "Bearer $accessToken").build()
            ).execute()
            val userJson = GSON.fromJson(userResp.body?.string(), JsonObject::class.java)
            val gameName = userJson.getAsJsonObject("acct")?.get("game_name")?.asString ?: return null
            val tagLine = userJson.getAsJsonObject("acct")?.get("tag_line")?.asString ?: ""

            val regionResp = authClient.newCall(
                Request.Builder()
                    .url("https://riot-geo.pas.si.riotgames.com/pas/v1/product/valorant")
                    .put("""{"id_token":"$idToken"}""".toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
            ).execute()
            val region = GSON.fromJson(regionResp.body?.string(), JsonObject::class.java)
                .getAsJsonObject("affinities")?.get("live")?.asString

            RiotAccountTokens("$gameName#$tagLine", accessToken, entitlement, region)
        }.getOrNull()
    }

    // ── HTTP helper ────────────────────────────────────────────────────────

    fun get(url: String, headers: Map<String, String>): String? {
        return runCatching {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            http.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code != 200) null else resp.body?.string()
            }
        }.getOrNull()
    }
}

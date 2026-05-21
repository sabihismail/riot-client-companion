package riot.xmpp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.stirante.lolclient.ClientApi
import league.util.LeagueConnectionUtil
import org.apache.hc.core5.http.message.BasicHeader
import riot.models.RiotFriend
import util.Logging
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.SocketTimeoutException

private const val XMPP_PORT = 5223
private const val LOG_TAG = "[RiotXmpp]"
private val GSON = Gson()

// PAS affinity → (chat domain, stream region)
private val AFFINITY_MAP = mapOf(
    "na1"    to ("na1" to "na2"),
    "us"     to ("la1" to "la1"),
    "us-br1" to ("br"  to "br1"),
    "us-la2" to ("la2" to "la2"),
    "us2"    to ("us2" to "us2"),
    "euw1"   to ("eu1" to "euw1"),
    "eun1"   to ("eu2" to "eun1"),
    "eu"     to ("ru1" to "ru1"),
    "eu3"    to ("eu3" to "eu3"),
    "ru1"    to ("ru1" to "ru1"),
    "tr1"    to ("tr1" to "tr1"),
    "br1"    to ("br"  to "br1"),
    "la1"    to ("la1" to "la1"),
    "la2"    to ("la2" to "la2"),
    "jp1"    to ("jp1" to "jp1"),
    "asia"   to ("jp1" to "jp1"),
    "kr1"    to ("kr1" to "kr1"),
    "oc1"    to ("oc1" to "oc1"),
)

object RiotXmppClient {

    /**
     * Fetches the full friend list + presence for [accessToken]'s account via XMPP.
     * [chatAffinities]: live hostname map from /client-config/v2/namespace/chat (may be null to use built-in map).
     * [xmppRegion]: the stream 'to' value from /chat/v1/session (e.g. "br1").
     */
    fun fetchFriends(
        accessToken: String,
        entitlement: String,
        chatAffinities: Map<String, String>? = null,
        xmppRegion: String? = null,
        presenceTimeoutMs: Long = 8000,
    ): List<RiotFriend> {
        val pasToken = getPasToken(accessToken) ?: run {
            Logging.log("$LOG_TAG Failed to get PAS token", util.LogType.WARNING)
            return emptyList()
        }
        val pasData = decodePasJwt(pasToken)
        val affinity = pasData.get("affinity")?.asString ?: run {
            Logging.log("$LOG_TAG No affinity in PAS token", util.LogType.WARNING)
            return emptyList()
        }

        val (chatDomain, defaultStream) = resolveChatHost(affinity, chatAffinities)
        val streamRegion = xmppRegion ?: defaultStream
        val host = if (chatAffinities != null && chatAffinities.containsKey(affinity))
            chatAffinities[affinity]!!
        else
            "$chatDomain.chat.si.riotgames.com"

        return runCatching {
            connectAndFetch(host, streamRegion, accessToken, pasToken, entitlement, presenceTimeoutMs)
        }.onFailure {
            Logging.log("$LOG_TAG XMPP error: ${it.message}", util.LogType.WARNING)
        }.getOrElse { emptyList() }
    }

    private fun getPasToken(accessToken: String): String? {
        val url = "https://riot-geo.pas.si.riotgames.com/pas/v1/service/chat"
        val response = runCatching {
            LeagueConnectionUtil.createHttpClient().use { client ->
                val req = org.apache.hc.client5.http.classic.methods.HttpGet(java.net.URI(url))
                req.addHeader("Authorization", "Bearer $accessToken")
                client.execute(req).use { resp ->
                    if (resp.code != 200) return null
                    val text = LeagueConnectionUtil.dumpStream(resp.entity.content)
                    org.apache.hc.core5.http.io.entity.EntityUtils.consume(resp.entity)
                    text
                }
            }
        }.getOrNull()
        return response?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun decodePasJwt(token: String): JsonObject {
        val payload = token.split(".").getOrNull(1) ?: return JsonObject()
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        return runCatching {
            GSON.fromJson(String(Base64.getDecoder().decode(padded)), JsonObject::class.java)
        }.getOrElse { JsonObject() }
    }

    private fun resolveChatHost(affinity: String, liveMap: Map<String, String>?): Pair<String, String> {
        if (liveMap != null && liveMap.containsKey(affinity)) {
            return liveMap[affinity]!! to affinity
        }
        return AFFINITY_MAP[affinity] ?: (affinity to affinity)
    }

    private fun createSslSocket(host: String): SSLSocket {
        val ks = KeyStore.getInstance("JKS")
        ks.load(ClientApi::class.java.getResourceAsStream("/riotgames.jks"), "nopass".toCharArray())
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx.socketFactory.createSocket(host, XMPP_PORT) as SSLSocket
    }

    private fun connectAndFetch(
        host: String,
        streamRegion: String,
        accessToken: String,
        pasToken: String,
        entitlement: String,
        presenceTimeoutMs: Long,
    ): List<RiotFriend> {
        val socket = createSslSocket(host)
        socket.soTimeout = 10_000
        val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
        val reader = InputStreamReader(socket.inputStream, Charsets.UTF_8)

        fun send(xml: String) { writer.write(xml); writer.flush() }
        fun read(sep: String, ms: Int = 10_000) = readUntil(reader, socket, sep, ms)

        // Auth flow
        send(streamHeader(streamRegion))
        read("</stream:features>")

        send(rsoAuth(accessToken, pasToken))
        val authResp = read("</success>", 10_000)
        if ("<fail" in authResp || "</success>" !in authResp) {
            socket.close()
            Logging.log("$LOG_TAG XMPP auth failed: ${authResp.take(200)}", util.LogType.WARNING)
            return emptyList()
        }

        send(streamHeader(streamRegion))
        read("</stream:features>")
        send(bindIq())
        read("</iq>")
        send(entitlementsIq(entitlement))
        read("</iq>")
        send(sessionIq())
        read("</iq>")

        // Roster
        send(rosterRequest())
        val rosterXml = read("</iq>", 15_000)
        val friends = parseRoster(rosterXml)

        // Collect presences
        send("<presence/>")
        socket.soTimeout = 500
        val deadline = System.currentTimeMillis() + presenceTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val xml = readUntil(reader, socket, "</presence>", 500)
                if (xml.isNotBlank()) parsePresence(xml, friends)
            } catch (_: SocketTimeoutException) { /* keep looping */ }
        }

        socket.close()
        return friends.values.toList()
    }

    private fun readUntil(reader: InputStreamReader, socket: SSLSocket, sep: String, timeoutMs: Int): String {
        socket.soTimeout = timeoutMs
        val sb = StringBuilder()
        val buf = CharArray(4096)
        while (!sb.contains(sep)) {
            val n = reader.read(buf)
            if (n == -1) break
            sb.append(buf, 0, n)
        }
        return sb.toString()
    }

    // ── XML builders ───────────────────────────────────────────────────────

    private fun streamHeader(region: String) =
        """<?xml version="1.0" encoding="UTF-8"?><stream:stream to="$region.pvp.net" xml:lang="en" version="1.0" xmlns="jabber:client" xmlns:stream="http://etherx.jabber.org/streams">"""

    private fun rsoAuth(rso: String, pas: String) =
        """<auth mechanism="X-Riot-RSO-PAS" xmlns="urn:ietf:params:xml:ns:xmpp-sasl"><rso_token>$rso</rso_token><pas_token>$pas</pas_token></auth>"""

    private fun bindIq() =
        """<iq id="_xmpp_bind1" type="set"><bind xmlns="urn:ietf:params:xml:ns:xmpp-bind"><puuid-mode enabled="true"/></bind></iq>"""

    private fun entitlementsIq(token: String) =
        """<iq id="xmpp_entitlements_0" type="set"><entitlements xmlns="urn:riotgames:entitlements"><token>$token</token></entitlements></iq>"""

    private fun sessionIq() =
        """<iq id="_xmpp_session1" type="set"><session xmlns="urn:ietf:params:xml:ns:xmpp-session"><platform>riot</platform></session></iq>"""

    private fun rosterRequest() =
        """<iq type="get" id="roster_1"><query xmlns="jabber:iq:riotgames:roster" last_state="true" /></iq>"""

    // ── XML parsers ────────────────────────────────────────────────────────

    private fun parseRoster(xml: String): MutableMap<String, RiotFriend> {
        val friends = mutableMapOf<String, RiotFriend>()
        runCatching {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(wrapForParsing(xml))))
            val ns = "jabber:iq:riotgames:roster"
            val items = doc.getElementsByTagNameNS(ns, "item")
            for (i in 0 until items.length) {
                val item = items.item(i)
                val puuid = item.attributes.getNamedItem("puuid")?.nodeValue ?: continue
                val sub = item.attributes.getNamedItem("subscription")?.nodeValue
                if (sub != "both") continue
                val idEls = (item as org.w3c.dom.Element).getElementsByTagNameNS(ns, "id")
                val idEl = if (idEls.length > 0) idEls.item(0) else null
                val name = idEl?.attributes?.getNamedItem("name")?.nodeValue ?: ""
                val tag = idEl?.attributes?.getNamedItem("tagline")?.nodeValue ?: ""
                friends[puuid] = RiotFriend(puuid = puuid, gameName = name, gameTag = tag)
            }
        }
        return friends
    }

    private fun parsePresence(xml: String, friends: MutableMap<String, RiotFriend>) {
        runCatching {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(InputSource(StringReader(wrapForParsing(xml))))
            val root = doc.documentElement ?: return
            if (root.tagName != "presence") return
            val puuid = root.getAttribute("from").split("@").firstOrNull() ?: return
            val friend = friends[puuid] ?: return
            val ptype = root.getAttribute("type").takeIf { it.isNotBlank() } ?: "available"
            friend.online = ptype != "unavailable"
            if (!friend.online) return

            val pEl = root.getElementsByTagName("p")
            val pText = if (pEl.length > 0) pEl.item(0)?.textContent else null
            if (!pText.isNullOrBlank()) applyPackedData(pText, friend)
        }
    }

    private fun applyPackedData(packed: String, friend: RiotFriend) {
        runCatching {
            val padded = packed + "=".repeat((4 - packed.length % 4) % 4)
            val json = GSON.fromJson(
                String(Base64.getDecoder().decode(padded)),
                JsonObject::class.java
            )
            friend.gameState = json.getAsJsonObject("player")?.get("state")?.asString
            val loc = json.getAsJsonObject("activity")?.get("location")?.asString ?: ""
            friend.mapName = loc.substringAfterLast("/").takeIf { it.isNotBlank() }
            friend.gameMode = json.getAsJsonObject("activity")?.get("mode")?.asString
                ?.removePrefix("social_mode_")
            friend.partyId = json.getAsJsonObject("party")?.get("id")?.asString
        }
    }

    // Wrap fragment in a root element so DocumentBuilder can parse it
    private fun wrapForParsing(xml: String): String {
        val stripped = xml.substringAfter("?>").trim()
        return """<root xmlns:stream="http://etherx.jabber.org/streams">$stripped</root>"""
    }
}

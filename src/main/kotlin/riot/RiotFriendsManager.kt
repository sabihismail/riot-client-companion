package riot

import db.DatabaseImpl
import db.models.RiotAccountsTable
import db.models.RiotFavouritesTable
import db.models.RiotFriendsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import riot.models.RiotAccountTokens
import riot.models.RiotFriend
import riot.xmpp.RiotXmppClient
import util.Logging
import util.LogType
import java.time.LocalDateTime
import java.util.Base64
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val LOG = "[FriendsManager]"

object RiotFriendsManager {

    // accountName → friends (in-memory, source of truth for UI)
    val allFriends = ConcurrentHashMap<String, List<RiotFriend>>()
    val favourites: MutableSet<String> = mutableSetOf()

    var activeAccount: String? = null
        private set

    private var activeTokens: RiotAccountTokens? = null
    private var chatAffinities: Map<String, String> = emptyMap()

    private val xmppPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RiotXmpp").also { it.isDaemon = true }
    }
    private val refreshTimer = Timer("SecondaryRefresh", true)
    private val listeners = mutableListOf<() -> Unit>()

    private val conn = RiotClientConnection(
        onAccountChange = { tokens ->
            activeTokens = tokens
            activeAccount = tokens.accountName
            captureChatAffinities()
            saveOrUpdateAccount(tokens.accountName, ssid = null)
            notifyListeners()
        },
        onFriendsSnapshot = { account, friends -> updateFriends(account, friends) },
        onPresenceUpdate = { puuid, updated -> patchPresence(puuid, updated) },
    )

    fun addChangeListener(l: () -> Unit) { listeners.add(l) }
    private fun notifyListeners() { listeners.forEach { runCatching { it() } } }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        // DatabaseImpl init happens lazily on first access — touch it now
        DatabaseImpl
        loadFromDb()
        conn.start()
        refreshTimer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { refreshSecondaryAccounts() }
        }, 90_000L, 5 * 60_000L)
    }

    fun stop() {
        conn.stop()
        refreshTimer.cancel()
        xmppPool.shutdown()
    }

    // ── In-memory updates ──────────────────────────────────────────────────

    private fun updateFriends(account: String, incoming: List<RiotFriend>) {
        val withMeta = incoming.map { f ->
            f.copy(isFavourite = favourites.contains(f.puuid), ownerAccount = account)
                .also { it.online = f.online; it.gameState = f.gameState; it.mapName = f.mapName; it.gameMode = f.gameMode }
        }
        allFriends[account] = withMeta
        persistFriends(account, withMeta)
        notifyListeners()
    }

    private fun patchPresence(puuid: String, updated: RiotFriend) {
        val account = activeAccount ?: return
        val list = allFriends[account]?.toMutableList() ?: return
        val idx = list.indexOfFirst { it.puuid == puuid }
        if (idx == -1) return
        val old = list[idx]
        list[idx] = old.copy(
            online = updated.online,
            gameState = updated.gameState,
            mapName = updated.mapName,
            gameMode = updated.gameMode,
            partyId = updated.partyId,
        ).also { it.isFavourite = old.isFavourite }
        allFriends[account] = list
        notifyListeners()
    }

    // ── Secondary accounts (XMPP) ──────────────────────────────────────────

    private fun refreshSecondaryAccounts() {
        val active = activeAccount
        loadAllSsids().forEach { (account, ssid) ->
            if (account == active) return@forEach
            xmppPool.submit { fetchViaXmpp(account, ssid) }
        }
    }

    private fun fetchViaXmpp(account: String, ssid: String) {
        Logging.log("$LOG XMPP refresh: $account", LogType.DEBUG)
        val tokens = conn.reauthWithSsid(ssid) ?: run {
            Logging.log("$LOG ssid expired for $account — clearing", LogType.DEBUG)
            clearSsid(account)
            return
        }
        val friends = RiotXmppClient.fetchFriends(
            accessToken = tokens.accessToken,
            entitlement = tokens.entitlement,
            chatAffinities = chatAffinities.takeIf { it.isNotEmpty() },
            xmppRegion = tokens.xmppRegion,
            presenceTimeoutMs = 8_000,
        )
        if (friends.isNotEmpty()) {
            updateFriends(account, friends)
            Logging.log("$LOG XMPP done: $account — ${friends.count { it.online }}/${friends.size} online", LogType.DEBUG)
        }
    }

    fun saveSsid(accountName: String, ssid: String) {
        saveOrUpdateAccount(accountName, ssid)
        xmppPool.submit { fetchViaXmpp(accountName, ssid) }
    }

    fun hasSsid(accountName: String): Boolean = transaction {
        RiotAccountsTable.selectAll()
            .where { RiotAccountsTable.accountName eq accountName }
            .singleOrNull()?.get(RiotAccountsTable.ssid)?.isNotBlank() == true
    }

    // ── Favourites ─────────────────────────────────────────────────────────

    fun toggleFavourite(puuid: String) {
        val nowFav = if (favourites.remove(puuid)) false else { favourites.add(puuid); true }
        allFriends.forEach { (account, list) ->
            allFriends[account] = list.map { f ->
                if (f.puuid == puuid) f.copy(isFavourite = nowFav) else f
            }
        }
        transaction {
            if (nowFav) {
                RiotFavouritesTable.insertIgnore { it[RiotFavouritesTable.puuid] = puuid }
            } else {
                RiotFavouritesTable.deleteWhere { RiotFavouritesTable.puuid eq puuid }
            }
        }
        notifyListeners()
    }

    // ── Chat affinities ────────────────────────────────────────────────────

    private fun captureChatAffinities() {
        val lock = readLockfile() ?: return
        val (port, password) = lock
        val token = Base64.getEncoder().encodeToString("riot:$password".toByteArray())
        val resp = conn.get(
            "https://127.0.0.1:$port/client-config/v2/namespace/chat",
            mapOf("Authorization" to "Basic $token", "Accept" to "*/*")
        ) ?: return
        runCatching {
            val json = com.google.gson.Gson().fromJson(resp, com.google.gson.JsonObject::class.java)
            chatAffinities = json.get("chat.affinities")?.asJsonObject
                ?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    fun allFriendsFlat() = allFriends.values.flatten()
    fun onlineFriends() = allFriendsFlat().filter { it.online }

    // ── DB persistence ─────────────────────────────────────────────────────

    private fun loadFromDb() {
        // Favourites
        transaction {
            RiotFavouritesTable.selectAll().forEach { favourites.add(it[RiotFavouritesTable.puuid]) }
        }

        // Cached friends per account
        transaction {
            RiotFriendsTable.selectAll()
                .groupBy { it[RiotFriendsTable.ownerAccount] }
                .forEach { (account, rows) ->
                    allFriends[account] = rows.map { row ->
                        RiotFriend(
                            puuid = row[RiotFriendsTable.puuid],
                            gameName = row[RiotFriendsTable.gameName],
                            gameTag = row[RiotFriendsTable.gameTag],
                            lastOnlineTs = row[RiotFriendsTable.lastOnlineTs],
                            ownerAccount = account,
                            isFavourite = favourites.contains(row[RiotFriendsTable.puuid]),
                        )
                    }
                }
        }

        Logging.log("$LOG Loaded ${allFriends.size} cached accounts, ${favourites.size} favourites from DB", LogType.DEBUG)
    }

    private fun persistFriends(account: String, friends: List<RiotFriend>) {
        transaction {
            val now = LocalDateTime.now()
            RiotFriendsTable.deleteWhere { RiotFriendsTable.ownerAccount eq account }
            friends.forEach { f ->
                RiotFriendsTable.insert { row ->
                    row[puuid] = f.puuid
                    row[gameName] = f.gameName
                    row[gameTag] = f.gameTag
                    row[ownerAccount] = account
                    row[lastOnlineTs] = f.lastOnlineTs
                    row[lastUpdated] = now
                }
            }
        }
    }

    private fun saveOrUpdateAccount(accountName: String, ssid: String?) {
        transaction {
            val existing = RiotAccountsTable.selectAll()
                .where { RiotAccountsTable.accountName eq accountName }
                .singleOrNull()

            if (existing == null) {
                RiotAccountsTable.insert { row ->
                    row[RiotAccountsTable.accountName] = accountName
                    row[RiotAccountsTable.ssid] = ssid
                    row[ssidSavedAt] = ssid?.let { LocalDateTime.now() }
                }
            } else if (ssid != null) {
                RiotAccountsTable.update({ RiotAccountsTable.accountName eq accountName }) { row ->
                    row[RiotAccountsTable.ssid] = ssid
                    row[ssidSavedAt] = LocalDateTime.now()
                }
            } else { /* account already known, no ssid to update */ }
        }
    }

    private fun clearSsid(accountName: String) {
        transaction {
            RiotAccountsTable.update({ RiotAccountsTable.accountName eq accountName }) { row ->
                row[ssid] = null
                row[ssidSavedAt] = null
            }
        }
    }

    private fun loadAllSsids(): Map<String, String> {
        return transaction {
            RiotAccountsTable.selectAll()
                .mapNotNull { row ->
                    val ssid = row[RiotAccountsTable.ssid] ?: return@mapNotNull null
                    row[RiotAccountsTable.accountName] to ssid
                }
                .toMap()
        }
    }
}

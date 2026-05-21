package riot.models

data class RiotFriend(
    val puuid: String,
    val gameName: String,
    val gameTag: String,
    val lastOnlineTs: Long = 0,
    var online: Boolean = false,
    var gameState: String? = null,   // INGAME, PREGAME, MENUS
    var mapName: String? = null,     // e.g. Ascent, Bind
    var gameMode: String? = null,    // competitive, deathmatch, etc
    var partyId: String? = null,
    var ownerAccount: String = "",
    var isFavourite: Boolean = false,
) {
    val displayName: String get() = "$gameName#$gameTag"

    val statusLabel: String get() = when {
        !online -> "Offline"
        gameState == "INGAME" && mapName != null -> "In Game ($mapName)"
        gameState == "INGAME" -> "In Game"
        gameState == "PREGAME" -> "Pre-Game"
        gameState == "MENUS" -> "Online"
        else -> "Online"
    }
}

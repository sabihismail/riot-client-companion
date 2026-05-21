package db.models

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object RiotFriendsTable : IntIdTable("riot_friends") {
    val puuid        = text("puuid")
    val gameName     = text("game_name")
    val gameTag      = text("game_tag")
    val ownerAccount = text("owner_account")
    val lastOnlineTs = long("last_online_ts").default(0)
    val lastUpdated  = datetime("last_updated")

    init {
        // one row per (puuid, account) pair — same friend can be on multiple accounts' lists
        uniqueIndex("uix_riot_friend", puuid, ownerAccount)
    }
}

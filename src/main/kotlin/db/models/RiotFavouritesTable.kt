package db.models

import org.jetbrains.exposed.dao.id.IntIdTable

object RiotFavouritesTable : IntIdTable("riot_favourites") {
    val puuid = text("puuid").uniqueIndex()
}

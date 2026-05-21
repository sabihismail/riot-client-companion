package db

import db.models.ChallengeMappingTable
import db.models.GenericKeyValueTable
import db.models.MasteryChestTable
import db.models.RiotAccountsTable
import db.models.RiotFavouritesTable
import db.models.RiotFriendsTable
import league.LeagueConnection
import league.models.MasteryChestInfo
import league.models.SummonerInfo
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import ui.controllers.MainViewController.Companion.CHEST_MAX_COUNT
import ui.controllers.MainViewController.Companion.CHEST_WAIT_TIME
import java.sql.Connection
import java.time.Duration
import java.time.LocalDateTime

object DatabaseImpl {
    init {
        Database.connect("jdbc:sqlite:database.db", "org.sqlite.JDBC")

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                MasteryChestTable,
                GenericKeyValueTable,
                ChallengeMappingTable,
                RiotFriendsTable,
                RiotAccountsTable,
                RiotFavouritesTable,
            )
        }
    }

    fun getValue(key: GenericKeyValueKey): String? {
        var str: String? = null

        val keyId = key.name + "_" + LeagueConnection.summonerInfo.uniqueId
        transaction {
            val result = GenericKeyValueTable.selectAll().where { GenericKeyValueTable.key eq keyId }.singleOrNull() ?: return@transaction
            str = result[GenericKeyValueTable.value]
        }

        return str
    }

    fun setValue(keyIn: GenericKeyValueKey, valueIn: Any) {
        val keyId = keyIn.name + "_" + LeagueConnection.summonerInfo.uniqueId
        transaction {
            val query: (SqlExpressionBuilder.() -> Op<Boolean>) = { GenericKeyValueTable.key eq keyId }
            if (GenericKeyValueTable.selectAll().where(query).count() == 1L) {
                GenericKeyValueTable.update(query) { update ->
                    update[value] = valueIn.toString()
                }
            } else {
                GenericKeyValueTable.insert { insert ->
                    insert[key] = keyId
                    insert[value] = valueIn.toString()
                }
            }
        }
    }

    fun setMasteryInfo(summonerInfo: SummonerInfo, masteryChestInfo: MasteryChestInfo) {
        val finalDate = if (CHEST_MAX_COUNT == masteryChestInfo.chestCount) {
            LocalDateTime.now()
        } else {
            val count = ((CHEST_MAX_COUNT - masteryChestInfo.chestCount - 1) * CHEST_WAIT_TIME + masteryChestInfo.remainingTime) * 24 * 60

            LocalDateTime.now().plusMinutes(count.toLong())
        }

        transaction {
            val uniqueAccountId = summonerInfo.uniqueId

            val query: (SqlExpressionBuilder.() -> Op<Boolean>) = { MasteryChestTable.accountId eq uniqueAccountId }
            if (MasteryChestTable.selectAll().where(query).count() >= 1) {
                MasteryChestTable.update(query) { update ->
                    update[name] = summonerInfo.displayName
                    update[lastBoxDate] = finalDate
                }
            } else {
                MasteryChestTable.insert { insert ->
                    insert[accountId] = uniqueAccountId
                    insert[name] = summonerInfo.displayName
                    insert[lastBoxDate] = finalDate
                }
            }
        }
    }

    fun getMasteryChestInfo(): MutableList<ResultRow> {
        val lst = mutableListOf<ResultRow>()
        transaction {
            val now = LocalDateTime.now()
            val elements = MasteryChestTable.selectAll().sortedWith(
                compareBy<ResultRow> { entry -> Duration.between(now, entry[MasteryChestTable.lastBoxDate]) <= Duration.ZERO }
                    .thenBy { entry -> entry[MasteryChestTable.lastBoxDate] }
                    .thenBy { entry -> entry[MasteryChestTable.name] }
            )

            lst.addAll(elements)
        }

        return lst
    }
}
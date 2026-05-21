package db.models

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object RiotAccountsTable : IntIdTable("riot_accounts") {
    val accountName  = text("account_name").uniqueIndex()
    val ssid         = text("ssid").nullable()
    val ssidSavedAt  = datetime("ssid_saved_at").nullable()
}

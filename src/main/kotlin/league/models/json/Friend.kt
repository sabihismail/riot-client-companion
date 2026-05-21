@file:Suppress("unused")

package league.models.json

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable
import kotlin.reflect.full.declaredMemberProperties

val PRODUCT_MAPPING = mapOf(LolProductName.VALORANT to "valorant", LolProductName.LEAGUE_OF_LEGENDS to "league")

@Serializable
enum class LolProductName {
    @SerializedName("league_of_legends")
    LEAGUE_OF_LEGENDS,

    @SerializedName("valorant")
    VALORANT;

    override fun toString(): String {
        return PRODUCT_MAPPING[this] ?: ""
    }
}

@Serializable
enum class LolAvailability {
    @SerializedName("chat")
    CHAT,

    @SerializedName("offline")
    OFFLINE,

    @SerializedName("mobile")
    MOBILE,
}


@Suppress("MemberVisibilityCanBePrivate")
@Serializable
class Friend {
    var availability: LolAvailability = LolAvailability.OFFLINE
    // var displayGroupId: Int? = null
    // var displayGroupName: String? = null
    var gameName: String? = null
    var gameTag: String? = null
    // var groupId: Int? = null
    // var groupName: String? = null
    // var icon: Int? = null
    // var id: String? = null
    // var isP2PConversationMuted: Boolean? = null
    // var lastSeenOnlineTimestamp: String? = null
    // var lol: Any? = null
    var name: String? = null
    var note: String? = null
    // var patchline: String? = null
    // var pid: String? = null
    // var platformId: String? = null
    var product: LolProductName? = null
    // var productName: String? = null
    // var puuid: String? = null
    var statusMessage: String? = null
    // var summary: String? = null
    var summonerId: Long? = null
    // var time: Long? = null
    var ownerFriend = ""

    // Populated by RiotClientPoller for Valorant presence
    @com.google.gson.annotations.Expose(serialize = false, deserialize = false)
    var gameState: String? = null

    // True when loaded from cache, not live
    @com.google.gson.annotations.Expose(serialize = false, deserialize = false)
    var isCached: Boolean = false
}

val lolChatFriendResourceImplPropertyMap by lazy {
    Friend::class.declaredMemberProperties
}

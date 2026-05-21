package riot.models

data class RiotAccountTokens(
    val accountName: String,
    val accessToken: String,
    val entitlement: String,
    val xmppRegion: String?,
)

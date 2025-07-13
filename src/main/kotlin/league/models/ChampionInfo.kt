package league.models

import com.stirante.lolclient.ClientApi
import league.models.enums.ChampionOwnershipStatus
import league.models.enums.ChampionRole
import league.models.json.EternalsInfo
import league.models.json.EternalsSetInfo
import util.StringUtil
import util.constants.GenericConstants.ETERNALS_DESCRIPTION_REGEX

data class ChampionInfo(val id: Int = -1, val name: String = "None", val ownershipStatus: ChampionOwnershipStatus = ChampionOwnershipStatus.NOT_OWNED,
                        val masteryPoints: Int = 1, val currentMasteryPoints: Int = 1, val nextLevelMasteryPoints: Int = 2, val level: Int = 0, val tokens: Int = 0,
                        var isSummonerSelectedChamp: Boolean = false, var eternalInfo: Map<Int, Boolean> = mapOf(), var roles: Set<ChampionRole>? = null,
                        var idealChampionToMasterEntry: Int = -1, val clientApi: ClientApi? = null, val masteryBoxRewards: String = "") {
    lateinit var completedChallenges: MutableSet<Int>
    lateinit var availableChallenges: MutableSet<Int>

    private var latestEternalInfo: MutableList<EternalsInfo> = mutableListOf()

    val nameLower by lazy {
        name.lowercase()
    }

    val percentageUntilNextLevel by lazy {
        if (level in 1..4)
            " (${"%.1f".format((currentMasteryPoints.toDouble()/(nextLevelMasteryPoints + currentMasteryPoints)) * 100)}%)"
        else
            ""
    }

    fun getEternals(showEternals: Boolean): List<EternalsInfo> {
        return if (eternalInfo.any { it.value } && showEternals) {
            if (latestEternalInfo.isEmpty()) {
                val apiResponse = clientApi?.executeGet("/lol-statstones/v2/player-statstones-self/${id}", Array<EternalsSetInfo>::class.java)?.responseObject

                latestEternalInfo = apiResponse?.filter { set -> set.name != "Starter Series" && set.stonesOwned > 0 }
                    ?.onEach { set -> set.statstones.onEach { stone -> stone.setName = set.name.toString() } }
                    ?.flatMap { set -> set.statstones }
                    ?.toMutableList()!!

                val starterEternals = apiResponse.firstOrNull { set -> set.name == "Starter Series" }?.statstones

                val nameMap = mapOf("Epic Monsters Killed" to "M", "Structures Destroyed" to "T", "Takedowns" to "K")
                val sortMap = mapOf("K" to 1, "T" to 2, "M" to 3)

                val joinedStarterSeries = starterEternals?.map { stone -> Pair(stone, nameMap[stone.name]) }
                    ?.sortedBy { sortMap[it.second] }
                latestEternalInfo.add(EternalsInfo().apply {
                    name = joinedStarterSeries?.joinToString(", ") {
                        "${it.second}${it.first.formattedMilestoneLevel}"
                    }
                    setName = "Starter Series"
                    setComplete = joinedStarterSeries?.all { it.first.formattedMilestoneLevel.toInt() > 5 } == true
                    formattedMilestoneLevel = "9999"
                })
            }

            if (latestEternalInfo.filterNot { it.setName == "Starter Series" }.groupBy { stone -> stone.setName }
                .any { it.value.all { stone -> stone.formattedMilestoneLevel.toInt() >= 5 } } or latestEternalInfo.first { it.setName == "Starter Series" }.setComplete) {
                return emptyList()
            }

            return latestEternalInfo
        } else {
            emptyList()
        }
    }

    val maxEternal by lazy {
        if (latestEternalInfo.isEmpty()) {
            getEternals(true)
        }

        latestEternalInfo.filter { it.setName != "Starter Series" }.maxByOrNull { it.formattedMilestoneLevel.toInt() }
    }

    val maxEternalStr by lazy {
        val highest = maxEternal

        if (highest != null && highest.formattedMilestoneLevel.toInt() >= 15) return@lazy ""

        val regexStr = StringUtil.getSafeRegex(ETERNALS_DESCRIPTION_REGEX, highest?.description ?: "")

        " - $regexStr${highest?.formattedMilestoneLevel}"
    }

    override fun toString(): String {
        return "ChampionInfo(id=$id, name='$name', ownershipStatus=$ownershipStatus, masteryPoints=$masteryPoints, currentMasteryPoints=$currentMasteryPoints, " +
                "nextLevelMasteryPoints=$nextLevelMasteryPoints, level=$level, tokens=$tokens, isSummonerSelectedChamp=$isSummonerSelectedChamp, eternalInfo=$eternalInfo, " +
                "roles=$roles, idealChampionToMasterEntry=$idealChampionToMasterEntry, clientApi=$clientApi, masteryBoxRewards='$masteryBoxRewards', " +
                "completedChallenges=$completedChallenges, availableChallenges=$availableChallenges, latestEternalInfo=$latestEternalInfo, " +
                "nameLower='$nameLower', percentageUntilNextLevel='$percentageUntilNextLevel', maxEternal=$maxEternal, maxEternalStr='$maxEternalStr')"
    }
}
package league.api

import javafx.scene.effect.*
import javafx.scene.image.Image
import javafx.scene.paint.Color
import league.models.ChampionInfo
import league.models.enums.CacheType
import league.models.enums.ChallengeLevel
import league.models.enums.ChampionOwnershipStatus
import league.models.enums.Role
import league.models.json.RoleMapping
import league.models.json.communitydragon.ApiChallengeResponse
import league.models.json.communitydragon.ApiEternalsResponse
import league.models.json.communitydragon.ApiQueueInfoResponse
import util.LogType
import util.Logging
import util.StringUtil
import util.constants.ViewConstants.IMAGE_WIDTH
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.notExists


object LeagueCommunityDragonApi {
    private val EXECUTOR_SERVICE = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1) // Sub 1 for UI thread?
    private val EXECUTOR_SET = setOf<Path>()

    var VERSION = "latest"

    var CHAMPION_ROLE_MAPPING = hashMapOf<Role, HashMap<Int, Float>>()
    var QUEUE_MAPPING = hashMapOf<Int, ApiQueueInfoResponse>()
    var CHALLENGE_THRESHOLD_MAPPING = hashMapOf<String, Long>()
    var ETERNALS_MAPPING = hashMapOf<String, List<Pair<Int, String>>>()
    private var LOOT_TRANSLATION_MAPPING = hashMapOf<String, String>()

    private val versionEscaped get() = VERSION.replace(".", "_")

    val CHAMPION_PORTRAIT_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/champion-icons/%s.png"
    val CHALLENGE_IMAGE_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/game/assets/challenges/config/%s/tokens/%s.png"
    private val CHAMPION_ROLE_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/plugins/rcp-fe-lol-champion-statistics/global/default/rcp-fe-lol-champion-statistics.js"
    private val QUEUE_TYPE_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/queues.json"
    private val CHALLENGES_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/challenges.json"
    private val ETERNALS_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/statstones.json"
    private val LOOT_NAME_ENDPOINT get() = "https://raw.communitydragon.org/$VERSION/plugins/rcp-fe-lol-loot/global/default/trans.json"

    fun getLootEntity(lootName: String): String? {
        CacheUtil.checkIfJsonCached(CacheType.JSON, ::LOOT_TRANSLATION_MAPPING, ::populateLootTranslationMapping, append = versionEscaped)

        return LOOT_TRANSLATION_MAPPING[lootName]
    }

    fun getChampionsByRole(role: Role): List<Int> {
        CacheUtil.checkIfJsonCached(CacheType.JSON, ::CHAMPION_ROLE_MAPPING, ::populateRoleMapping, append = versionEscaped)

        return CHAMPION_ROLE_MAPPING[role]?.map { it.key }!!
    }

    fun getQueueMapping(id: Int): ApiQueueInfoResponse {
        CacheUtil.checkIfJsonCached(CacheType.JSON, ::QUEUE_MAPPING, ::populateQueueMapping, append = versionEscaped)

        return QUEUE_MAPPING[id]!!
    }

    fun getChallengeThreshold(id: String, challengeLevel: ChallengeLevel): Long {
        CacheUtil.checkIfJsonCached(CacheType.JSON, ::CHALLENGE_THRESHOLD_MAPPING, ::populateChallengeThresholdMapping, append = versionEscaped)

        return CHALLENGE_THRESHOLD_MAPPING[id + challengeLevel.name]!!
    }

    fun getEternal(contentId: String): List<Pair<Int, String>> {
        CacheUtil.checkIfJsonCached(CacheType.JSON, ::ETERNALS_MAPPING, ::populateEternalsMapping, append = versionEscaped)

        return ETERNALS_MAPPING[contentId]!!
    }

    fun getImage(t: CacheType, vararg params: Any): Image {
        val path = getImagePath(t, forceReturn = true, *params)

        return Image(path!!.toUri().toString())
    }

    private fun enqueueAllImageDownloads(cacheType: CacheType) {
        val ids = when (cacheType) {
            CacheType.CHAMPION -> getChampionIds()
            CacheType.CHALLENGE -> getChallengeImageMapping()
            else -> emptyList()
        }

        val path = CacheUtil.getPath(cacheType)
        ids.forEach {
            val imagePath = path.resolve(it.joinToString("-") + ".png")

            addToExecutorService(cacheType, imagePath, forceReturn = false, *it)
        }
    }

    private fun addToExecutorService(cacheType: CacheType, imagePath: Path, forceReturn: Boolean = false, vararg params: Any) {
        if (EXECUTOR_SET.contains(imagePath)) return

        val obj = EXECUTOR_SERVICE.submit(Callable {
            val urlStr = CacheUtil.getEndpoint(cacheType)?.format(*params)

            val connection = URL(urlStr).openConnection()
            connection.setRequestProperty("User-Agent", "RiotClientCompanion")

            try {
                val readableByteChannel = Channels.newChannel(connection.getInputStream())
                val fileOutputStream = FileOutputStream(imagePath.toFile())

                fileOutputStream.channel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE)

                Logging.log("Image Download: '$imagePath'", LogType.INFO)
            } catch (e: FileNotFoundException) {
                throw e
            }

            return@Callable imagePath
        })

        if (forceReturn) {
            obj.get()
        }
    }

    fun getImagePath(cacheType: CacheType, forceReturn: Boolean, vararg params: Any): Path? {
        val path = CacheUtil.getPath(cacheType)

        if (path.notExists() || Files.list(path).count() == 0L) {
            if (path.notExists()) {
                path.createDirectory()
            }

            enqueueAllImageDownloads(cacheType)
        }

        val imagePath = path.resolve(params.joinToString("-") + ".png")
        if (!imagePath.exists()) {
            addToExecutorService(cacheType, imagePath, forceReturn = forceReturn, *params)
        }

        return imagePath
    }

    fun getChampionImageEffect(championInfo: ChampionInfo): Effect {
        if (championInfo.ownershipStatus == ChampionOwnershipStatus.NOT_OWNED || championInfo.ownershipStatus == ChampionOwnershipStatus.RENTAL ||
            championInfo.ownershipStatus == ChampionOwnershipStatus.FREE_TO_PLAY) {
            return ColorAdjust(0.0, -1.0, -0.7, -0.1)
        }

        val colorInput = ColorInput().apply {
            width = IMAGE_WIDTH
            height = IMAGE_WIDTH

//            paint = if (championInfo.masteryBoxRewards == "")
//                CHAMPION_STATUS_UNAVAILABLE_CHEST_COLOR
//            else
//                CHAMPION_STATUS_AVAILABLE_CHEST_COLOR
        }

        val blend = Blend().apply {
            mode = BlendMode.SRC_OVER
            opacity = 0.7
            topInput = colorInput
        }

        return blend
    }

    fun getChallengeImageEffect(currentLevel: ChallengeLevel?): Effect? {
        if (currentLevel != ChallengeLevel.NONE) return null

        return ColorAdjust(0.0, -0.5, -0.5, -0.1)
    }

    private fun getChampionIds(): List<Array<Int>> {
        CacheUtil.checkIfJsonCached(CacheType.JSON, ::CHAMPION_ROLE_MAPPING, ::populateRoleMapping, append = versionEscaped)

        return CHAMPION_ROLE_MAPPING.map { it.value }.flatMap { it.keys.map { key -> arrayOf(key) } }
    }

    private fun populateQueueMapping() {
        QUEUE_MAPPING.clear()

        val jsonStr = sendRequest(QUEUE_TYPE_ENDPOINT)
        val json = StringUtil.parseJsonArray<ApiQueueInfoResponse>(jsonStr)

        QUEUE_MAPPING = HashMap(json.associateBy { it.id })
        CacheUtil.addJsonCache(CacheType.JSON, ::QUEUE_MAPPING, append = versionEscaped)
    }

    private fun populateRoleMapping() {
        CHAMPION_ROLE_MAPPING.clear()

        val jsonStr = sendRequest(CHAMPION_ROLE_ENDPOINT)
        val json = StringUtil.extractAndAggregateJson<RoleMapping>(jsonStr, arrayOf("BOTTOM", "TOP", "MIDDLE", "JUNGLE", "SUPPORT"))

        CHAMPION_ROLE_MAPPING[Role.TOP] = json.top
        CHAMPION_ROLE_MAPPING[Role.JUNGLE] = json.jungle
        CHAMPION_ROLE_MAPPING[Role.MIDDLE] = json.middle
        CHAMPION_ROLE_MAPPING[Role.BOTTOM] = json.bottom
        CHAMPION_ROLE_MAPPING[Role.SUPPORT] = if (json.support.isNullOrEmpty()) json.utility!! else json.support
        CacheUtil.addJsonCache(CacheType.JSON, ::CHAMPION_ROLE_MAPPING, append = versionEscaped)
    }

    private fun getChallengeImageMapping(): List<Array<String>> {
        val jsonStr = sendRequest(CHALLENGES_ENDPOINT)
        val json = StringUtil.extractJSONFromString<ApiChallengeResponse>(jsonStr)

        return json.challenges.flatMap { it.value.levelToIconPath.keys.map { rank -> arrayOf(it.key.toString(), rank.lowercase()) } }
    }

    private fun populateChallengeThresholdMapping() {
        val jsonStr = sendRequest(CHALLENGES_ENDPOINT)
        val json = StringUtil.extractJSONFromString<ApiChallengeResponse>(jsonStr)

        CHALLENGE_THRESHOLD_MAPPING = HashMap(json.challenges.values.flatMap { c -> c.thresholds!!.map { (k, v) -> (c.name!! + k.name) to v.value!!.toLong() } }
            .toMap())
        CacheUtil.addJsonCache(CacheType.JSON, ::CHALLENGE_THRESHOLD_MAPPING, append = versionEscaped)
    }

    private fun populateEternalsMapping() {
        val jsonStr = sendRequest(ETERNALS_ENDPOINT)
        val json = StringUtil.extractJSONFromString<ApiEternalsResponse>(jsonStr)

        ETERNALS_MAPPING = HashMap(json.statstoneData.flatMap { data ->
            data.statstones.map { it.contentId to it.getMilestoneValues() }
        }.toMap())
        CacheUtil.addJsonCache(CacheType.JSON, ::ETERNALS_MAPPING, append = versionEscaped)
    }

    private fun populateLootTranslationMapping() {
        val jsonStr = sendRequest(LOOT_NAME_ENDPOINT)
        val json = StringUtil.extractJSONFromString<Map<String, String>>(jsonStr)

        LOOT_TRANSLATION_MAPPING = HashMap(json)
        CacheUtil.addJsonCache(CacheType.JSON, ::LOOT_TRANSLATION_MAPPING, append = versionEscaped)
    }

    private fun sendRequest(url: String): String {
        val connection = URL(url).openConnection()
        connection.setRequestProperty("User-Agent", "RiotClientCompanion")

        return connection.getInputStream().bufferedReader().use { it.readText() }
    }
}
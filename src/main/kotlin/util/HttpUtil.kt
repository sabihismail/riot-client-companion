package util

import league.util.LeagueConnectionUtil
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.io.entity.EntityUtils
import util.LogMessageType
import util.LogType
import util.Logging
import util.constants.GenericConstants.GSON
import java.net.URI

object HttpUtil {
    private val HTTP_CLIENT = LeagueConnectionUtil.createHttpClient()

    fun makeGetRequest(url: String, headers: List<Header> = listOf()): String {
        Logging.log("GET $url", LogType.DEBUG, messageType = LogMessageType.HTTP)

        val method = HttpGet(URI(url))
        for (header in headers) {
            method.addHeader(header)
        }

        HTTP_CLIENT.execute(method).use { response ->
            if (response.code != 200) {
                Logging.log("GET $url -> ${response.code}", LogType.WARNING, messageType = LogMessageType.HTTP)
            } else {
                val t = LeagueConnectionUtil.dumpStream(response.entity.content)
                EntityUtils.consume(response.entity)

                Logging.log("GET $url -> ${response.code} (${t?.length ?: 0} bytes)", LogType.DEBUG, messageType = LogMessageType.HTTP)
                Logging.log("Response: $t", LogType.VERBOSE, messageType = LogMessageType.HTTP)

                return t ?: ""
            }
        }

        return ""
    }

    inline fun <reified T: Any> makeGetRequestJson(url: String, headers: List<Header> = listOf()): T? {
        val stringResponse = makeGetRequest(url, headers = headers)

        if (stringResponse.isBlank()) return null

        return GSON.fromJson(stringResponse, T::class.java)
    }
}
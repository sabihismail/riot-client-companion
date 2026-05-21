package util

import util.constants.GenericConstants.GSON
import java.nio.file.Paths
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.reflect.full.memberProperties

class Settings {
    var mainId = 0L
    var disenchantIds = ""
    var developerIds = ""
    var debugMode = false

    companion object {
        val INSTANCE: Settings by lazy {
            val configFile = Paths.get("config.json")

            if (!configFile.exists()) {
                val settings = Settings()
                val json = GSON.toJson(settings)

                configFile.createFile()
                configFile.writeText(json)

                invalidConfigurationData()
            }

            val dataStr = configFile.readText()
            val data = GSON.fromJson(dataStr, Settings::class.java)

            for (property in Settings::class.memberProperties) {
                val propertyValue = property.get(data)

                if (propertyValue.toString().isBlank()) {
                    invalidConfigurationData()
                }
            }

            data
        }

        private fun invalidConfigurationData() {
            Logging.log("Invalid configuration in 'config.json'", LogType.ERROR)
        }
    }
}
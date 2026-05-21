
import tornadofx.launch
import ui.MainApp
import util.LogType
import util.Logging
import util.Settings


const val DEBUG_FAKE_UI_DATA_ARAM = false
const val DEBUG_FAKE_UI_DATA_NORMAL = false
const val DEBUG_FAKE_UI_DATA_UPDATED_CHALLENGES = false

fun main(args: Array<String>) {
    if (System.getenv("DEBUG") != null || Settings.INSTANCE.debugMode) Logging.logMode = LogType.DEBUG
    launch<MainApp>(args)
}

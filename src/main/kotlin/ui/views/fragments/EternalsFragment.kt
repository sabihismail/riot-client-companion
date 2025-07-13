package ui.views.fragments

import javafx.geometry.Pos
import javafx.scene.text.Font
import league.models.json.EternalsInfo
import tornadofx.*
import ui.views.util.blackLabel
import util.StringUtil
import util.constants.GenericConstants.ETERNALS_DESCRIPTION_REGEX

class EternalsFragment : Fragment() {
    val eternal: List<EternalsInfo> by param()
    val fontSizeIn: Double by param()

    override var root = stackpane {
        alignment = Pos.BOTTOM_LEFT
    }

    init {
        if (eternal.isNotEmpty()) {
            set(eternal)
        }
    }

    fun set(newEternal: List<EternalsInfo>) {
        val vBox = vbox {
            newEternal.filter { it.setName != "Starter Series" }
                .sortedByDescending { it.formattedMilestoneLevel }
                .filter { it.formattedMilestoneLevel.toInt() < 5 }
                .forEach {
                    val regex = StringUtil.getSafeRegex(ETERNALS_DESCRIPTION_REGEX, it.description)

                    blackLabel(regex + "Lvl ${it.formattedMilestoneLevel} - ${it.formattedValue}/${it.nextMilestone}", fontSize = fontSizeIn, isWrapText = false) {
                        val txt = if (it.formattedMilestoneLevel.toInt() >= 4) {
                            it.description
                        } else {
                            "${it.description} (${it.summaryThreshold})"
                        }

                        tooltip(txt) {
                            style {
                                font = Font.font(fontSizeIn)
                            }
                        }
                    }
                }

            val starters = newEternal.firstOrNull { it.setName == "Starter Series" }
            if (starters != null) {
                blackLabel(starters.name.toString(), fontSize = fontSizeIn)
            }
        }

        root.children.clear()
        root.add(vBox)
    }
}
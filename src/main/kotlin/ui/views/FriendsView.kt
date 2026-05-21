package ui.views

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.collections.transformation.SortedList
import javafx.geometry.Pos
import javafx.scene.control.TableView
import riot.RiotFriendsManager
import riot.models.RiotFriend
import tornadofx.*

enum class FriendSort { BY_ACCOUNT, BY_NAME, BY_FAVOURITES }

class FriendsView : View("Friends") {

    private val sortMode = SimpleObjectProperty(FriendSort.BY_ACCOUNT)
    private val onlineOnly = SimpleBooleanProperty(false)
    private val search = SimpleStringProperty("")

    private val source = FXCollections.observableArrayList<RiotFriend>()
    private val filtered = FilteredList(source)
    private val sorted = SortedList(filtered)

    override val root = borderpane {
        prefWidth = 740.0
        prefHeight = 600.0

        top = vbox(spacing = 6.0) {
            paddingAll = 10.0

            hbox(spacing = 8.0) {
                alignment = Pos.CENTER_LEFT
                label("Sort:")
                val tg = togglegroup()
                radiobutton("By Account", tg) {
                    isSelected = true
                    action { sortMode.set(FriendSort.BY_ACCOUNT); refreshSort() }
                }
                radiobutton("By Name", tg) {
                    action { sortMode.set(FriendSort.BY_NAME); refreshSort() }
                }
                radiobutton("Favourites First", tg) {
                    action { sortMode.set(FriendSort.BY_FAVOURITES); refreshSort() }
                }
            }

            hbox(spacing = 12.0) {
                alignment = Pos.CENTER_LEFT
                checkbox("Online only", onlineOnly) { action { applyFilter() } }
                label("Search:")
                textfield(search) {
                    prefWidth = 180.0
                    textProperty().addListener { _, _, _ -> applyFilter() }
                }
                spacer()
                label("") {
                    style = "-fx-text-fill: grey; -fx-font-size: 11;"
                    RiotFriendsManager.addChangeListener {
                        runLater { text = RiotFriendsManager.activeAccount ?: "Not connected" }
                    }
                }
            }
        }

        center = tableview(sorted) {
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY

            // Star / favourite column — value = full RiotFriend so cellFormat can read puuid
            column<RiotFriend, RiotFriend>("★") { ReadOnlyObjectWrapper(it.value) }.apply {
                maxWidth = 36.0; minWidth = 36.0
                cellFormat { friend ->
                    text = if (friend.isFavourite) "★" else "☆"
                    style = if (friend.isFavourite) "-fx-text-fill: gold; -fx-font-size: 14;" else "-fx-text-fill: grey; -fx-font-size: 14;"
                    setOnMouseClicked { RiotFriendsManager.toggleFavourite(friend.puuid) }
                }
            }

            column<RiotFriend, String>("Name") { SimpleStringProperty(it.value.displayName) }.apply {
                minWidth = 170.0
            }

            column<RiotFriend, String>("Status") { SimpleStringProperty(it.value.statusLabel) }.apply {
                minWidth = 160.0
                cellFormat { label ->
                    text = label
                    style = when {
                        label.startsWith("In Game") -> "-fx-text-fill: #4fc3f7;"
                        label == "Pre-Game" -> "-fx-text-fill: #ffb74d;"
                        label == "Offline" -> "-fx-text-fill: #888888;"
                        else -> ""
                    }
                }
            }

            column<RiotFriend, String>("Mode") {
                SimpleStringProperty(it.value.gameMode?.replaceFirstChar { c -> c.uppercase() } ?: "")
            }.apply { minWidth = 100.0 }

            column<RiotFriend, String>("Account") { SimpleStringProperty(it.value.ownerAccount) }.apply {
                minWidth = 130.0
                cellFormat { acct ->
                    text = acct
                    style = if (acct == RiotFriendsManager.activeAccount) "" else "-fx-text-fill: #aaaaaa;"
                }
            }
        }

        bottom = hbox(spacing = 8.0) {
            paddingAll = 8.0
            alignment = Pos.CENTER_LEFT
            label("") {
                RiotFriendsManager.addChangeListener {
                    runLater {
                        val online = RiotFriendsManager.onlineFriends().size
                        val total = RiotFriendsManager.allFriendsFlat().size
                        val accounts = RiotFriendsManager.allFriends.size
                        text = "$online online / $total total  ·  $accounts account${if (accounts != 1) "s" else ""}"
                    }
                }
            }
        }
    }

    init {
        RiotFriendsManager.addChangeListener { runLater { reload() } }
        RiotFriendsManager.start()
        reload()
    }

    private fun reload() {
        source.setAll(RiotFriendsManager.allFriendsFlat())
        applyFilter()
        refreshSort()
    }

    private fun applyFilter() {
        val q = search.value.trim().lowercase()
        val oo = onlineOnly.value
        filtered.setPredicate { f ->
            if (oo && !f.online) return@setPredicate false
            if (q.isEmpty()) return@setPredicate true
            f.displayName.lowercase().contains(q) ||
                f.ownerAccount.lowercase().contains(q) ||
                (f.gameMode?.lowercase()?.contains(q) == true)
        }
    }

    private fun refreshSort() {
        sorted.comparator = when (sortMode.value!!) {
            FriendSort.BY_ACCOUNT -> compareBy({ it.ownerAccount }, { !it.online }, { it.displayName.lowercase() })
            FriendSort.BY_NAME -> compareBy({ !it.online }, { it.displayName.lowercase() })
            FriendSort.BY_FAVOURITES -> compareBy({ !it.isFavourite }, { !it.online }, { it.displayName.lowercase() })
        }
    }

    fun onClose() { /* manager runs in background */ }
}

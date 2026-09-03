package com.photobooth.ui

import com.photobooth.i18n.Strings
import com.photobooth.session.OverlaySelection
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * The operator screen for the optional second display: the layout gallery
 * plus one big red "take the photo" button. No download, no result view —
 * after a shot it just stays as it is. Same interaction as the phone
 * `/choose` page, rendered natively.
 *
 * `PhotoBoothApp` owns the [javafx.stage.Stage]; this only builds [root] and
 * exposes the two hooks it needs. Styled by `.picker-*` in `kiosk/kiosk.css`.
 */
class PickerView(
    private val overlaySelection: OverlaySelection,
    private val strings: Strings,
) {
    /** Called when the red button is pressed; returns whether a shoot actually started (false = booth busy). */
    var onShoot: () -> Boolean = { false }

    private val tilesByName = LinkedHashMap<String, VBox>()

    private val gallery = FlowPane(24.0, 24.0).apply {
        styleClass += "picker-grid"
        alignment = Pos.CENTER
        overlaySelection.available().forEach { file ->
            val tile = tile(file.name, file.nameWithoutExtension, Image(file.toURI().toString(), 260.0, 0.0, true, true, true))
            tilesByName[file.name] = tile
            children.add(tile)
        }
    }

    // A round arcade-style button — size / look / the "pushed in" press are all in
    // `.shoot-button` in kiosk.css (so don't pin a maxWidth here, it'd beat the CSS).
    private val shootButton = Button(strings.t("take_photo_button")).apply {
        styleClass += "shoot-button"
        isWrapText = true
        setOnAction { onShoot() }
    }

    val root: Parent = BorderPane().apply {
        styleClass += "picker-root"
        top = Label(strings.t("choose_overlay_heading")).apply { styleClass += "picker-heading" }
        BorderPane.setAlignment(top, Pos.CENTER)
        center = ScrollPane(gallery).apply {
            isFitToWidth = true
            styleClass += "picker-scroll"
        }
        bottom = StackPane(shootButton).apply { styleClass += "picker-actions" }
    }

    init {
        refreshSelection()
    }

    private fun tile(name: String, label: String, thumb: Image): VBox {
        val image = ImageView(thumb).apply { fitWidth = 260.0; isPreserveRatio = true }
        return VBox(8.0, image, Label(label).apply { styleClass += "picker-label" }).apply {
            styleClass += "picker-tile"
            alignment = Pos.CENTER
            setOnMouseClicked {
                overlaySelection.choose(name)
                refreshSelection()
            }
        }
    }

    /** Re-highlight the tile matching `overlaySelection.current()`. Safe to call from any thread. */
    fun refreshSelection() = Platform.runLater {
        val current = overlaySelection.current().name
        tilesByName.forEach { (name, tile) ->
            val selected = name == current
            if (selected && "selected" !in tile.styleClass) tile.styleClass += "selected"
            if (!selected) tile.styleClass -= "selected"
        }
    }

    /** Disable the red button while a shoot is running so a second one can't be started. */
    fun setBusy(busy: Boolean) = Platform.runLater { shootButton.isDisable = busy }
}

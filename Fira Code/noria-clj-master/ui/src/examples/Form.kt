package noria.examples.form

import java.util.function.Function

import noria.*
import noria.layout.*
import noria.scene.*
import noria.scene.UserEvent.*

fun Frame.app(): TRenderObject {
    val label_username = implicitExpr { Text("Username", Color.css("000000"), fontSystemRegular, PhotonApi.Wrap.WORDS) }
    val field_username = implicitExpr { textField(opts = TextFieldOptions(placeholderText = "Username...")) }

    val label_password = implicitExpr { Text("Password", Color.css("000000"), fontSystemRegular, PhotonApi.Wrap.WORDS) }
    val field_password = implicitExpr { textField(opts = TextFieldOptions(placeholderText = "Password...")) }

    val label_repeat = implicitExpr { Text("Repeat password", Color.css("000000"), fontSystemRegular, PhotonApi.Wrap.WORDS) }
    val field_repeat = implicitExpr { textField(opts = TextFieldOptions(placeholderText = "Repeat password...")) }

    val button_login = implicitExpr { buttonPrimary("Register", implicitExpr {{ }}) }
    val button_cancel = implicitExpr { buttonStandard("Cancel", implicitExpr {{ }}) }

    val buttons = implicitExpr { hbox(listOf(button_cancel  to Dimension.hug,
                                             tNil to Dimension.stretch(1f),
                                             tNil to Dimension.px(10f),
                                             button_login   to Dimension.hug)) }

    val table = implicitExpr { grid(listOf(GridCell(implicitExpr { align(label_username, alignY = 0.5f) }),
                                           GridCell(field_username),
                                           GridCell(implicitExpr { align(label_password, alignY = 0.5f) }),
                                           GridCell(field_password),
                                           GridCell(implicitExpr { align(label_repeat, alignY = 0.5f) }),
                                           GridCell(field_repeat),
                                           GridCell(implicitExpr { Nil() }),
                                           GridCell(buttons)),
                                    columns = 2,
                                    columnGap = 10f,
                                    rowGap = 10f,
                                    columnWidths = listOf(Dimension.hug, Dimension.px(200f))) }
    return implicitExpr { align(table, 0.5f, 0.5f) }
}

fun main() {
    val windowId = 1729L
    var windowSize = Size(1250.0f, 950.0f)
    var noriaRef: Noria<Unit, *>? = null

    val updater = StateUpdater { nodeId, transform ->
        // trigger cascade updates and redraws starting from dirtySet
        val dirtySet = mapOf<Long, Function<Any?, Any?>>(nodeId to Function { transform.apply(it) })
        noriaRef = noriaRef!!.revaluate(dirtySet)
        // tell Scene we are finished and it could send frame for rendering
        val scene = noriaRef!!.rootBindings.get(SceneKey) as Scene
        scene.commit(0)
    }

    Thread {
        val scene: Scene = PhotonApi.createWindow(windowId, windowSize, "Noria Forms Example", false)
        val rootBindings: Map<Any, Any> = eventsBindings() + mapOf(SceneKey to scene, UPDATER_KEY to updater)
        noriaRef = NoriaImpl.noria(rootBindings) {
            renderRoot(currentFrame, windowSize, app())
        }
        scene.commit(0)
    }.start()

    PhotonApi.runEventLoop { events ->
        events.forEach { e ->
            if (noriaRef != null) {
                when (e) {
                    is WindowResize -> {
                        windowSize = e.size
                        updater.accept(noriaRef!!.rootId, Identity)
                    }

                    is CloseRequest -> { PhotonApi.stopApplication(); return@forEach }
                    
                    is KeyboardInput ->
                        if (e.keyCode == VirtualKeyCode.Q && e.modifiers.cmd) {
                            PhotonApi.stopApplication()
                            return@forEach
                        }
                }
                handleUserEvent(noriaRef!!, e)
            }
        }
    }
}

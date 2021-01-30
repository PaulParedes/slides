package noria.examples.hello

import java.util.function.Function

import noria.*
import noria.layout.*
import noria.scene.*
import noria.scene.UserEvent.*

fun Frame.app(): TRenderObject {
  val label = implicitExpr { Text("Hello, world!", Color.css("000000"), FontSpec("UI", 32), PhotonApi.Wrap.WORDS) }
  val row = implicitExpr { hbox(listOf(implicitExpr { Nil() } to Dimension.stretch(1f),
                                       label          to Dimension.hug,
                                       implicitExpr { Nil() } to Dimension.stretch(1f))) }
  val column = implicitExpr { vbox(listOf(implicitExpr { Nil() } to Dimension.stretch(1f),
                                          row            to Dimension.hug,
                                          implicitExpr { Nil() } to Dimension.stretch(1f))) }
  return column
}

fun main() {
  val windowId = 1729L
  var windowSize = Size(1250.0f, 950.0f)
  var noriaRef: Noria<Unit, *>? = null

  val updater = StateUpdater { nodeId, transform ->
    // trigger cascade updates and redraws starting from dirtySet
    val dirtySet = mapOf<Long, StateUpdate>(nodeId to Function { transform.apply(it) })
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

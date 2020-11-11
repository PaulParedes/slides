package noria.ui

import noria.*
import noria.scene.*
import java.util.concurrent.atomic.AtomicLong

val AllScenesAttr: Attr<MutableSet<Scene>> = Attr("onair.noria.scenes")

val SealedFrame<*, *, *>.allScenes: MutableSet<Scene>
  get() {
    return AllScenesAttr.get(this.bindings)
  }

val NextWindowId: AtomicLong = AtomicLong(0L)

fun Frame.windowImpl(initialSize: Size, title: String, content: View): Scene {
  val size = state { initialSize }
  val window = resource<Scene>(0,
                               {
                                 val scene = PhotonApi.createWindow(NextWindowId.incrementAndGet(), initialSize, title, true)
                                 allScenes.add(scene)
                                 scene
                               },
                               { scene ->
                                 allScenes.remove(scene)
                                 PhotonApi.destroyWindow(scene.windowId())
                               })
  val scene: Scene = read(window)

  onGlobalEvents(GlobalHandlers(windowId = scene.windowId(),
                                onWindowResize = { e ->
                                  size.update { e.size }
                                }))
  val layoutThunk = expr(content) {
    content(Constraints.tight(read(size)))
  }
  expr {
    val hitCallbacksRegistry = bindings.hitCallbacksRegistry
    val layout = read(layoutThunk)
    val contentRenderer = layout.renderer
    val contentSize = layout.size
    val root = SceneContext(currentFrame, hitCallbacksRegistry, scene).contentRenderer(Rect(Point.ZERO, contentSize))
    scene.setRoot(root.id)
  }
  return scene
}

inline fun Frame.window(initialSize: Size, title: String, crossinline content: Component): Scene {
  return windowImpl(initialSize, title, content())
}


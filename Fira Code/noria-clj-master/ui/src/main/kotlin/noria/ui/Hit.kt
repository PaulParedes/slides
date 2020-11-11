package noria.ui

import noria.Attr
import noria.Bindings
import noria.Frame
import noria.scene.Color
import noria.scene.Point
import noria.scene.Rect
import noria.scene.UserEvent
import java.util.concurrent.ConcurrentHashMap

data class HitCallbacks(val onMouseWheel: TypedHitCallback<UserEvent.MouseWheel>? = null,
                        val onCursorMoved: TypedHitCallback<UserEvent.CursorMoved>? = null,
                        val onMouseInput: TypedHitCallback<UserEvent.MouseInput>? = null)

typealias CallbackId = Long
typealias HitCallbacksRegistry = ConcurrentHashMap<CallbackId, HitCallback>

val HitCallbacksRegistryKey: Attr<HitCallbacksRegistry> = Attr("hitCallbacks")

@Suppress("UNCHECKED_CAST")
val Bindings.hitCallbacksRegistry: HitCallbacksRegistry
  get() = get(HitCallbacksRegistryKey) as HitCallbacksRegistry

val HitCallbacksRegistryReconciler = RegistryReconciler<HitCallback>()

fun SceneContext.registerHitCallback(callback: HitCallback): CallbackId {
  return frame.child(0, HitCallbacksRegistryReconciler, hitCallbacksRegistry to callback).thunkId
}

fun StackingContext.hitBox(bounds: Rect, callbacks: HitCallbacks) {
  val node = nextId()
  scene.rect(node, bounds.origin(), bounds.size(), Color.transparent)

  val eventTypes = ArrayList<UserEvent.EventType>()
  val onCursorMoved = callbacks.onCursorMoved
  val onMouseInput = callbacks.onMouseInput
  val onMouseWheel = callbacks.onMouseWheel
  if (onCursorMoved != null) {
    eventTypes.add(UserEvent.EventType.CURSOR_MOVED)
  }
  if (onMouseInput != null) {
    eventTypes.add(UserEvent.EventType.MOUSE_INPUT)
  }
  if (onMouseWheel != null) {
    eventTypes.add(UserEvent.EventType.MOUSE_WHEEL)
  }
  if (eventTypes.isNotEmpty()) {
    scene.onEvent(node, sceneContext.registerHitCallback { event, hitInfo ->
      if (event.type() == UserEvent.EventType.CURSOR_MOVED && onCursorMoved != null) {
        onCursorMoved(event as UserEvent.CursorMoved, hitInfo)
      }
      else if (event.type() == UserEvent.EventType.MOUSE_INPUT && onMouseInput != null) {
        onMouseInput(event as UserEvent.MouseInput, hitInfo)
      }
      else if (event.type() == UserEvent.EventType.MOUSE_WHEEL && onMouseWheel != null) {
        onMouseWheel(event as UserEvent.MouseWheel, hitInfo)
      }
      else {
        Propagate.CONTINUE
      }
    }, eventTypes)
  }
  add(node)
}

fun hitBoxImpl(callbacks: HitCallbacks, view: View): View {
  return { cs ->
    val (size, layoutNode, renderer) = view(cs)
    Layout(size, layoutNode) { viewport ->
      stack {
        hitBox(Rect(Point.ZERO, size), callbacks)
        mount(Point.ZERO, renderer(viewport))
      }
    }
  }

}

inline fun Frame.hitBox(noinline onMouseWheel: TypedHitCallback<UserEvent.MouseWheel>? = null,
                        noinline onCursorMoved: TypedHitCallback<UserEvent.CursorMoved>? = null,
                        noinline onMouseInput: TypedHitCallback<UserEvent.MouseInput>? = null, crossinline c: Component): View {
  val view = c()
  return hitBoxImpl(HitCallbacks(onMouseWheel, onCursorMoved, onMouseInput), view)
}

package noria.ui

import noria.*
import noria.scene.PhotonApi
import noria.scene.UserEvent
import java.util.concurrent.ConcurrentHashMap

enum class Propagate { STOP, CONTINUE }
typealias HitCallback = (UserEvent, UserEvent.NodeHit) -> Propagate
typealias TypedHitCallback<T> = (T, UserEvent.NodeHit) -> Propagate

typealias GlobalHandler<T> = (T) -> Unit
typealias WindowId = Long

class GlobalHandlers(val windowId: WindowId,
                     val onKeyboardInput: GlobalHandler<UserEvent.KeyboardInput>? = null,
                     val onCharacterTyped: GlobalHandler<UserEvent.CharacterTyped>? = null,
                     val onNewFrame: GlobalHandler<UserEvent.NewFrame>? = null,
                     val onWindowResize: GlobalHandler<UserEvent.WindowResize>? = null,
                     val onCloseRequest: GlobalHandler<UserEvent.CloseRequest>? = null,
                     val onCursorMoved: GlobalHandler<UserEvent.CursorMoved>? = null,
                     val onMouseInput: GlobalHandler<UserEvent.MouseInput>? = null,
                     val onMouseWheel: GlobalHandler<UserEvent.MouseWheel>? = null,
                     val onMouseMotion: GlobalHandler<UserEvent.MouseMotion>? = null)

fun GlobalHandlers.handleEvent(event: UserEvent) {
  if (event is UserEvent.WindowEvent) {
    if (event.windowId == windowId) {
      when (event) {
        is UserEvent.CharacterTyped -> onCharacterTyped?.invoke(event)
        is UserEvent.CloseRequest -> onCloseRequest?.invoke(event)
        is UserEvent.KeyboardInput -> onKeyboardInput?.invoke(event)
        is UserEvent.CursorMoved -> onCursorMoved?.invoke(event)
        is UserEvent.MouseInput -> onMouseInput?.invoke(event)
        is UserEvent.MouseWheel -> onMouseWheel?.invoke(event)
        is UserEvent.NewFrame -> onNewFrame?.invoke(event)
        is UserEvent.WindowResize -> onWindowResize?.invoke(event)
      }
    }
  }
  else {
    when (event) {
      is UserEvent.MouseMotion -> onMouseMotion?.invoke(event)
    }
  }
}

typealias GlobalHandlersRegistry = ConcurrentHashMap<CallbackId, GlobalHandlers>

val GlobalHandlersRegistryKey: Attr<GlobalHandlersRegistry> = Attr("GlobalHandlers")

val Bindings.globalHandlers: GlobalHandlersRegistry get() = GlobalHandlersRegistryKey.get(this)

val TheRegistryEntryReconciler = RegistryReconciler<GlobalHandlers>()

@Suppress("NOTHING_TO_INLINE")
inline fun Frame.onGlobalEvents(handlers: GlobalHandlers) {
  child(generateKey(), TheRegistryEntryReconciler, bindings.globalHandlers to handlers)
}

private fun handleHits(event: UserEvent,
                       hits: List<UserEvent.NodeHit>,
                       hitHandlers: HitCallbacksRegistry) {
  for (hit in hits) { // closest to furthest by draw order
    val hitCallback = hitHandlers.get(hit.callbackId)
    if (hitCallback != null) {
      if (Propagate.STOP == hitCallback(event, hit)) {
        break
      }
    }
  }
}

fun handleUserEvent(event: UserEvent,
                    globalHandlers: GlobalHandlersRegistry,
                    hitHandlers: HitCallbacksRegistry) {
  if (event is UserEvent.HitEvent) {
    handleHits(event, event.hits, hitHandlers)
  }
  globalHandlers.values.forEach { handlers ->
    handlers.handleEvent(event)
  }

  if (event is UserEvent.NewFrame) {
    val animationRunning = globalHandlers.values.any { handlers ->
      handlers.windowId == event.windowId && handlers.onNewFrame != null
    }
    PhotonApi.setAnimationRunning(animationRunning)
  }
}
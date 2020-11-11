package noria.layout

import io.lacuna.bifurcan.IntMap
import noria.*
import noria.scene.UserEvent
import noria.scene.PhotonApi
import noria.scene.Scene
import java.util.Timer
import java.util.concurrent.atomic.AtomicReference

val timer = Timer(/* isDaemon = */ true)

typealias Ref<T> = AtomicReference<T>

typealias PersistentMap<K, V> = io.lacuna.bifurcan.Map<K, V>
typealias GlobalCallback = (UserEvent) -> Unit
typealias GlobalHandlers = PersistentMap<UserEvent.EventType, IntMap<GlobalCallback>>

val RespondersKey = Attr<Ref<RespondersTree>>("noria.responders")
val RespondersRootKey = Attr<Ref<ThunkId?>>("noria.keyboard.root")
val RespondersStateKey = Attr<Ref<RespondersState>>("noria.keyboard.state")
val GlobalHandlersKey = Attr<Ref<GlobalHandlers>>("noria.globalHandlers")
val ModifiersStateKey = Attr<Ref<UserEvent.ModifiersState>>("noria.modifiersState")

fun eventsBindings() = mapOf(
  RespondersKey to Ref(RespondersTree()),
  RespondersRootKey to Ref(null),
  RespondersStateKey to Ref(RespondersState(false, Chord(emptyList()))),
  GlobalHandlersKey to Ref(GlobalHandlers()),
  ModifiersStateKey to Ref(UserEvent.ModifiersState(false, false, false, false)))

data class GlobalHandler(val eventType: UserEvent.EventType,
                         val id: Long,
                         val callback: GlobalCallback)

object GlobalHandlerReconciler : Reconciler<GlobalHandler, Unit, GlobalHandler> {

  override fun needsReconcile(frame: SealedFrame<GlobalHandler, Unit, GlobalHandler>,
                              newArg: GlobalHandler): Boolean {
    return frame.state != newArg
  }

  override fun reconcile(frame: OpenFrame<GlobalHandler, Unit, GlobalHandler>,
                         newArg: GlobalHandler) {
    val state = frame.state
    frame.bindings.updateRef(GlobalHandlersKey) { hh: GlobalHandlers ->
      var handlers = hh
      if (state != null) {
        handlers = handlers.update(state.eventType) { callbacks: IntMap<GlobalCallback>? ->
          callbacks?.remove(state.id)
        }
      }
      handlers.update(newArg.eventType) { callbacks: IntMap<GlobalCallback>? ->
        (callbacks ?: IntMap()).put(newArg.id, newArg.callback)
      }
    }
    if (newArg.eventType == UserEvent.EventType.NEW_FRAME)
      PhotonApi.setAnimationRunning(true)
    frame.state = newArg
  }

  override fun destroy(frame: SealedFrame<GlobalHandler, Unit, GlobalHandler>) {
    val state = frame.state!!
    frame.bindings.updateRef(GlobalHandlersKey) { globalHandlers ->
      globalHandlers.update(state.eventType) { callbacks ->
        callbacks.remove(state.id)
      }
    }
  }
}

inline fun Frame.subscribeGlobal(eventType: UserEvent.EventType,
                                 key: Any? = null,
                                 noinline callback: GlobalCallback) {
  val scene = scene()
  val handler = expr(callback) {
    { userEvent: UserEvent ->
      when (userEvent) {
        is UserEvent.MouseMotion -> { callback(userEvent) }

        is UserEvent.MouseWheel -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.MouseInput -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.CursorMoved -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.NewFrame -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.CharacterTyped -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.KeyboardInput -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.WindowResize -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
        is UserEvent.CloseRequest -> if (userEvent.windowId == scene.windowId()) { callback(userEvent) }
      }
    }
  }
  this.child(key ?: handler.thunkId, GlobalHandlerReconciler, GlobalHandler(eventType, handler.thunkId, read(handler)))
}

fun runHitCallback(scene: Scene,
                   userEvent: UserEvent,
                   hit: UserEvent.NodeHit,
                   onEvent: HitCallback): Propagate {
  when (userEvent) {
    is UserEvent.MouseWheel -> if (userEvent.windowId == scene.windowId()) {
      return onEvent(userEvent, hit)
    }
    is UserEvent.MouseInput -> if (userEvent.windowId == scene.windowId()) {
      return onEvent(userEvent, hit)
    }
    is UserEvent.CursorMoved -> if (userEvent.windowId == scene.windowId()) {
      return onEvent(userEvent, hit)
    }
  }
  return Propagate.CONTINUE
}


inline fun Frame.subscribeHit(scene: Scene,
                              rectId: NodeId,
                              mask: EventMask,
                              noinline onEvent: HitCallback): ThunkId {
  val thunkId = expr(rectId, mask, onEvent) {
    scene.onEvent(rectId, this.id, mask);
    { userEvent: UserEvent, hit: UserEvent.NodeHit ->
      runHitCallback(scene, userEvent, hit, onEvent)
    }
  }.thunkId

  resource(
    key = thunkId,
    initializer = { thunkId },
    destroyer = { scene.onEvent(rectId, 0, listOf()) }
  )

  return thunkId
}

typealias EventMask = Iterable<UserEvent.EventType>

enum class Propagate { STOP, CONTINUE }
typealias HitCallback = (UserEvent, UserEvent.NodeHit) -> Propagate
typealias TypedHitCallback<T> = (T, UserEvent.NodeHit) -> Propagate

data class HitHandler(val mask: EventMask,
                      val tCallback: Computation<HitCallback>)

typealias NextHandler = () -> Boolean
typealias ChordHandler = (NextHandler) -> Unit
typealias TextInputHandler = (String, NextHandler) -> Unit

data class Keystroke(val keycode: UserEvent.VirtualKeyCode,
                     val modifiers: UserEvent.ModifiersState)

data class Chord(val keystrokes: List<Keystroke>)

sealed class Responder {
  data class Delegate(val delegate: ThunkId) : Responder()
  data class Keymap(val keymap: Map<Chord, ChordHandler>) : Responder()
  data class TextInput(val f: TextInputHandler) : Responder()
}

typealias RespondersTree = PersistentMap<ThunkId, RespondersChain>

data class RespondersState(val skipNextCharacterTyped: Boolean,
                           val currentChord: Chord)

enum class HandleResult {
  NOT_FOUND,
  NON_TERMINAL,
  FINISHED
}

enum class MatchResult { NO, EXACT, PREFIX }

fun isPrefix(prefix: Chord,
             chord: Chord): MatchResult {
  if (prefix.keystrokes.size <= chord.keystrokes.size) {
    prefix.keystrokes.forEachIndexed { index, keystroke ->
      if (keystroke != chord.keystrokes.get(index)) {
        return MatchResult.NO
      }
    }
    return if (prefix.keystrokes.size == chord.keystrokes.size) {
      MatchResult.EXACT
    }
    else {
      MatchResult.PREFIX
    }
  }
  else {
    return MatchResult.NO
  }
}

fun handleKeystroke(currentChord: Chord,
                    rs: Iterator<Map.Entry<Chord, ChordHandler>>): HandleResult {
  while (rs.hasNext()) {
    val (chord, handler) = rs.next()
    when (isPrefix(currentChord, chord)) {
      MatchResult.EXACT -> {
        handler({ handleKeystroke(currentChord, rs) != HandleResult.NOT_FOUND })
        return HandleResult.FINISHED
      }
      MatchResult.PREFIX -> {
        return HandleResult.NON_TERMINAL
      }
    }
  }
  return HandleResult.NOT_FOUND
}


fun dfs(tree: RespondersTree, root: ThunkId): Sequence<Responder> {
  return sequence {
    val children = tree.get(root, null)?.chain ?: emptyList()
    for (child in children) {
      when (child) {
        is Responder.Delegate -> yieldAll(dfs(tree, child.delegate))
        is Responder.Keymap -> yield(child)
        is Responder.TextInput -> yield(child)
      }
    }
  }
}

fun handleCharacterTyped(chars: String, i: Iterator<Responder.TextInput>): Boolean {
  if (i.hasNext()) {
    val input = i.next()
    input.f(chars, { handleCharacterTyped(chars, i) })
    return true
  }
  return false
}

fun isModifier(keyCode: UserEvent.VirtualKeyCode): Boolean {
  return keyCode in listOf(
    UserEvent.VirtualKeyCode.LControl,
    UserEvent.VirtualKeyCode.RControl,
    UserEvent.VirtualKeyCode.LAlt,
    UserEvent.VirtualKeyCode.RAlt,
    UserEvent.VirtualKeyCode.LShift,
    UserEvent.VirtualKeyCode.RShift,
    UserEvent.VirtualKeyCode.LWin,
    UserEvent.VirtualKeyCode.RWin)
}

fun handleKeyboard(state: RespondersState,
                   respondersRoot: ThunkId,
                   tree: RespondersTree,
                   event: UserEvent): RespondersState =
  when (event) {
    is UserEvent.CharacterTyped ->
      if (state.skipNextCharacterTyped) {
        state.copy(skipNextCharacterTyped = false)
      }
      else {
        val tis = dfs(tree, respondersRoot)
          .filterIsInstance(Responder.TextInput::class.java)
          .iterator()
        handleCharacterTyped(event.chars, tis)
        state
      }
    is UserEvent.KeyboardInput -> {
      if (event.state == UserEvent.ButtonState.PRESSED && !isModifier(event.keyCode)) {
        val k = Keystroke(event.keyCode, event.modifiers)
        val rs = dfs(tree, respondersRoot)
          .flatMap {
            when (it) {
              is Responder.Keymap -> it.keymap.entries.asSequence()
              else -> emptySequence()
            }
          }
        val nextChord = Chord(state.currentChord.keystrokes + k)
        val r = handleKeystroke(nextChord, rs.iterator())
        when (r) {
          HandleResult.NOT_FOUND -> {
            RespondersState(
              currentChord = Chord(emptyList()),
              skipNextCharacterTyped = !state.currentChord.keystrokes.isEmpty())
          }
          HandleResult.NON_TERMINAL -> {
            RespondersState(
              currentChord = nextChord,
              skipNextCharacterTyped = true)
          }
          HandleResult.FINISHED -> {
            RespondersState(
              currentChord = Chord(emptyList()),
              skipNextCharacterTyped = true)
          }
        }
      }
      else {
        state
      }
    }
    else -> state
  }


fun keyboardRoot(frame: Frame, thunk: Computation<*>) {
  frame.bindings.updateRef(RespondersRootKey) {
    thunk.thunkId
  }
}

fun keyboard(frame: Frame, thunk: Computation<*>, chain: List<Responder>): Thunk<*> =
  frame.child(thunk, RespondersChainReconciler, RespondersChain(thunk, chain, frame.generateId()))

data class RespondersChain(val thunk: Computation<*>,
                           val chain: List<Responder>,
                           val id: Any)

object RespondersChainReconciler : Reconciler<RespondersChain, Unit, RespondersChain> {
  override fun needsReconcile(frame: SealedFrame<RespondersChain, Unit, RespondersChain>,
                              newArg: RespondersChain): Boolean =
    frame.state != newArg

  override fun reconcile(frame: OpenFrame<RespondersChain, Unit, RespondersChain>,
                         newArg: RespondersChain) {
    frame.bindings.updateRef(RespondersKey) { r: RespondersTree ->
      r.put(newArg.thunk.thunkId, newArg)
    }
    frame.state = newArg
  }

  override fun destroy(frame: SealedFrame<RespondersChain, Unit, RespondersChain>) {
    val state = frame.state!!
    frame.bindings.updateRef(RespondersKey) { r: RespondersTree ->
      val chain = r.get(state.thunk.thunkId, null)
      if (chain?.id == state.id) {
        r.remove(state.thunk.thunkId)
      }
      else {
        r
      }
    }
  }
}

fun handleHits(noria: Noria<*, *>, event: UserEvent, hits: List<UserEvent.NodeHit>) {
  for (hit in hits) { // closest to furthest by draw order
    val hitCallback = noria.read(hit.callbackId) as HitCallback?
    if (hitCallback != null)
      if (Propagate.STOP == hitCallback(event, hit))
        break
  }
}

fun handleUserEvent(noria: Noria<*, *>, event: UserEvent) {
  when (event) {
    is UserEvent.KeyboardInput -> {
      noria.rootBindings.updateRef(ModifiersStateKey) { event.modifiers }
    }

    is UserEvent.CharacterTyped -> {
      val ch: Int = event.chars.codePointAt(0)
      val type = Character.getType(ch).toByte()
      val modifiers = ModifiersStateKey.get(noria.rootBindings).get()
      if (modifiers.cmd || modifiers.ctrl || Character.isISOControl(ch) || type == Character.PRIVATE_USE)
        return
    }

    is UserEvent.CursorMoved -> handleHits(noria, event, event.hits)
    is UserEvent.MouseInput -> handleHits(noria, event, event.hits)
    is UserEvent.MouseWheel -> handleHits(noria, event, event.hits)
  }

  val globalHandlers = GlobalHandlersKey[noria.rootBindings].get().get(event.type())
  if (globalHandlers.isPresent)
    for (entry in globalHandlers.get())
      entry.value()(event)

  val respondersRoot = RespondersRootKey[noria.rootBindings].get()
  if (respondersRoot != null) {
    val responders = RespondersKey[noria.rootBindings].get()
    noria.rootBindings.updateRef(RespondersStateKey) { respondersState ->
      handleKeyboard(
        respondersState,
        respondersRoot,
        responders,
        event)
    }
  }

  if (event is UserEvent.NewFrame) {
    val newFrameListeners = GlobalHandlersKey[noria.rootBindings].get()[UserEvent.EventType.NEW_FRAME]
    PhotonApi.setAnimationRunning(newFrameListeners.isPresent() && newFrameListeners.get().size() > 0)
  }
}

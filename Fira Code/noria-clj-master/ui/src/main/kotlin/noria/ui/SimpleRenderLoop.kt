package noria.ui

import noria.*
import noria.layout.LOG_LEVEL
import noria.scene.PhotonApi
import noria.scene.Scene
import noria.scene.UserEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

data class Reval(val events: List<UserEvent> = emptyList(),
                 val dirtySet: Map<Long, StateUpdate?>?)

typealias EventsQueue = LinkedBlockingDeque<Reval>

fun runSimpleOneWindowRenderLoop(eventsQueue: EventsQueue, app: Frame.() -> Scene) {
  val statisticBufferSize = 60
  val frames = DoubleArray(statisticBufferSize)
  var frameIdx = 0L

  var noriaRef: Noria<Scene, *>? = null

  val updater = StateUpdater { nodeId, transform ->
    eventsQueue.put(Reval(dirtySet = mapOf(nodeId to transform)))
    // trigger cascade updates and redraws starting from dirtySet
  }

  val framesScheduleTime = ConcurrentHashMap<Long, Long>()

  Thread {
    val hitCallbacksRegistry = HitCallbacksRegistry()
    val globalHandlersRegistry = GlobalHandlersRegistry()
    noriaRef = NoriaImpl.noria(mapOf<Any, Any>(UPDATER_KEY to updater,
                                               AllScenesAttr to HashSet<Scene>(),
                                               HitCallbacksRegistryKey to hitCallbacksRegistry,
                                               GlobalHandlersRegistryKey to globalHandlersRegistry)) {
      app()
    }
    noriaRef!!.result.commit(frameIdx)
    val dirtySet = HashMap<Long, StateUpdate?>()
    var requestNewFrame = true
    while (true) {
      var reval: Reval? = eventsQueue.take()
      val events = ArrayList<String>()
      while (reval != null) {
        reval.events.forEach { e ->
          events.add(e.type().name)
          handleUserEvent(e, globalHandlersRegistry, hitCallbacksRegistry)
          if (e is UserEvent.NewFrame) {
            requestNewFrame = true
          }
        }
        reval.dirtySet?.forEach { (k, v) -> if (v == null) dirtySet[k] = v else dirtySet.merge (k, v) { v1, v2 -> v2.compose(v1) } }
        reval = eventsQueue.poll()
      }
      if (dirtySet.isNotEmpty() && requestNewFrame) {
        val t0 = System.nanoTime()
        noriaRef = noriaRef!!.revaluate(dirtySet)
        val dt = (System.nanoTime() - t0) / 1_000_000.0
        frames[frameIdx.rem(statisticBufferSize).toInt()] = dt
        val avg = Arrays.stream(frames).average().asDouble
        val m = Arrays.stream(frames).max().asDouble
        if ("DEBUG" == LOG_LEVEL) {
          println("[ perf ] frame %.3f ms".format(dt))
          if (frameIdx.rem(statisticBufferSize) == 0L) {
            println("[ perf ] Dropped frames: $framesScheduleTime")
          }
        }
        if ("INFO" == LOG_LEVEL && frameIdx.rem(statisticBufferSize) == 0L) {
          println("[ perf ] rolling avg %.3f ms max %.3f".format(avg, m))
        }
        frameIdx += 1
        // tell Scene we are finished and it could send frame for rendering
        val scene = noriaRef!!.result
        val commitTime = System.currentTimeMillis()
        if (scene.commit(frameIdx)) {
          requestNewFrame = false
          dirtySet.clear()
          if (LOG_LEVEL == "DEBUG") {
            println("[ perf ] Commit $frameIdx caused by $events")
            framesScheduleTime[frameIdx] = commitTime
          }
        }
      }
    }
  }.start()

  var isProfilerShowing = false
  PhotonApi.runEventLoop { events ->
    events.forEach { e ->
      if (LOG_LEVEL == "DEBUG") {
        when (e) {
          is UserEvent.NewFrame -> {
            val frameId = e.frameId
            if (frameId != null) {
              val scheduleTime = framesScheduleTime.remove(e.frameId)
              if (scheduleTime == null) {
                println("[ perf ] Phantom frame")
              }
              else {
                val frameTime = System.currentTimeMillis() - scheduleTime
                if (frameTime > 16) {
                  println("[ perf ] Frame $frameId took ${frameTime}ms")
                }
              }
            }
          }
        }
      }

      if (e is UserEvent.KeyboardInput) {
        if (e.state == UserEvent.ButtonState.PRESSED && e.modifiers.alt && e.modifiers.cmd) {
          when (e.keyCode) {
            UserEvent.VirtualKeyCode.P -> {
              isProfilerShowing = !isProfilerShowing
              PhotonApi.setDebugProfilerShowing(e.windowId, isProfilerShowing)
            }
            UserEvent.VirtualKeyCode.W -> {
              val cpuProfile = createTempFile().absolutePath
              // TODO deleteOnExit?
              PhotonApi.dropCpuProfile(cpuProfile)
              println("[ debug ] drop cpu profile to $cpuProfile")
            }
          }
        }
      }
    }
    eventsQueue.put(Reval(events = events, dirtySet = null))
  }
}
package noria.layout

import noria.*
import noria.scene.*

data class DraggableState(val windowOffset: Point?,
                          val holdOffset: Point)

data class Draggable(val tChild: TRenderObject) : RenderObject {
  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    with(frame) {
      return nesting(frame) {
        read(tChild).measure(frame, cs)
      }
    }
  }

  override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? {
    with(frame) {
      return nesting(frame) {
        read(tChild).makeVisible(frame, measure)
      }
    }
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val stackId = generateId()
      val hitboxId = generateId()
      val tState = state<DraggableState?> { null }
      val state = read(tState)
      val scene = scene()

      return if (state == null) {
        nesting(frame) {
          scene.rect(hitboxId, Point(0f, 0f), measure.asSize(), Color.transparent)

          subscribeHit(scene, hitboxId, listOf(UserEvent.EventType.MOUSE_INPUT)) { event, hit ->
            event as UserEvent.MouseInput
            if (event.button == UserEvent.MouseButton.LEFT && event.state == UserEvent.ButtonState.PRESSED)
              tState.update {
                DraggableState(null,
                               Point(-hit.relativeCursorPosition.x, -hit.relativeCursorPosition.y))
              }
            Propagate.CONTINUE
          }

          val renderResult = memo(tChild, measure, tViewport, tWindowOffset) {
            read(tChild).render(currentFrame, measure, tViewport, tWindowOffset)
          }

          if (renderResult.node != null)
            scene.stack(stackId, longArrayOf(renderResult.node, hitboxId))
          else
            scene.stack(stackId, longArrayOf(hitboxId))

          RenderResult(stackId, renderResult.overlays)
        }
      }
      else {
        nesting(frame) {
          subscribeGlobal(UserEvent.EventType.CURSOR_MOVED) { event ->
            event as UserEvent.CursorMoved
            tState.update { it?.copy(windowOffset = event.cursorPosition.offset(it.holdOffset)) }
          }

          subscribeGlobal(UserEvent.EventType.MOUSE_INPUT) { event ->
            event as UserEvent.MouseInput
            if (event.button == UserEvent.MouseButton.LEFT && event.state == UserEvent.ButtonState.RELEASED)
              tState.update { null }
          }

          scene.rect(hitboxId, Point(0f, 0f), measure.asSize(), Color.transparent)
          scene.stack(stackId, longArrayOf(hitboxId))

          val windowOffset = state.windowOffset ?: read(tWindowOffset)

          val tChildViewport = expr(windowOffset, measure) {
            val windowSize = read(WindowSizeKey[this.bindings])
            Rect(0f, 0f, windowSize.width, windowSize.height)
              .offset(-windowOffset.x, -windowOffset.y)
              .intersect(Rect(0f, 0f, measure.width, measure.height))
          }

          val renderResult = memo(tChild, measure, tChildViewport, windowOffset) {
            read(tChild).render(currentFrame, measure, tChildViewport, expr(windowOffset) { windowOffset })
          }
          if (renderResult.node != null)
            scene.setPosition(renderResult.node, windowOffset)
          RenderResult(stackId, (if (renderResult.node != null) listOf(renderResult.node) else listOf()) + renderResult.overlays)
        }
      }
    }
  }
}
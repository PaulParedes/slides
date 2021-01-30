package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.floor

enum class SplitDirection {
  HORIZONTAL, VERTICAL
}

data class SplitOptions(val direction: SplitDirection? = null,
                        val delimeterWidth: Float? = null,
                        val delimeterColor: Color? = null) {
  companion object {
    val constructorParameterNames = listOf("direction", "delimeterWidth", "delimeterColor")
  }

  fun merge(opts: SplitOptions?) =
    if (opts == null)
      this
    else
      SplitOptions(direction = opts.direction ?: direction,
                   delimeterWidth = opts.delimeterWidth ?: delimeterWidth,
                   delimeterColor = opts.delimeterColor ?: delimeterColor)
}

private val defaultOptions = SplitOptions(direction = SplitDirection.HORIZONTAL,
                                          delimeterWidth = 5f,
                                          delimeterColor = Color.css("CCC7C7"))

fun Frame.split(tFirst: TRenderObject,
                tSecond: TRenderObject,
                initialRatio: Float = 0.5f,
                opts: SplitOptions? = null): Split {
  val tState = state { SplitState(initialRatio) }
  return Split(tFirst, tSecond, tState, expr { tState::update }, defaultOptions.merge(opts))
}

data class SplitState(val ratio: Float = 0.5f,
                      val drag: Boolean = false)

data class Split(val tFirst: TRenderObject,
                 val tSecond: TRenderObject,
                 val tState: Thunk<SplitState>,
                 val tUpdateState: Thunk<(Transformation<SplitState>) -> Unit>,
                 val opts: SplitOptions = defaultOptions) : RenderObject {

  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    with(frame) {
      return nesting(frame) {
        val direction = opts.direction!!
        val delimiterWidth = opts.delimeterWidth!!
        val state = read(tState)
        val fullWidth = cs.maxWidth
        val fullHeight = cs.maxHeight

        when (direction) {
          SplitDirection.HORIZONTAL -> {
            val width1 = floor((fullWidth - delimiterWidth) * state.ratio)
            val measure1 = memo(tFirst, width1, fullHeight) {
              read(tFirst).measure(currentFrame, Constraints.tight(Size(width1, fullHeight)))
            }

            val width2 = fullWidth - width1 - delimiterWidth
            val measure2 = memo(tSecond, fullHeight, width2) {
              read(tSecond).measure(currentFrame, Constraints.tight(Size(width2, fullHeight)))
            }

            Measure(fullWidth, fullHeight, Pair(measure1, measure2))
          }

          SplitDirection.VERTICAL -> {
            val height1 = floor((fullHeight - delimiterWidth) * state.ratio)
            val measure1 = memo(tFirst, fullWidth, height1) {
              read(tFirst).measure(currentFrame, Constraints.tight(Size(fullWidth, height1)))
            }

            val height2 = fullHeight - height1 - delimiterWidth
            val measure2 = memo(tSecond, fullWidth, height2) {
              read(tSecond).measure(currentFrame, Constraints.tight(Size(fullWidth, height2)))
            }

            Measure(fullWidth, fullHeight, Pair(measure1, measure2))
          }
        }
      }
    }
  }

  override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? {
    with(frame) {
      return nesting(frame) {
        val (measure1, measure2) = measure.extra as Pair<Measure, Measure>
        val scrollTo1 = memo(tFirst, measure1) {
          read(tFirst).makeVisible(currentFrame, measure1)
        }
        if (scrollTo1 != null)
          scrollTo1
        else {

          val direction = opts.direction!!
          val delimiterWidth = opts.delimeterWidth!!
          val state = read(tState)

          val scrollTo2 = memo(tSecond, measure2) {
            read(tSecond).makeVisible(currentFrame, measure2)
          }
          if (scrollTo2 != null) {
            when (direction) {
              SplitDirection.HORIZONTAL -> {
                val width1 = floor((measure.width - delimiterWidth) * state.ratio)
                scrollTo2.copy(pos = scrollTo2.pos.offset(width1 + delimiterWidth, 0f))
              }
              SplitDirection.VERTICAL -> {
                val height1 = floor((measure.height - delimiterWidth) * state.ratio)
                scrollTo2.copy(pos = scrollTo2.pos.offset(0f, height1 + delimiterWidth))
              }
            }
          }
          else {
            null
          }
        }
      }
    }
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      return nesting(frame) {
        val scene = scene()
        val stackId = generateId()
        val bgId = generateId()
        val delimiterId = generateId()
        var first: RenderResult? = null
        var second: RenderResult? = null

        val direction = opts.direction!!
        val delimiterWidth = opts.delimeterWidth!!
        val delimiterColor = opts.delimeterColor!!

        val state = read(tState)
        val updateState = read(tUpdateState)
        val (measure1, measure2) = measure.extra as Pair<Measure, Measure>

        scene.rect(bgId, Point(0f, 0f), measure.asSize(), Color.transparent)

        when (direction) {
          SplitDirection.HORIZONTAL -> {
            val width1 = floor((measure.width - delimiterWidth) * state.ratio)
            val tViewport1 = expr(tViewport, measure.height, width1) {
              read(tViewport).intersect(Rect(0f, 0f, width1, measure.height))
            }
            first = memo(tViewport1, measure1, tWindowOffset) {
              if (read(tViewport1) != null)
                read(tFirst).render(currentFrame, measure1, tViewport1, tWindowOffset)
              else
                null
            }

            val tViewport2 = expr(tViewport, delimiterWidth, measure, width1) {
              read(tViewport)
                .intersect(Rect(width1 + delimiterWidth, 0f, measure.width, measure.height))
                ?.offset(-width1 - delimiterWidth, 0f)
            }
            val offset = Point(width1 + delimiterWidth, 0f)
            val tOffset2 = expr(tWindowOffset, offset) { read(tWindowOffset).offset(offset) }
            second = memo(tViewport2, measure2, tViewport2, tOffset2) {
              if (read(tViewport2) != null)
                read(tSecond).render(currentFrame, measure2, tViewport2 as Thunk<Rect>, tOffset2)
              else
                null
            }
            if (second != null && second.node != null)
              scene.setPosition(second.node!!, offset)

            scene.rect(delimiterId, Point(width1, 0f),
                       Size(delimiterWidth, measure.height), delimiterColor)
          }
          SplitDirection.VERTICAL -> {
            val height1 = floor((measure.height - delimiterWidth) * state.ratio)
            val tViewport1 = expr(tViewport, measure, height1) { read(tViewport)?.intersect(Rect(0f, 0f, measure.width, height1)) }
            first = memo(tFirst, measure1, tViewport1, tWindowOffset) {
              read(tFirst).render(currentFrame, measure1, tViewport1, tWindowOffset)
            }

            val tViewport2 = expr(tViewport, height1, delimiterWidth, measure) {
              read(tViewport)
                .intersect(Rect(0f, height1 + delimiterWidth, measure.width, measure.height))
                ?.offset(0f, -height1 - delimiterWidth)
            }
            val offset = Point(0f, height1 + delimiterWidth)
            val tOffset2 = expr(tWindowOffset, offset) { read(tWindowOffset).offset(offset) }
            second = memo(tViewport2, measure2, tOffset2) {
              if (read(tViewport2) != null) {
                read(tSecond).render(currentFrame, measure2, tViewport2 as Thunk<Rect>, tOffset2)
              }
              else null
            }
            if (second != null && second.node != null)
              scene.setPosition(second.node!!, offset)

            scene.rect(delimiterId, Point(0f, height1),
                       Size(measure.width, delimiterWidth), delimiterColor)
          }
        }

        subscribeHit(scene, delimiterId, listOf(UserEvent.EventType.MOUSE_INPUT)) { e, _ ->
          e as UserEvent.MouseInput
          if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED) {
            updateState { splitState ->
              splitState.copy(drag = true)
            }
            Propagate.STOP
          }
          else
            Propagate.CONTINUE
        }

        if (state.drag) {
          subscribeHit(scene, bgId, listOf(UserEvent.EventType.CURSOR_MOVED)) { e, hit ->
            e as UserEvent.CursorMoved
            when (direction) {
              SplitDirection.HORIZONTAL -> updateState { it.copy(ratio = hit.relativeCursorPosition.x / measure.width) }
              SplitDirection.VERTICAL -> updateState { it.copy(ratio = hit.relativeCursorPosition.y / measure.height) }
            }
            Propagate.STOP
          }
          subscribeGlobal(UserEvent.EventType.MOUSE_INPUT) { e: UserEvent ->
            e as UserEvent.MouseInput
            if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
              updateState { it.copy(drag = false) }
            }
          }
        }

        val children = if (state.drag) {
          listOfNotNull(first?.node, second?.node, delimiterId, bgId).toLongArray()
        }
        else {
          listOfNotNull(first?.node, second?.node, delimiterId).toLongArray()
        }
        scene.stack(stackId, children)
        RenderResult(stackId, (first?.overlays ?: listOf()) + (second?.overlays ?: listOf()))
      }
    }
  }
}

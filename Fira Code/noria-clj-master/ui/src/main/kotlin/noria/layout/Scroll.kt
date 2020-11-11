package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

enum class ScrollDirection {
  HORIZONTAL, VERTICAL, BOTH
}

data class ScrollOptions(val scrollDirection: ScrollDirection? = null,
                         val propagate: Propagate? = null,
                         val scrollbarColor: Color? = null) {
  companion object {
    val constructorParameterNames = listOf("scrollDirection", "propagate", "scrollbarColor")
  }

  fun merge(opts: ScrollOptions?) =
    if (opts == null)
      this
    else
      ScrollOptions(scrollDirection = opts.scrollDirection ?: scrollDirection,
                    propagate = opts.propagate ?: propagate,
                    scrollbarColor = opts.scrollbarColor ?: scrollbarColor)
}

private val defaultOptions = ScrollOptions(scrollDirection = ScrollDirection.VERTICAL,
                                           propagate = Propagate.CONTINUE,
                                           scrollbarColor = Color(0f, 0f, 0f, 0.5f))

typealias ScrollState = Point

fun Frame.scroll(tChild: TRenderObject, opts: ScrollOptions? = null, initialScrollPosition: Point = Point(
  0f, 0f)): Scroll {
  val tState = state { initialScrollPosition }
  return Scroll(tChild, tState, expr { tState::update }, defaultOptions.merge(opts))
}

private fun newScrollPos(old: Point, scrollDirection: ScrollDirection, offset: Point, maxScroll: Point): Point {
  val new = when (scrollDirection) {
    ScrollDirection.VERTICAL -> Point(old.x, old.y - offset.y)
    ScrollDirection.HORIZONTAL -> Point(
      old.x - (if (abs(offset.x) > abs(offset.y)) offset.x else offset.y), old.y)
    ScrollDirection.BOTH -> Point(old.x - offset.x, old.y - offset.y)
  }
  return Point(clamp(new.x, 0f, maxScroll.x), clamp(new.y, 0f, maxScroll.y))
}

data class Scroll(val tChild: TRenderObject,
                  val tState: Thunk<ScrollState>,
                  val tUpdateState: Thunk<(Transformation<ScrollState>) -> Unit>,
                  val opts: ScrollOptions = defaultOptions) : RenderObject {
  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    with(frame) {
      return nesting(frame) {
        val childConstraints =
          when (opts.scrollDirection!!) {
            ScrollDirection.HORIZONTAL -> Constraints(cs.minWidth, Float.POSITIVE_INFINITY, cs.minHeight,
                                                      cs.maxHeight)
            ScrollDirection.VERTICAL -> Constraints(cs.minWidth, cs.maxWidth, cs.minHeight,
                                                    Float.POSITIVE_INFINITY)
            ScrollDirection.BOTH -> Constraints(cs.minWidth, Float.POSITIVE_INFINITY, cs.minHeight,
                                                Float.POSITIVE_INFINITY)
          }
        val childMeasure = memo(tChild, childConstraints) {
          read(tChild).measure(currentFrame, childConstraints)
        }

        Measure(clamp(childMeasure.width, cs.minWidth, cs.maxWidth), clamp(childMeasure.height, cs.minHeight, cs.maxHeight), childMeasure)
      }
    }
  }

  override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? {
    with(frame) {
      return nesting(frame) {
        val childMeasure = measure.extra as Measure
        val scrollCommand = read(tChild).makeVisible(currentFrame, childMeasure)

        if (scrollCommand != null) {
          scrollCommand.onFinished()

          val scrollPos = read(tState)
          val updateState = read(tUpdateState)

          val scrollView = Rect(scrollPos.x, scrollPos.y, scrollPos.x + measure.width,
                                scrollPos.y + measure.height)
          if (!scrollView.contains(scrollCommand.pos)) {
            val nextScrollPos = scrollCommand.pos.offset(-measure.width / 2f, -measure.height / 2f)
            val maxScroll = Point(max(0f, childMeasure.width - measure.width),
                                  max(0f, childMeasure.height - measure.height))
            val nextScrollPosClamped = Point(clamp(nextScrollPos.x, 0f, maxScroll.x),
                                             clamp(nextScrollPos.y, 0f, maxScroll.y))
            updateState {
              nextScrollPosClamped
            }
            ScrollCommand(scrollCommand.pos.offset(-nextScrollPosClamped.x, -nextScrollPosClamped.y))
          }
          else {
            ScrollCommand(scrollCommand.pos.offset(-scrollPos.x, -scrollPos.y))
          }
        }
        else
          null
      }
    }
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      return nesting(frame) {
        val id = generateId()
        val debugTextBgId = generateId()
        val debugTextId = generateId()
        val hitboxId = generateId()
        val scrollId = generateId()
        val scene = scene()
        val scrollPos = read(tState)
        var nextScrollPos = scrollPos
        val updateState = read(tUpdateState)
        val scrollDirection = opts.scrollDirection!!
        val children = mutableListOf(hitboxId)
        val size = measure.asSize()

        val childMeasure = measure.extra as Measure
        val childSize = childMeasure.asSize()

        val maxScroll = Point(max(0f, childMeasure.width - measure.width),
                              max(0f, childMeasure.height - measure.height))

        // hitbox
        scene.rect(hitboxId, Point(0f, 0f), size, Color(0f, 0f, 0f, 0f))

        // render child
        val tChildViewport = expr(tViewport, size, childSize, scrollPos) {
          read(tViewport)
            .intersect(Rect(0f, 0f, size.width, size.height))
            ?.offset(scrollPos)
            ?.intersect(Rect(0f, 0f, childSize.width, childSize.height)) ?: Rect(0f, 0f, 0f, 0f)
        }
        val tChildOffset = expr(tWindowOffset, scrollPos) { read(tWindowOffset).offset(-scrollPos.x, -scrollPos.y) }
        val childResult = memo(tChild, childMeasure, tChildViewport, tChildOffset) {
          read(tChild).render(currentFrame, childMeasure, tChildViewport, tChildOffset)
        }


        subscribeHit(scene, hitboxId, listOf(UserEvent.EventType.MOUSE_WHEEL)) { e, _ ->
          e as UserEvent.MouseWheel
          val offset = Point(e.delta.dx, e.delta.dy)
          val projected = newScrollPos(scrollPos, scrollDirection, offset, maxScroll)
          if (projected != scrollPos) {
            updateState { newScrollPos(it, scrollDirection, offset, maxScroll) }
            Propagate.STOP
          }
          else
            opts.propagate!!
        }

        // clamp scroll pos
        if (nextScrollPos.x < 0f || nextScrollPos.x > maxScroll.x || nextScrollPos.y < 0f || nextScrollPos.y > maxScroll.y) {
          nextScrollPos = Point(clamp(nextScrollPos.x, 0f, maxScroll.x),
                                clamp(nextScrollPos.y, 0f, maxScroll.y))
        }

        if (nextScrollPos != scrollPos) {
          updateState { nextScrollPos }
        }

        // child

        if (childResult.node != null) {
          scene.scroll(scrollId, Point(0f, 0f), size, childResult?.node, childSize)
          scene.scrollPosition(scrollId, Point(scrollPos.x, scrollPos.y))
          children.add(scrollId)
        }

        // render scrollbars
        val scrollbarWidth = 4f
        val minScrollbarLenght = 10f
        val scrollbarPadding = 2f
        val vScrollbarVisible = scrollDirection != ScrollDirection.HORIZONTAL && childSize.height > size.height
        val hScrollbarVisible = scrollDirection != ScrollDirection.VERTICAL && childSize.width > size.width

        if (vScrollbarVisible) {
          val scrollbarId = generateId()
          val maxh = if (hScrollbarVisible)
            size.height - 3 * scrollbarPadding - scrollbarWidth
          else
            size.height - 2 * scrollbarPadding

          val from = min(maxh - minScrollbarLenght, max(0f, scrollPos.y / childSize.height * maxh))
          val to = max(from + minScrollbarLenght, min(maxh, (scrollPos.y + size.height) / childSize.height * maxh))
          scene.rect(scrollbarId,
                     Point(size.width - scrollbarWidth - scrollbarPadding, scrollbarPadding + from),
                     Size(scrollbarWidth, to - from),
                     opts.scrollbarColor)
          children.add(scrollbarId)
        }

        if (hScrollbarVisible) {
          val scrollbarId = generateId()
          val maxw = if (vScrollbarVisible)
            size.width - 3 * scrollbarPadding - scrollbarWidth
          else
            size.width - 2 * scrollbarPadding

          val from = min(maxw - minScrollbarLenght, max(0f, scrollPos.x / childSize.width * maxw))
          val to = max(from + minScrollbarLenght, min(maxw, (scrollPos.x + size.width) / childSize.width * maxw))
          scene.rect(scrollbarId,
                     Point(scrollbarPadding + from, size.height - scrollbarWidth - scrollbarPadding),
                     Size(to - from, scrollbarWidth),
                     opts.scrollbarColor)
          children.add(scrollbarId)
        }



        scene.stack(id, children.toLongArray())
        RenderResult(id, childResult?.overlays ?: listOf())
      }
    }
  }
}

fun clamp(x: Float, low: Float, high: Float): Float = min(max(x, low), high)

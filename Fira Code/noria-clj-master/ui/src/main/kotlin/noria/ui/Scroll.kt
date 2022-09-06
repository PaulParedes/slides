package noria.ui

import noria.*
import noria.scene.*
import java.lang.Math.abs
import kotlin.math.max
import kotlin.math.min


enum class ScrollDirection {
  HORIZONTAL, VERTICAL, BOTH
}
typealias ScrollCommand = Point
typealias ScrollState = Point

enum class DragDirection { HORIZONTAL, VERTICAL }
data class DragState(val active: Boolean, val direction: DragDirection, val position: Float, val ratio: Float) {
  companion object {
    val empty = DragState(false, DragDirection.HORIZONTAL, 0f, 0f)
  }
}

private fun nextScrollPos(old: Point, scrollDirection: ScrollDirection, offset: Point, maxScroll: Point): Point {
  val new = when (scrollDirection) {
    ScrollDirection.VERTICAL -> Point(old.x, old.y - offset.y)
    ScrollDirection.HORIZONTAL -> Point(
      old.x - (if (abs(offset.x) > abs(offset.y)) offset.x else offset.y), old.y)
    ScrollDirection.BOTH -> Point(old.x - offset.x, old.y - offset.y)
  }
  return Point(new.x.coerceIn(0f, maxScroll.x), new.y.coerceIn(0f, maxScroll.y))
}

fun performScrollCommand(scrollPos: Point, scrollCommand: ScrollCommand, scrollSize: Size, contentSize: Size): Point {
  val viewport = Rect(scrollPos.x,
                      scrollPos.y,
                      scrollPos.x + scrollSize.width,
                      scrollPos.y + scrollSize.height)
  return if (!viewport.contains(scrollCommand)) {
    val nextScrollPos = scrollCommand.offset(-scrollSize.width / 2f, -scrollSize.height / 2f)
    val maxScroll = Point(max(0f, contentSize.width - scrollSize.width),
                          max(0f, contentSize.height - scrollSize.height))
    val nextScrollPosClamped = Point(nextScrollPos.x.coerceIn(0f, maxScroll.x),
                                     nextScrollPos.y.coerceIn(0f, maxScroll.y))
    nextScrollPosClamped
  }
  else {
    scrollPos
  }
}

fun clampScrollPos(scrollPos: Point, maxScroll: Point): Point =
  if (scrollPos.x < 0f ||
      scrollPos.x > maxScroll.x ||
      scrollPos.y < 0f ||
      scrollPos.y > maxScroll.y) {
    Point(scrollPos.x.coerceIn(0f, maxScroll.x),
          scrollPos.y.coerceIn(0f, maxScroll.y))
  }
  else {
    scrollPos
  }

fun updateScrollPos(scrollPos: Point, maxScroll: Point, scrollCommand: ScrollCommand?, scrollSize: Size, contentSize: Size): Point {
  val clampedScrollPos = clampScrollPos(scrollPos, maxScroll)
  return if (scrollCommand != null) {
    performScrollCommand(clampedScrollPos, scrollCommand, scrollSize, contentSize)
  }
  else {
    clampedScrollPos
  }
}

fun parentScrollCommand(position: Thunk<ScrollState>, scrollCommand: Point, scrollSize: Size, contentSize: Size): Point {
  val maxScroll = maxScrollPos(contentSize, scrollSize)
  val scrollPos = position.read(null)
  val viewport = Rect(scrollPos.x, scrollPos.y, scrollPos.x + scrollSize.width,
                      scrollPos.y + scrollSize.height)
  val updatedScrollPos =
    if (!viewport.contains(scrollCommand)) {
      updateScrollPos(scrollPos, maxScroll, scrollCommand, scrollSize, contentSize)
    }
    else {
      scrollPos
    }
  return scrollCommand.offset(-updatedScrollPos.x, -updatedScrollPos.y)
}

fun maxScrollPos(contentSize: Size, scrollSize: Size): Point =
  Point(max(0f, contentSize.width - scrollSize.width),
        max(0f, contentSize.height - scrollSize.height))

typealias ScrollUpdater = ((ScrollState) -> ScrollState) -> Unit

fun scrollImpl(scrollDirection: ScrollDirection,
               propagate: Propagate,
               scrollbarColor: Color,
               position: Thunk<ScrollState>,
               updater: ScrollUpdater,
               dragState: Thunk<DragState>,
               dragUpdater: ((DragState) -> DragState) -> Unit,
               content: View): View {
  return view { cs ->
    val contentConstraints = when (scrollDirection) {
      ScrollDirection.HORIZONTAL -> Constraints(cs.minWidth, Float.POSITIVE_INFINITY, cs.minHeight,
                                                cs.maxHeight)
      ScrollDirection.VERTICAL -> Constraints(cs.minWidth, cs.maxWidth, cs.minHeight,
                                              Float.POSITIVE_INFINITY)
      ScrollDirection.BOTH -> Constraints(cs.minWidth, Float.POSITIVE_INFINITY, cs.minHeight,
                                          Float.POSITIVE_INFINITY)
    }
    val contentLayout = content(contentConstraints)
    val contentSize = contentLayout.size
    val contentRenderer = contentLayout.renderer
    val scrollSize = Size(contentSize.width.coerceIn(cs.minWidth, cs.maxWidth),
                          contentSize.height.coerceIn(cs.minHeight, cs.maxHeight))

    val maxScrollPos = maxScrollPos(contentSize, scrollSize)
    val layoutNode = LayoutNode.Scroll(position, updater, scrollSize, contentSize)
    layoutNode.addChild(Point.ZERO, contentLayout)
    Layout(scrollSize, layoutNode) { viewport ->
      stack {
        mount(Point.ZERO) {
          renderBoundary(WILDCARD) {
            val drag = read(dragState)
            hitBox(Rect(Point.ZERO, scrollSize),
                   HitCallbacks(onMouseWheel = { e: UserEvent.MouseWheel, _: UserEvent.NodeHit ->
                     val offset = Point(e.delta.dx, e.delta.dy)
                     val scrollPos = position.read(null)
                     val projected = nextScrollPos(scrollPos, scrollDirection, offset, maxScrollPos)
                     if (projected != scrollPos) {
                       updater { scrollPos ->
                         nextScrollPos(scrollPos,
                                       scrollDirection,
                                       offset,
                                       maxScrollPos)
                       }
                       Propagate.STOP
                     }
                     else
                       propagate
                   }))

            if (drag.active) {
              onGlobalEvents(
                GlobalHandlers(
                  scene.windowId(),
                  onMouseMotion = { e: UserEvent.MouseMotion ->
                    when (drag.direction) {
                      DragDirection.HORIZONTAL -> dragUpdater { state ->
                        val newPosition = state.position + e.delta.dx
                        updater { scrollPos ->
                          Point(newPosition * state.ratio, scrollPos.y)
                        }
                        state.copy(position = newPosition)
                      }
                      DragDirection.VERTICAL -> dragUpdater { state ->
                        val newPosition = state.position + e.delta.dy
                        updater { scrollPos ->
                          Point(scrollPos.x, newPosition * state.ratio)
                        }
                        state.copy(position = newPosition)
                      }
                    }
                    Propagate.STOP
                  },
                  onMouseInput = { e: UserEvent.MouseInput ->
                    if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
                      dragUpdater { state -> state.copy(active = false) }
                      Propagate.STOP
                    }
                    else {
                      Propagate.CONTINUE
                    }
                  }))
            }

            // clamp scroll pos
            val scrollPosFromState = read(position)
            val scrollPos = clampScrollPos(scrollPosFromState, maxScrollPos)
            if (scrollPos != scrollPosFromState) {
              updater { scrollPos ->
                clampScrollPos(scrollPos, maxScrollPos)
              }
            }


            val childViewport =
              viewport
                .intersect(Rect(Point.ZERO, scrollSize))
                ?.offset(scrollPos)
                ?.intersect(Rect(Point.ZERO, contentSize))

            scroll(bounds = Rect(Point.ZERO, scrollSize),
                   contentSize = contentSize,
                   scrollPosition = scrollPos) {
              if (childViewport != null) {
                contentRenderer(childViewport)
              }
              else {
                stack {}
              }
            }

            // render scrollbars
            val scrollbarWidth = 4f
            val minScrollbarLenght = 10f
            val scrollbarPadding = 2f
            val vScrollbarVisible = scrollDirection != ScrollDirection.HORIZONTAL && contentSize.height > scrollSize.height
            val hScrollbarVisible = scrollDirection != ScrollDirection.VERTICAL && contentSize.width > scrollSize.width
//            val vScrollbarVisible = false
//            val hScrollbarVisible = false

            if (vScrollbarVisible) {
              val maxh =
                if (hScrollbarVisible)
                  scrollSize.height - 3 * scrollbarPadding - scrollbarWidth
                else
                  scrollSize.height - 2 * scrollbarPadding

              val from = min(maxh - minScrollbarLenght, max(0f, scrollPos.y / contentSize.height * maxh))
              val to = max(from + minScrollbarLenght, min(maxh, (scrollPos.y + scrollSize.height) / contentSize.height * maxh))
              val vScrollbarRect = Rect(Point(scrollSize.width - scrollbarWidth - scrollbarPadding, scrollbarPadding + from),
                                        Size(scrollbarWidth, to - from))
              rect(vScrollbarRect,
                   scrollbarColor)

              hitBox(vScrollbarRect,
                     HitCallbacks(
                       onMouseInput = { e: UserEvent.MouseInput, _: UserEvent.NodeHit ->
                         if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED) {
                           val initialCursor = vScrollbarRect.top
                           val ratio = contentSize.height / scrollSize.height
                           dragUpdater { _ -> DragState(true, DragDirection.VERTICAL, initialCursor, ratio) }
                           Propagate.STOP
                         }
                         else if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
                           dragUpdater { state -> state.copy(active = false) }
                           Propagate.STOP
                         }
                         else {
                           Propagate.CONTINUE
                         }
                       }))
            }

            if (hScrollbarVisible) {
              val maxw =
                if (vScrollbarVisible)
                  scrollSize.width - 3 * scrollbarPadding - scrollbarWidth
                else
                  scrollSize.width - 2 * scrollbarPadding

              val from = min(maxw - minScrollbarLenght, max(0f, scrollPos.x / contentSize.width * maxw))
              val to = max(from + minScrollbarLenght, min(maxw, (scrollPos.x + scrollSize.width) / contentSize.width * maxw))
              val hScrollbarRect =
                Rect(
                  Point(scrollbarPadding + from, scrollSize.height - scrollbarWidth - scrollbarPadding),
                  Size(to - from, scrollbarWidth))
              rect(
                hScrollbarRect,
                scrollbarColor)
              hitBox(hScrollbarRect,
                     HitCallbacks(
                       onMouseInput = { e: UserEvent.MouseInput, _: UserEvent.NodeHit ->
                         if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED) {
                           val initialCursor = hScrollbarRect.left
                           val ratio = contentSize.width / scrollSize.width
                           dragUpdater { _ -> DragState(true, DragDirection.HORIZONTAL, initialCursor, ratio) }
                           Propagate.STOP
                         }
                         else if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
                           dragUpdater { state -> state.copy(active = false) }
                           Propagate.STOP
                         }
                         else {
                           Propagate.CONTINUE
                         }
                       }))
            }
          }
        }
      }
    }
  }
}


inline fun Frame.scroll(scrollDirection: ScrollDirection = ScrollDirection.VERTICAL,
                        propagate: Propagate = Propagate.CONTINUE,
                        scrollbarColor: Color = Color(0f, 0f, 0f, 0.5f),
                        position: Thunk<ScrollState>,
                        noinline update: ((ScrollState) -> ScrollState) -> Unit,
                        crossinline child: Component): View {
  val dragState = state { DragState.empty }
  return scrollImpl(scrollDirection, propagate, scrollbarColor, position, update, dragState, dragState::update, child())
}

tailrec fun toParentScroll(point: Point, layoutNode: LayoutNode): Pair<Point, LayoutNode.Scroll>? {
  val p = layoutNode.parent
  return when {
    p == null -> null
    p is LayoutNode.Scroll -> point.offset(layoutNode.origin) to p
    else -> toParentScroll(point.offset(layoutNode.origin), p)
  }
}

tailrec fun LayoutNode.scrollTo(point: Point = Point.ZERO) {
  val parentScroll = toParentScroll(point, this)
  if (parentScroll != null) {
    val (point, scroll) = parentScroll
    val updater = scroll.scrollUpdater
    val scrollSize = scroll.scrollSize
    val contentSize = scroll.contentSize
    updater { scrollPos ->
      performScrollCommand(scrollPos, point, scrollSize, contentSize)
    }
    val parentPoint = parentScrollCommand(scroll.scrollPosition, point, scrollSize, contentSize)
    scroll.scrollTo(parentPoint)
  }
}

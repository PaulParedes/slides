package noria.ui

import noria.*
import noria.scene.*
import kotlin.math.max
import kotlin.math.min

enum class SplitDirection {
  HORIZONTAL, // first: Left, second: Right
  VERTICAL    // first: Top, second: Bottom
}

data class SplitState(val ratio: Float, val cursorPosition: Float, val drag: Boolean)

fun splitSeparator(direction: SplitDirection,
                   thickness: Float = 2f,
                   color: Color,
                   update: ((SplitState) -> SplitState) -> Unit): View {

  val size = when (direction) {
    SplitDirection.HORIZONTAL -> Size(thickness, Float.MAX_VALUE)
    SplitDirection.VERTICAL -> Size(Float.MAX_VALUE, thickness)
  }

  return view { cs ->
    val constrainedSize = Size(size.width.coerceIn(cs.minWidth, cs.maxWidth),
                               size.height.coerceIn(cs.minHeight, cs.maxHeight))

    Layout(constrainedSize, LayoutNode()) { viewport ->
      stack {
        rect(Rect(Point.ZERO, constrainedSize),
             color)
        hitBox(Rect(Point.ZERO, constrainedSize),
               HitCallbacks(
                 onMouseInput = { e: UserEvent.MouseInput, _: UserEvent.NodeHit ->
                   if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED) {
                     val initial = when (direction) {
                       SplitDirection.VERTICAL -> e.cursorPosition.y
                       SplitDirection.HORIZONTAL -> e.cursorPosition.x
                     }
                     update { (r, _) -> SplitState(r, initial, true) }
                     Propagate.STOP
                   }
                   else if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
                     update { (r, _) -> SplitState(r, 0f, false) }
                     Propagate.STOP
                   }
                   else {
                     Propagate.CONTINUE
                   }
                 }
               )
        )
      }
    }
  }
}

fun splitOverlay(direction: SplitDirection,
                 update: ((SplitState) -> SplitState) -> Unit,
                 content: View): View {
  return view { cs ->
    val overlayLayoutNode = LayoutNode()
    val contentLayout = content(cs)
    overlayLayoutNode.addChild(Point.ZERO, contentLayout)
    val size = contentLayout.size
    Layout(size, overlayLayoutNode) { viewport ->
      stack {
        mount(Point.ZERO) {
          contentLayout.renderer(sceneContext, viewport)
        }
        onGlobalEvents(
          GlobalHandlers(
            scene.windowId(),
            onMouseMotion = { e: UserEvent.MouseMotion ->
              when (direction) {
                SplitDirection.HORIZONTAL -> update { state ->
                  val newPosition = state.cursorPosition + e.delta.dx
                  state.copy(cursorPosition = newPosition,
                             ratio = min(1f, max(0f, newPosition / size.width)))
                }
                SplitDirection.VERTICAL -> update { state ->
                  val newPosition = state.cursorPosition + e.delta.dy
                  state.copy(cursorPosition = newPosition,
                             ratio = min(1f, max(0f, newPosition / size.height)))
                }
              }
              Propagate.STOP
            },
            onMouseInput = { e: UserEvent.MouseInput ->
              if (e.button == UserEvent.MouseButton.LEFT && e.state == UserEvent.ButtonState.RELEASED) {
                update { state -> state.copy(drag = false) }
                Propagate.STOP
              }
              else {
                Propagate.CONTINUE
              }
            })
        )
      }
    }
  }
}

fun Frame.splitImpl(direction: SplitDirection,
                    tState: Thunk<SplitState>,
                    firstChild: View,
                    secondChild: View,
                    separatorColor: Color,
                    update: ((SplitState) -> SplitState) -> Unit): View {
  return boundary {
    expr(WILDCARD) {
      val (ratio, _, drag) = read(tState)

      val splitView = when (direction) {
        SplitDirection.HORIZONTAL -> {
          hbox {
            stretch(ratio = ratio) { firstChild }
            hug { splitSeparator(SplitDirection.HORIZONTAL, update = update, color = separatorColor) }
            stretch(ratio = 1 - ratio) { secondChild }
          }
        }
        SplitDirection.VERTICAL -> {
          vbox {
            stretch(ratio = ratio) { firstChild }
            hug { splitSeparator(SplitDirection.VERTICAL, update = update, color = separatorColor) }
            stretch(ratio = 1 - ratio) { secondChild }
          }
        }
      }

      if (drag) {
        splitOverlay(direction, update, splitView)
      }
      else {
        splitView
      }
    }
  }
}

fun Frame.split(state: Thunk<SplitState>,
                update: ((SplitState) -> SplitState) -> Unit,
                firstChild: View,
                secondChild: View,
                separatorColor: Color = Color(1f, 0f, 1f, 1f),
                direction: SplitDirection = SplitDirection.HORIZONTAL): View {
  return splitImpl(direction, state, firstChild, secondChild, separatorColor, update)
}

fun Frame.split(firstChild: View,
                secondChild: View,
                ratio: Float = 0.5f,
                separatorColor: Color = Color(1f, 0f, 1f, 1f),
                direction: SplitDirection = SplitDirection.HORIZONTAL): View {
  val splitState = state { SplitState(ratio, 0f, false) }
  return splitImpl(direction, splitState, firstChild, secondChild, separatorColor, splitState::update)
}

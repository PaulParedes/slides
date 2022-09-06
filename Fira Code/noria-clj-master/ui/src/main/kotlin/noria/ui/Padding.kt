package noria.ui

import noria.Frame
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect
import noria.scene.Size
import kotlin.math.max

@Suppress("NAME_SHADOWING")
fun paddingImpl(top: Dimension,
                right: Dimension,
                bottom: Dimension,
                left: Dimension,
                child: View): View =
  view { cs ->
    val top = top.convert(cs.maxHeight)
    val right = right.convert(cs.maxWidth)
    val bottom = bottom.convert(cs.maxHeight)
    val left = left.convert(cs.maxWidth)

    val childCs = Constraints(max(cs.minWidth - left - right, 0f),
                              max(cs.maxWidth - left - right, 0f),
                              max(cs.minHeight - top - bottom, 0f),
                              max(cs.maxHeight - top - bottom, 0f))
    val childLayout = child(childCs)
    val size = childLayout.size
    val renderer = childLayout.renderer
    val childBounds = Rect(left, top, left + size.width, top + size.height)

    val layoutNode = LayoutNode()
    layoutNode.addChild(childBounds.origin(), childLayout)

    Layout(Size(size.width + left + right,
                size.height + top + bottom), layoutNode) { viewport ->
      val childVP = childViewport(childBounds, viewport)
      stack {
        if (childVP != null) {
          mount(Point(left, top)) {
            renderer(childVP)
          }
        }
      }
    }
  }

data class Padding(val top: Dimension,
                   val right: Dimension,
                   val bottom: Dimension,
                   val left: Dimension)

inline fun Frame.padding(padding: Padding,
                         crossinline child: Component): View {
  val c = child()
  return paddingImpl(padding.top, padding.right, padding.bottom, padding.left, c)
}

inline fun Frame.padding(horizontal: Dimension = Dimension.px(0f),
                         vertical: Dimension = Dimension.px(0f),
                         crossinline child: Component): View {
  val c = child()
  return paddingImpl(vertical, horizontal, vertical, horizontal, c)
}

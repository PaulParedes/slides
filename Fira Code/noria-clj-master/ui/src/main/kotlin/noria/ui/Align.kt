package noria.ui

import noria.Frame
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect
import noria.scene.Size
import kotlin.math.max

fun childViewport(childBounds: Rect, parentViewport: Rect): Rect? {
  return childBounds.intersect(parentViewport)?.offset(Point(-childBounds.left, -childBounds.top))
}

fun alignImpl(alignX: Float = 0f,
              alignY: Float = 0f,
              childAlignX: Float = alignX,
              childAlignY: Float = alignY,
              child: View): View {
  return view { cs ->
    val childCs = Constraints(0f, cs.maxWidth, 0f, cs.maxHeight)
    val childLayout = child(childCs)
    val childSize = childLayout.size
    val offsetX =
      if (childSize.width < cs.minWidth) {
        cs.minWidth * alignX - childSize.width * childAlignX
      }
      else {
        0f
      }
    val offsetY =
      if (childSize.height < cs.minHeight) {
        cs.minHeight * alignY - childSize.height * childAlignY
      }
      else {
        0f
      }
    val childOrigin = Point(offsetX, offsetY)
    val childRenderer = childLayout.renderer
    val layoutNode = LayoutNode()
    layoutNode.addChild(childOrigin, childLayout)
    Layout(Size(max(cs.minWidth, childSize.width),
                max(cs.minHeight, childSize.height)), layoutNode) { viewport ->
      stack {
        val childViewport = childViewport(Rect(childOrigin, childSize), viewport)
        if (childViewport != null) {
          mount(childOrigin) {
            childRenderer(childViewport)
          }
        }
      }
    }
  }
}

inline fun Frame.align(alignX: Float = 0f,
                       alignY: Float = 0f,
                       childAlignX: Float = alignX,
                       childAlignY: Float = alignY,
                       crossinline child: Component): View {
  return alignImpl(alignX, alignY, childAlignX, childAlignY, child())
}
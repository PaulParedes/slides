package noria.ui

import noria.Frame
import noria.scene.*

fun decorateImpl(backgroundColor: Color? = null,
                 borderRadius: Float? = null,
                 child: View): View =
  { cs ->
    val (size, layoutNode, renderer) = child(cs)
    Layout(size, layoutNode) { viewport ->
      val rect = Rect(Point.ZERO, size)
      val childStack = renderer(viewport)

      val s =
        if (backgroundColor != null) {
          stack {
            rect(rect, backgroundColor)
            mount(Point.ZERO, childStack)
          }
        }
        else {
          childStack
        }

      if (borderRadius != null) {
        stack {
          clip(rect, Scene.ComplexClipRegion(rect,
                                             BorderRadius.uniform(borderRadius),
                                             Scene.ClipMode.CLIP)) {
            s
          }
        }
      }
      else {
        s
      }
    }
  }

inline fun Frame.decorate(backgroundColor: Color? = null,
                          borderRadius: Float? = null,
                          crossinline child: Component): View {
  return decorateImpl(backgroundColor, borderRadius, child())
}

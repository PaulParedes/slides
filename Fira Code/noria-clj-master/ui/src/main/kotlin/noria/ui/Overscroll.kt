package noria.ui

import noria.Frame
import noria.onDestroy
import noria.scene.Point
import noria.scene.Rect
import noria.scene.Size

val overscrollRatio: Float = 0.5f

fun enlargeViewport(viewport: Rect, size: Size): Rect {
  val dh = (viewport.height() * overscrollRatio) / 2.0f
  return Rect(viewport.left, viewport.top - dh, viewport.right, viewport.bottom + dh).intersect(Rect(Point.ZERO, size))!!
}

fun overscrollImpl(view: View): View =
  view { cs ->
    val layout = view(cs)
    val renderer = layout.renderer
    val size = layout.size
    var cachedViewport: Rect? = null
    var cachedStack: SceneNode.Stack? = null
    Layout(size, layout.layoutNode) { viewport ->
      onDestroy {
        cachedStack = null
        cachedViewport = null
      }
      val cachedViewport1 = cachedViewport
      if (cachedViewport1 == null ||
          !cachedViewport1.contains(viewport.origin()) ||
          !cachedViewport1.contains(Point(viewport.right, viewport.bottom))) {
        val enlarged = enlargeViewport(viewport, size)
        val stack = renderer(enlarged)
        cachedViewport = enlarged
        cachedStack = stack
        stack
      } else {
        cachedStack!!
      }
    }
  }


inline fun Frame.overscroll(crossinline component: Component): View {
  return overscrollImpl(component())
}


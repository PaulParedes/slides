package noria.ui

import noria.Frame
import noria.generateKey
import noria.scene.Size
import noria.scene.Point
import noria.scene.Rect
import noria.scope

class Overlay(val children: ArrayList<Pair<Any, View>> = ArrayList()) {
  @Suppress("NOTHING_TO_INLINE")
  inline fun add(key: Any = generateKey(), noinline view: View) {
    children.add(key to view)
  }
}

fun overlayImpl(overlay: Overlay): View =
  view { cs ->
    val children = ArrayList<Layout>()
    var maxWidth = 0f
    var maxHeight = 0f
    val layoutNode = LayoutNode()
    for ((key, child) in overlay.children) {
      val childLayout = scope(key) { child(cs) }
      children.add(childLayout)
      if (childLayout.size.width > maxWidth) {
        maxWidth = childLayout.size.width
      }
      if (childLayout.size.height > maxHeight) {
        maxHeight = childLayout.size.height
      }
      layoutNode.addChild(Point.ZERO, childLayout)
    }
    Layout(Size(maxWidth, maxHeight), layoutNode) { viewport ->
      stack {
        children.forEachIndexed { i, child ->
          mount(Point.ZERO) {
            val renderer = child.renderer
            scope(overlay.children[i].first) {
              renderer(viewport.intersect(Rect(Point.ZERO, child.size)))
            }
          }
        }
      }
    }
  }

inline fun Frame.overlay(crossinline builder: Overlay.() -> Unit): View {
  val overlay = Overlay()
  overlay.builder()
  return overlayImpl(overlay)
}
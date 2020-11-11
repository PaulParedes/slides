package noria.ui

import noria.Thunk
import noria.scene.Point
import noria.scene.Size

open class LayoutNode {
  var parent: LayoutNode? = null
  lateinit var origin: Point

  fun addChild(origin: Point, layout: Layout) {
    layout.layoutNode.parent = this
    layout.layoutNode.origin = origin
  }

  class Scroll(val scrollPosition: Thunk<Point>,
               val scrollUpdater: ScrollUpdater,
               val scrollSize: Size,
               val contentSize: Size) : LayoutNode()

  class Ref: LayoutNode()
}
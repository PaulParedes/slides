package noria.ui

import noria.scene.Point
import noria.scene.Rect

typealias NodeId = Long

sealed class SceneNode(var id: NodeId) {
  var parent: SceneNode? = null
  abstract val origin: Point

  class Stack(id: NodeId) : SceneNode(id) {
    override var origin: Point = Point.ZERO
  }

  class Scroll(id: NodeId,
               val bounds: Rect) : SceneNode(id) {
    override val origin: Point
      get() = bounds.origin()
  }

  class Clip(id: NodeId,
             val bounds: Rect) : SceneNode(id) {
    override val origin: Point
      get() = bounds.origin()
  }
}
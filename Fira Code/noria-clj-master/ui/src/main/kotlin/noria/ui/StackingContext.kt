package noria.ui

import gnu.trove.TLongArrayList
import noria.OpenFrame
import noria.expr
import noria.scene.*

class StackingContext(val stack: SceneNode.Stack,
                      val sceneContext: SceneContext) : OpenFrame<Nothing, Nothing, Nothing> by sceneContext {
  val scene: Scene = sceneContext.scene
  val children = TLongArrayList()

  fun add(nodeId: NodeId) {
    children.add(nodeId)
  }

  fun add(node: SceneNode) {
    add(node.id)
    node.parent = stack
  }

  fun mount(position: Point, stack: SceneNode.Stack) {
    stack.origin = position
    scene.setPosition(stack.id, position)
    add(stack)
  }

  inline fun mount(position: Point, crossinline builder: SceneContext.() -> SceneNode.Stack) {
    mount(position, sceneContext.builder())
  }

  inline fun scroll(bounds: Rect,
                    contentSize: Size,
                    scrollPosition: Point,
                    crossinline content: SceneContext.() -> SceneNode.Stack) {
    val scrollId = expr {}.thunkId
    val stack = sceneContext.content()
    val scroll = SceneNode.Scroll(scrollId, bounds)
    stack.parent = scroll
    stack.origin = Point.ZERO
    scene.scroll(
      scrollId,
      bounds.origin(),
      bounds.size(),
      stack.id,
      contentSize)
    scene.scrollPosition(scrollId, scrollPosition)
    add(scroll)
  }

  inline fun clip(clipRect: Rect,
                  vararg clips: Scene.ComplexClipRegion,
                  crossinline content: SceneContext.() -> SceneNode) {
    val stack = sceneContext.content()
    val clipId = nextId()
    val clip = SceneNode.Clip(clipId, clipRect)
    stack.parent = clip
    scene.clip(clipId, clipRect, clips.toList(), stack.id)
    add(clip)
  }

  fun border(bounds: Rect, color: Color, lineWidth: Float, style: Scene.BorderStyle, radius: BorderRadius) {
    val node = nextId()
    scene.border(node, bounds.origin(), bounds.size(), color, lineWidth, style, radius)
    add(node)
  }

  fun rect(bounds: Rect, color: Color) {
    val node = nextId()
    scene.rect(node, bounds.origin(), bounds.size(), color)
    add(node)
  }

  fun text(origin: Point, size: Size, color: Color, textLayout: PhotonApi.TextLayout) {
    val node = nextId()
    scene.text(node, origin, size, color, textLayout)
    add(node)
  }

  fun image(bounds: Rect, imageId: Long) {
    val node = nextId()
    scene.image(node, bounds.origin(), bounds.size(), imageId)
    add(node)
  }
}
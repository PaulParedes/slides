package noria.ui.v2

import noria.Frame
import noria.OpenFrame
import noria.Thunk
import noria.scene.*
import noria.ui.Dimension
import noria.ui.ScrollUpdater
import noria.ui.childViewport
import kotlin.math.max

typealias Children = Node.() -> Unit
typealias Layouter = Node.LayoutNode.(Constraints) -> Unit
typealias Renderer = Node.RenderNode.(Rect) -> Unit
typealias SceneNodeId = Long

sealed class Node(var frame: Frame? = null): OpenFrame<Nothing, Nothing, Nothing> by frame as OpenFrame<Nothing, Nothing, Nothing> {

  val children: ArrayList<Node> = ArrayList()
  var parent: Node? = null


  open fun emit(child: Node) {
    children.add(child)
    child.parent = this
    child.frame = this.frame
  }

  class Collector(val node: Node, val filter: (Node) -> Boolean) : Node() {
    val result = ArrayList<Node>()

    override fun emit(child: Node) {
      if (filter(child)) {
        result.add(child)
      }
      else {
        node.emit(child)
      }
    }
  }

  sealed class LayoutNode(val layouter: Layouter) : Node() {
    class Scroll(val scrollPosition: Thunk<Point>,
                 val scrollUpdater: ScrollUpdater,
                 val scrollSize: Size,
                 val contentSize: Size,
                 layouter: Layouter) : LayoutNode(layouter)

    class Generic(layouter: Layouter) : LayoutNode(layouter)

    fun emit(origin: Point, renderNode: RenderNode) {
      renderNode.origin = origin
      emit(renderNode)
    }
  }

  class RenderNode(val size: Size,
                   val layoutNode: LayoutNode,
                   val renderer: Renderer) : Node() {
    lateinit var origin: Point
  }

  sealed class SceneNode(var nodeId: SceneNodeId) : Node() {
    lateinit var scene: Scene

    abstract val origin: Point

    class Stack(nodeId: SceneNodeId) : SceneNode(nodeId) {
      override var origin: Point = Point.ZERO
    }

    class Scroll(nodeId: SceneNodeId,
                 val bounds: Rect) : SceneNode(nodeId) {
      override val origin: Point
        get() = bounds.origin()
    }

    class Clip(nodeId: SceneNodeId,
               val bounds: Rect) : SceneNode(nodeId) {
      override val origin: Point
        get() = bounds.origin()
    }
  }
}

fun Node.layoutNode(layouter: Layouter) {
  emit(Node.LayoutNode.Generic(layouter))
}

fun Node.LayoutNode.renderNode(size: Size,layouter: Renderer) {
  emit(Node.RenderNode(size, this, layouter))
}

fun Node.collectLayoutNodes(children: Children): List<Node.LayoutNode> {
  val collector = Node.Collector(this) { node -> node is Node.LayoutNode }
  collector.children()
  return collector.result as List<Node.LayoutNode>
}

fun Node.collectRenderNodes(children: Children): List<Node.RenderNode> {
  val collector = Node.Collector(this) { node -> node is Node.RenderNode }
  collector.children()
  return collector.result as List<Node.RenderNode>
}

fun Node.collectSceneNodes(children: Children): List<Node.SceneNode> {
  val collector = Node.Collector(this) { node -> node is Node.SceneNode }
  collector.children()
  return collector.result as List<Node.SceneNode>
}

fun Node.stack(children: Node.SceneNode.() -> Unit) {
  emit(Node.SceneNode.Stack(frame!!.nextId()))
}

fun Node.SceneNode.Stack.mount(origin: Point, children: Node.() -> Unit) {
  val sceneNodes = collectSceneNodes { children() }

}

fun Node.padding(top: Dimension = Dimension.zero,
                 right: Dimension = Dimension.zero,
                 bottom: Dimension = Dimension.zero,
                 left: Dimension = Dimension.zero,
                 children: Children) {
  val child = collectLayoutNodes(children).single()
  val childLayouter = child.layouter
  layoutNode { cs ->
    val top = top.convert(cs.maxHeight)
    val right = right.convert(cs.maxWidth)
    val bottom = bottom.convert(cs.maxHeight)
    val left = left.convert(cs.maxWidth)
    val childCs = Constraints(max(cs.minWidth - left - right, 0f),
                              max(cs.maxWidth - left - right, 0f),
                              max(cs.minHeight - top - bottom, 0f),
                              max(cs.maxHeight - top - bottom, 0f))

    val childRenderNode = collectRenderNodes {
      childLayouter(childCs)
    }.single()
    val size = childRenderNode.size
    val renderer = childRenderNode.renderer
    val childBounds = Rect(left, top, left + size.width, top + size.height)
    emit(childBounds.origin(), childRenderNode)
    renderNode(Size(size.width + left + right,
                    size.height + top + bottom)) { viewport ->
      val childVP = childViewport(childBounds, viewport)
      stack {
        if (childVP != null) {
//          mount(Point(left, top)) {
//            renderer(childVP)
//          }
        }
      }
    }
  }
}

fun Node.text(text: String) {

}

fun Node.myView1() {
  text("hey")
}

fun Node.myView() {
  padding {
    myView1()
  }
}


/*typealias Component = Frame.() -> View
typealias View = Frame.(Constraints) -> Layout
data class Layout(val size: Size,
                  val layoutNode: LayoutNode,
                  val renderer: Renderer)
typealias Renderer = SceneContext.(viewport: Rect) -> Stack
typealias Stack = SceneNode.Stack
typealias IncrementalComponent = Frame.() -> Thunk<View>
*/
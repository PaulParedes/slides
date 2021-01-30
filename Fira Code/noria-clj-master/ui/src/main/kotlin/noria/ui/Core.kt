package noria.ui

import noria.*
import noria.scene.*

typealias Component = Frame.() -> View
typealias View = Frame.(Constraints) -> Layout
data class Layout(val size: Size,
                  val layoutNode: LayoutNode,
                  val renderer: Renderer)
typealias Renderer = SceneContext.(viewport: Rect) -> Stack
typealias Stack = SceneNode.Stack
typealias IncrementalComponent = Frame.() -> Thunk<View>

fun view(v: View): View {
  return v
}

fun boundaryImpl(t: Thunk<View>): View =
  view { constraints ->
    val view = read(t)
    val (size, node, renderer) = view(constraints)
    Layout(size, node) { viewport ->
      renderer(viewport)
    }
  }

inline fun Frame.boundary(crossinline b: IncrementalComponent): View {
  return boundaryImpl(currentFrame.b())
}

inline fun Frame.constrain(crossinline f: (Constraints) -> Constraints, crossinline child: Component): View {
  val c = child()
  return view { constraints ->
    c(f(constraints))
  }
}

inline fun Frame.ref(crossinline c: Component): Pair<View, LayoutNode.Ref> {
  val v = c()
  val layoutNode = LayoutNode.Ref()
  return view { cs ->
    val layout = v(cs)
    layoutNode.addChild(Point.ZERO, layout)
    Layout(layout.size, layoutNode, layout.renderer)
  } to layoutNode
}

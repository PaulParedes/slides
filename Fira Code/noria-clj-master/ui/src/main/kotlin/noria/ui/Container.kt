package noria.ui

import noria.onDestroy
import noria.scene.Point
import noria.scene.Rect
import noria.scene.Size
import noria.scope
import java.util.*

data class ContainerChild(val layout: Layout,
                          val key: Any,
                          val position: Point)

fun container(size: Size, children: List<ContainerChild>): Layout {
  val viewports = arrayOfNulls<Rect?>(children.size)
  val stacks = arrayOfNulls<SceneNode.Stack?>(children.size)
  val layoutNode = LayoutNode()
  for (c in children) {
    layoutNode.addChild(c.position, c.layout)
  }
  return Layout(size, layoutNode) { viewport ->
    onDestroy {
      Arrays.fill(viewports, null)
    }
    stack {
      children.forEachIndexed { i, (layout, key, position) ->
        val bounds = Rect(position, layout.size)
        val intersection = viewport.intersect(bounds)?.offset(-bounds.left, -bounds.top)
        if (intersection != null) {
          val stack =
            if (intersection == viewports[i]) {
              stacks[i]!!
            }
            else {
              val renderer = layout.renderer
              scope(key) { sceneContext.renderer(intersection) }
            }
          stacks[i] = stack
          viewports[i] = intersection
          mount(bounds.origin(), stack)
        }
        else {
          viewports[i] = null
        }
      }
    }
  }
}

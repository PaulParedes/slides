package noria.layout

import noria.*
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect
import noria.scene.Size
import kotlin.math.max

data class ChildPosition<TChild>(val child: TChild, val position: Rect)

data class VirtualLayout<TChild>(val size: Size,
                                 val childrenLayout: List<ChildPosition<TChild>>,
                                 val materialize: (Frame, TChild) -> Thunk<RenderObject>)

interface VirtualLayoutAlgorithm<TSource, TChild> {
  fun layout(frame: Frame, cs: Constraints, source: TSource): VirtualLayout<TChild>
}

data class ListDataSource<TItem, TKey>(val list: List<TItem>,
                                       val keyFn: (TItem) -> TKey,
                                       val renderFn: (Frame, TItem) -> Thunk<RenderObject>)

class VirtualColumn<TChild, TKey> : VirtualLayoutAlgorithm<ListDataSource<TChild, TKey>, TChild> {

  override fun layout(frame: Frame, cs: Constraints, source: ListDataSource<TChild, TKey>): VirtualLayout<TChild> {
    with(frame) {
      val representatives = HashMap<TKey, Size>()

      val layout = ArrayList<ChildPosition<TChild>>(source.list.size)
      var y = 0f
      var maxX = Float.MIN_VALUE
      var maxY = Float.MIN_VALUE
      val childCs = Constraints(cs.minWidth, cs.maxWidth, 0f, cs.maxHeight)
      for (child in source.list) {
        val key = source.keyFn(child)!!
        if (!representatives.containsKey(key)) {
          val size = scope(key as Any) {
            memo(source, child, childCs) {
              source.renderFn(currentFrame, child).read(currentFrame).measure(currentFrame, childCs).asSize()
            }
          }
          representatives[key] = size
        }
        val size = representatives[key]!!
        val left = 0f
        val top = y
        val right = left + size.width
        val bottom = top + size.height
        layout.add(ChildPosition(child, Rect(left, top, right, bottom)))
        maxX = max(maxX, right)
        maxY = max(maxY, bottom)
        y = bottom
      }
      return VirtualLayout(Size(maxX, maxY), layout, source.renderFn)
    }
  }
}

data class VirtualContainer<TSource, TChild>(val algorithm: VirtualLayoutAlgorithm<TSource, TChild>,
                                             val source: TSource) : RenderObject {

  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    val layout = algorithm.layout(frame, cs, source)
    return Measure(layout.size.width, layout.size.height, layout)
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val scene = scene()
      val layout = measure.extra as VirtualLayout<TChild>
      val viewport = read(tViewport)
      val renderedChildren = ArrayList<RenderResult>()
      val renderFn = layout.materialize
      for (idx in layout.childrenLayout.indices) {
        val rect = layout.childrenLayout[idx].position
        val child = layout.childrenLayout[idx].child
        val intersection = rect.intersect(viewport)
        if (intersection != null && child != null) {
          scope(child as Any) {
            val tChildViewport = expr(intersection, rect) { intersection.offset(-rect.left, -rect.top) }
            val tChildOffset = expr(tWindowOffset, rect) { read(tWindowOffset).offset(-rect.left, -rect.top) }
            val tRenderObject = thunk<RenderObject>(renderFn) { renderFn(currentFrame, child) }
            val childCs = Constraints.tight(rect.size())
            val tChildMeasure = expr(tRenderObject, childCs) { read(tRenderObject).measure(currentFrame, childCs) }
            val renderResult = memo(tRenderObject, tChildMeasure, tChildViewport, tChildOffset) {
              read(tRenderObject).render(currentFrame, read(tChildMeasure), tChildViewport, tChildOffset)
            }
            if (renderResult.node != null) {
              scene.setPosition(renderResult.node, rect.origin())
            }
            renderedChildren.add(renderResult)
          }
        }
      }
      val id = generateId()
      scene.stack(id, renderedChildren.mapNotNull { it.node }.toLongArray())
      return RenderResult(id, renderedChildren.flatMap { it.overlays })
    }
  }
}

fun <TChild, TKey> virtualColumn(children: List<TChild>,
                                 keyFn: (TChild) -> TKey,
                                 renderFn: (Frame, TChild) -> Thunk<RenderObject>): RenderObject {
  return VirtualContainer(VirtualColumn(), ListDataSource(children, keyFn, renderFn))
}


data class TreeDataSource<TNode>(val root: TNode,
                                 val showRoot: Boolean = true,
                                 val childrenFn: (Frame, TreeDataSource<TNode>, TNode) -> List<TNode>,
                                 val renderFn: (Frame, TNode) -> Thunk<RenderObject>) {
  companion object {
    val constructorParameterNames = listOf("root", "showRoot", "childrenFn", "renderFn")
  }
}

data class TreeRenderOpts(val nodeHeight: Float, val stepWidth: Float = 8f) {
  companion object {
    val constructorParameterNames = listOf("nodeHeight", "stepWidth")
  }
}

fun <TChild> virtualTreeViewLayout(frame: Frame,
                                   cs: Constraints,
                                   source: TreeDataSource<TChild>,
                                   node: TChild,
                                   renderOpts: TreeRenderOpts): VirtualLayout<TChild> {
  with(frame) {
    // calculate width using max of viewport
    // fixed for now todo add groups

    val nodeHeight = renderOpts.nodeHeight
    var stepWidth = renderOpts.stepWidth
    var accumulatedY = 0f

    val layout = ArrayList<ChildPosition<TChild>>()

    if (node == source.root && !source.showRoot) {
      stepWidth = 0f;
    }
    else {
      layout.add(ChildPosition(node, Rect(0f, 0f, Float.POSITIVE_INFINITY, nodeHeight)))
      accumulatedY = nodeHeight
    }

    for ((i, child) in source.childrenFn(currentFrame, source, node).withIndex()) {
      // todo if node is not expandable do not create new frame
      scope(i) {
        val childLayout = memo(cs, source, child, renderOpts) {
          virtualTreeViewLayout(this, cs, source, child, renderOpts)
        }

        layout.addAll(childLayout.childrenLayout
                        .map { relativePosition ->
                          ChildPosition(relativePosition.child,
                                        Rect(relativePosition.position.left + stepWidth,
                                             relativePosition.position.top + accumulatedY,
                                             relativePosition.position.right + stepWidth,
                                             relativePosition.position.bottom + accumulatedY))
                        })
        accumulatedY += childLayout.size.height
      }
    }
    return VirtualLayout(Size(Float.POSITIVE_INFINITY, accumulatedY), layout, source.renderFn)
  }
}

class VirtualTreeView<TChild>(val renderOpts: TreeRenderOpts) : VirtualLayoutAlgorithm<TreeDataSource<TChild>, TChild> {
  override fun layout(frame: Frame, cs: Constraints, source: TreeDataSource<TChild>): VirtualLayout<TChild> {
    return virtualTreeViewLayout(frame, cs, source, source.root, renderOpts)
  }
}

fun <TChild> virtualTreeView(source: TreeDataSource<TChild>, renderOpts: TreeRenderOpts): RenderObject {
  return VirtualContainer(VirtualTreeView<TChild>(renderOpts), source)
}

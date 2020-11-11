package noria.layout

import noria.*
import noria.scene.*

data class LayoutPosition(val tChild: TRenderObject,
                          val origin: Point,
                          val measure: Measure) {
  override fun toString() =
    "LayoutPosition($tChild $origin -> ${measure.asSize()})"
}

data class Layout(val size: Size,
                  val positions: List<LayoutPosition>)

interface LayoutAlgorithm<TArg> {
  fun layout(frame: Frame, cs: Constraints, arg: TArg): Layout
  fun displayNameImpl(arg: TArg): String = javaClass.getSimpleName()
}

class ChildKeys() {
  val keys = HashMap<TRenderObject, Int>()

  fun forChild(tChild: TRenderObject): Pair<TRenderObject, Int> =
    Pair(tChild, keys.merge(tChild, 0) { x, _ -> x + 1 }!!)
}

data class Container<TArg>(val hitHandler: HitHandler?,
                           val algorithm: LayoutAlgorithm<TArg>,
                           val arg: TArg) : RenderObject {
  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    with(frame) {
      val layout = nesting(frame) { algorithm.layout(currentFrame, cs, arg) }
      val width = clamp(layout.size.width, cs.minWidth, cs.maxWidth)
      val height = clamp(layout.size.height, cs.minHeight, cs.maxHeight)
      return Measure(width, height, layout)
    }
  }

  override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? {
    with(frame) {
      val layout = measure.extra as Layout
      val childKeys = ChildKeys()
      return nesting(frame) n@{
        for ((tChild, childOrigin, childMeasure) in layout.positions) {
          val scrollTo = scope(childKeys.forChild(tChild)) {
            memo(tChild, childMeasure) {
              read(tChild).makeVisible(currentFrame, childMeasure)
            }
          }
          if (scrollTo != null)
            return@n scrollTo.copy(pos = scrollTo.pos.offset(childOrigin))
        }
        return@n null
      }
    }
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val id = generateId()
      val scene = scene()
      val children = mutableListOf<RenderResult>()
      val viewport = read(tViewport)

      if (hitHandler != null) {
        val hitboxId = generateId()
        expr(measure, hitHandler) {
          scene().rect(hitboxId, Point(.0f, .0f), measure.asSize(),
                       Color(0f, 0f, 0f, 0f))
          scene().onEvent(hitboxId, hitHandler.tCallback.thunkId, hitHandler.mask)
        }
        children.add(RenderResult(hitboxId))
      }

      val layout = measure.extra as Layout
      val childKeys = ChildKeys()
      return nesting(frame) {
        for ((tChild, childOrigin, childMeasure) in layout.positions) {
          val bounds = Rect(childOrigin, childMeasure.asSize())
          val intersection = viewport.intersect(bounds)
          if (intersection != null) {
            scope(childKeys.forChild(tChild)) {
              val renderResult = memo(tChild, intersection, bounds, childMeasure, tWindowOffset) {
                val childViewport = intersection.offset(-bounds.left, -bounds.top)
                if (childViewport != null) {
                  val tChildOffset = expr(tWindowOffset, bounds) {
                    read(tWindowOffset).offset(bounds.left, bounds.top)
                  }
                  read(tChild).render(currentFrame, childMeasure, expr(childViewport) { childViewport }, tChildOffset)
                }
                else
                  null
              }

              if (renderResult != null) {
                if (renderResult.node != null) {
                  scene.setPosition(renderResult.node, bounds.origin())
                }
                children.add(renderResult)
              }
            }
          }
        }
        scene.stack(id, children.mapNotNull { it.node }.toLongArray())
        RenderResult(id, children.flatMap { it.overlays })
      }
    }
  }

  override fun displayNameImpl(): String {
    return algorithm.displayNameImpl(arg)
  }
}

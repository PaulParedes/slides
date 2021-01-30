package noria.layout

import noria.*
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect

data class Constrainer(val tChild: TRenderObject,
                       val constraint: (Constraints) -> Constraints)
    : RenderObject {
    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        val constraints = constraint(cs)
        with (frame) {
            val childMeasure = nesting (frame) { memo(tChild, constraints) { read(tChild).measure(currentFrame, constraints) }}
            return Measure(clamp(childMeasure.width, constraints.minWidth, constraints.maxWidth),
                clamp(childMeasure.height, constraints.minHeight, constraints.maxHeight), childMeasure)
        }
    }

    override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? {
        with(frame) {
          return nesting(frame) {
            memo(tChild, measure.extra) {
              val childMeasure = measure.extra as Measure
              read(tChild).makeVisible(currentFrame, childMeasure)
            }
          }
        }
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
        with(frame) {
          return nesting(frame) {
            memo(tChild, measure.extra, tViewport, tWindowOffset) {
              val childMeasure = measure.extra as Measure
              read(tChild).render(currentFrame, childMeasure, tViewport, tWindowOffset)
            }
          }
        }
    }
}
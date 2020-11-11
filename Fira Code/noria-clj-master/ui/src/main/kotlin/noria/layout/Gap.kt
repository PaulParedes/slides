package noria.layout

import noria.Frame
import noria.Thunk
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect

class Gap(val width: Float, val height: Float) : RenderObject {
  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    return Measure(clamp(width, cs.minWidth, cs.maxWidth), clamp(height, cs.minHeight, cs.maxHeight), null)
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    return RenderResult.NIL
  }

  override fun displayNameImpl(): String = "Gap[${decimalFormat.format(width)}x${decimalFormat.format(height)}]"
}
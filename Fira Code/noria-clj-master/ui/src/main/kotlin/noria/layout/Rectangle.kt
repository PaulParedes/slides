package noria.layout

import noria.*
import noria.scene.Color
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect

class Rectangle(val color : Color) : RenderObject {
  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    return Measure(cs.maxWidth, cs.maxHeight)
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with (frame){
      val scene = scene()
      val rectId = generateId()
      val stackId = generateId()
      scene.rect(rectId, Point(0f, 0f), measure.asSize(), color)
      scene.stack(stackId, longArrayOf(rectId))
      return RenderResult(stackId)
    }
  }

  override fun displayName(): String = "Rectangle[$color]"
}
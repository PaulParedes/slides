package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

object Overlay: LayoutAlgorithm<List<TRenderObject>> {
  override fun layout(frame: Frame, cs: Constraints, arg: List<TRenderObject>): Layout =
    with(frame) {
      val positions = ArrayList<LayoutPosition>(arg.count())
      var width = 0f
      var height = 0f
      val childKeys = ChildKeys()
      for (tchild in arg) {
        val measure = scope(childKeys.forChild(tchild)) {
          memo(tchild, cs) {
            read(tchild).measure(currentFrame, cs)
          }
        }
        positions.add(LayoutPosition(tchild, Point(0f, 0f), measure))
        width = max(width, measure.width)
        height = max(height, measure.height)
      }
      Layout(Size(width, height), positions)
    }
  
  override fun displayNameImpl(arg: List<TRenderObject>): String = "Overlay[children: ${arg.size}]"
}

fun overlay(children: List<TRenderObject>, hitHandler: HitHandler? = null): Container<List<TRenderObject>> =
  Container(hitHandler, Overlay, children)

package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

typealias FlexChild = Pair<TRenderObject, Dimension>
data class FlexArgument(val children: List<FlexChild>)

object HBox: LayoutAlgorithm<FlexArgument> {
  override fun layout(frame: Frame, cs: Constraints, arg: FlexArgument): Layout {
    with(frame) {
      var taken = 0f
      var totalStretch = 0f
      val measuredChildren = ArrayList<Triple<TRenderObject, Dimension, Measure?>>(arg.children.count())
      val childKeys = ChildKeys()

      // all dimensions -> pixels and stretch
      for ((tchild, dim) in arg.children) {
        scope (childKeys.forChild(tchild)) {
          when (dim.type) {
            DimensionType.PX -> {
              val pixels = dim.value
              val childCs = Constraints(pixels, pixels, cs.minHeight, cs.maxHeight)
              val measure = memo(childCs) { read(tchild).measure(currentFrame, childCs) }
              measuredChildren.add(Triple(tchild, dim, measure))
              taken += pixels
            }
            DimensionType.PERCENT -> {
              val pixels = dim.value * cs.maxWidth
              val childCs = Constraints(pixels, pixels, cs.minHeight, cs.maxHeight)
              val measure = memo(childCs) {
                read(tchild).measure(currentFrame, childCs)
              }
              measuredChildren.add(Triple(tchild, Dimension.px(pixels), measure))
              taken += pixels
            }
            DimensionType.STRETCH -> {
              measuredChildren.add(Triple(tchild, dim, null))
              totalStretch += dim.value
            }
            DimensionType.HUG -> {
              val childCs = Constraints(0f, cs.maxWidth - taken, cs.minHeight, cs.maxHeight)
              val measure = memo(childCs) {
                read(tchild).measure(currentFrame, childCs)
              }
              val pixels = measure.width
              measuredChildren.add(Triple(tchild, Dimension.px(pixels), measure))
              taken += pixels
            }
          }
        }
      }

      val free = max(0f, cs.minWidth - taken)

      // pixels and stretch -> pixels
      val measuredChildrenAll = measuredChildren.map { (tchild, dim, measure) ->
        when (dim.type) {
          DimensionType.STRETCH -> {
            val pixels = dim.value / totalStretch * free
            val childCs = Constraints(pixels, pixels, cs.minHeight, cs.maxHeight)
            val stretchMeasure = scope(childKeys.forChild(tchild)) {
              memo(childCs) {
                read(tchild).measure(currentFrame, childCs)
              }
            }
            Triple(tchild, pixels, stretchMeasure)
          }
          DimensionType.PX -> Triple(tchild, dim.value, measure!!)
          else -> throw IllegalStateException("Unreachable")
        }
      }

      val maxHeight = measuredChildrenAll.map { it.third.height }.max() ?: 0f

      val positions = ArrayList<LayoutPosition>(arg.children.count())
      var offset = 0f
      for ((tchild, pixels, measure) in measuredChildrenAll) {
//        val childCs = Constraints.tight(pixels, maxHeight)
//        val secondMeasure = if (measure.height == maxHeight)
//                              measure
//                            else
//                              scope(tchild to 1) {
//                                memo(childCs) {
//                                  read(tchild).measure(this, childCs)
//                                }
//                              }
//        positions.add(LayoutPosition(tchild, Point(offset, 0f), secondMeasure))
        positions.add(LayoutPosition(tchild, Point(offset, 0f), measure))
        offset += pixels
      }
      
      return Layout(Size(offset, maxHeight), positions)
    }
  }

  override fun displayNameImpl(arg: FlexArgument): String = "HBox[children: ${arg.children.size}]"
}

fun hbox(children: List<FlexChild>, hitHandler: HitHandler? = null): Container<FlexArgument> =
  Container(hitHandler, HBox, FlexArgument(children))

fun row(children: List<TRenderObject>, hitHandler: HitHandler? = null): Container<FlexArgument> =
  Container(hitHandler, HBox, FlexArgument(children.map { it to Dimension.hug }))

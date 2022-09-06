package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

object VBox: LayoutAlgorithm<FlexArgument> {
  override fun layout(frame: Frame, cs: Constraints, arg: FlexArgument): Layout {
    with(frame) {
      var taken = 0f
      var totalStretch = 0f
      val measuredChildren = ArrayList<Triple<TRenderObject, Dimension, Measure?>>(arg.children.count())
      val childKeys = ChildKeys()

      // all dimensions -> pixels and stretch
      for ((tchild, dim) in arg.children) {
        when (dim.type) {
          DimensionType.PX -> {
            val pixels = dim.value
            val childCs = Constraints(cs.minWidth, cs.maxWidth, pixels, pixels)
            val measure = scope(childKeys.forChild(tchild)) {
              memo(childCs) {
                read(tchild).measure(this, childCs)
              }
            }
            measuredChildren.add(Triple(tchild, dim, measure))
            taken += pixels
          }
          DimensionType.PERCENT -> {
            val pixels = dim.value * cs.maxHeight
            val childCs = Constraints(cs.minWidth, cs.maxWidth, pixels, pixels)
            val measure = scope(childKeys.forChild(tchild)) {
              memo(childCs) {
                read(tchild).measure(currentFrame, childCs)
              }
            }
            measuredChildren.add(Triple(tchild, Dimension.px(pixels), measure))
            taken += pixels
          }
          DimensionType.STRETCH -> {
            measuredChildren.add(Triple(tchild, dim, null))
            totalStretch += dim.value
          }
          DimensionType.HUG -> {
            val childCs = Constraints(cs.minWidth, cs.maxWidth, 0f, cs.maxHeight - taken)
            val measure = scope(childKeys.forChild(tchild)) {
              memo(childCs) {
                read(tchild).measure(currentFrame, childCs)
              }
            }
            val pixels = measure.height
            measuredChildren.add(Triple(tchild, Dimension.px(pixels), measure))
            taken += pixels
          }
        }
      }

      val free = max(0f, cs.minHeight - taken)

      // pixels and stretch -> pixels
      val measuredChildrenAll = measuredChildren.map { (tchild, dim, measure) ->
        when (dim.type) {
          DimensionType.STRETCH -> {
            val pixels = dim.value / totalStretch * free
            val childCs = Constraints(cs.minWidth, cs.maxWidth, pixels, pixels)
            val stretchMeasure = scope(childKeys.forChild(tchild)) {
              memo(childCs) {
                read(tchild).measure(this, childCs)
              }
            }
            Triple(tchild, pixels, stretchMeasure)
          }
          DimensionType.PX -> Triple(tchild, dim.value, measure!!)
          else -> throw IllegalStateException("Unreachable")
        }
      }

      val maxWidth = measuredChildrenAll.map { it.third.width }.max() ?: 0f

      val positions = ArrayList<LayoutPosition>(arg.children.count())
      var offset = 0f
      for ((tchild, pixels, measure) in measuredChildrenAll) {
//        val childCs = Constraints.tight(maxWidth, pixels)
//        val secondMeasure = if (measure.width == maxWidth)
//                              measure
//                            else
//                              scope(tchild to 1) {
//                                memo(childCs) {
//                                  read(tchild).measure(this, childCs)
//                                }
//                              }
//        positions.add(LayoutPosition(tchild, Point(0f, offset), secondMeasure))
        positions.add(LayoutPosition(tchild, Point(0f, offset), measure))
        offset += pixels
      }

      return Layout(Size(maxWidth, offset), positions)
    }
  }

  override fun displayNameImpl(arg: FlexArgument): String = "VBox[children: ${arg.children.size}]"
}

fun vbox(children: List<FlexChild>, hitHandler: HitHandler? = null): Container<FlexArgument> =
  Container(hitHandler, VBox, FlexArgument(children))

fun column(children: List<TRenderObject>, hitHandler: HitHandler? = null): Container<FlexArgument> =
  Container(hitHandler, VBox, FlexArgument(children.map { it to Dimension.hug }))

package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

data class PaddingArgument(val tChild: TRenderObject,
                           val top: Dimension,
                           val right: Dimension,
                           val bottom: Dimension,
                           val left: Dimension)

object Padding: LayoutAlgorithm<PaddingArgument> {
  override fun layout(frame: Frame, cs: Constraints, arg: PaddingArgument): Layout =
      with(frame) {
        // TODO handle Stretch correctly
        val top = arg.top.convert(cs.maxHeight)
        val right = arg.right.convert(cs.maxWidth)
        val bottom = arg.bottom.convert(cs.maxHeight)
        val left = arg.left.convert(cs.maxWidth)

        val tChild = arg.tChild
        val childCs = Constraints(max(cs.minWidth - left - right, 0f),
                                  max(cs.maxWidth - left - right, 0f),
                                  max(cs.minHeight - top - bottom, 0f),
                                  max(cs.maxHeight - top - bottom, 0f))

        val childMeasure = read(tChild).measure(currentFrame, childCs)

        Layout(Size(childMeasure.width + left + right,
                    childMeasure.height + top + bottom),
               listOf(LayoutPosition(tChild, Point(left, top), childMeasure)))
      }

  override fun displayNameImpl(arg: PaddingArgument): String = "Padding[${arg.top} ${arg.right} ${arg.bottom} ${arg.left}]"
}

fun padding(tChild: TRenderObject,
            top: Float = 0f,
            right: Float = 0f,
            bottom: Float = 0f,
            left: Float = 0f,
            hitHandler: HitHandler? = null): Container<PaddingArgument> =
    Container(hitHandler, Padding, PaddingArgument(tChild, Dimension.px(top), Dimension.px(right), Dimension.px(bottom), Dimension.px(left)))

fun paddingDimensions(tChild: TRenderObject,
                      top: Dimension = Dimension.zero,
                      right: Dimension = Dimension.zero,
                      bottom: Dimension = Dimension.zero,
                      left: Dimension = Dimension.zero,
                      hitHandler: HitHandler? = null): Container<PaddingArgument> =
    Container(hitHandler, Padding, PaddingArgument(tChild, top, right, bottom, left))

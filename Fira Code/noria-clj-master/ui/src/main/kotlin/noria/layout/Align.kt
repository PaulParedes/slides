package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

data class AlignArgument(val tChild: TRenderObject,
                         val alignX: Float,
                         val alignY: Float,
                         val childAlignX: Float,
                         val childAlignY: Float)

object Align: LayoutAlgorithm<AlignArgument> {
  override fun layout(frame: Frame, cs: Constraints, arg: AlignArgument): Layout =
      with(frame) {
        val tChild = arg.tChild
        val childCs = Constraints(0f, cs.maxWidth, 0f, cs.maxHeight)
        val childMeasure = memo(tChild, childCs) { read(tChild).measure(currentFrame, childCs) }
        val offsetX = if (childMeasure.width < cs.minWidth)
                        cs.minWidth * arg.alignX - childMeasure.width * arg.childAlignX
                      else 0f
        val offsetY = if (childMeasure.height < cs.minHeight)
                        cs.minHeight * arg.alignY - childMeasure.height * arg.childAlignY
                      else 0f

        Layout(Size(max(cs.minWidth, childMeasure.width),
                    max(cs.minHeight, childMeasure.height)),
               listOf(LayoutPosition(tChild, Point(offsetX, offsetY), childMeasure)))
      }

      override fun displayNameImpl(arg: AlignArgument): String = "Align[${decimalFormat.format(arg.alignX)} ${decimalFormat.format(arg.alignY)}]"
    }

fun align(tChild: TRenderObject,
          alignX: Float = 0f,
          alignY: Float = 0f,
          childAlignX: Float = alignX,
          childAlignY: Float = alignY,
          hitHandler: HitHandler? = null): Container<AlignArgument> =
    Container(hitHandler, Align, AlignArgument(tChild, alignX, alignY, childAlignX, childAlignY))

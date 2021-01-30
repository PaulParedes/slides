package noria.layout

import noria.*
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Rect
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

data class RenderResult(val node: NodeId? = null,
                        val overlays: List<NodeId> = listOf()){
    companion object{
        @JvmField val NIL : RenderResult = RenderResult()
  }
}

data class ScrollCommand(val pos: Point, val onFinished: (()->Unit) = {})

val decimalFormat: DecimalFormat = {
  val dfs = DecimalFormatSymbols.getInstance()
  dfs.setDecimalSeparator('.')
  DecimalFormat("#.##", dfs)
}()

interface RenderObject {
  fun measure(frame: Frame, cs: Constraints): Measure =
      if ("DEBUG" == LOG_LEVEL) {
        val t0 = System.nanoTime()
        val res = measureImpl(frame, cs)
        val dt = (System.nanoTime() - t0) / 1000000.0
        println("${decimalFormat.format(dt)}ms\t${indent(frame)}measure ${displayName()}")
        res
      } else
        measureImpl(frame, cs)
  fun measureImpl(frame: Frame, cs: Constraints): Measure

  fun makeVisible(frame: Frame, measure: Measure): ScrollCommand? =
      if ("DEBUG" == LOG_LEVEL) {
        val t0 = System.nanoTime()
        val res = makeVisibleImpl(frame, measure)
        val dt = (System.nanoTime() - t0) / 1000000.0
        println("${decimalFormat.format(dt)}ms\t${indent(frame)}makeVisible ${displayName()}")
        res
      } else
        makeVisibleImpl(frame, measure)
  fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? = null

  fun render(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
      if ("DEBUG" == LOG_LEVEL) {
        val t0 = System.nanoTime()
        val res = renderImpl(frame, measure, tViewport, tWindowOffset)
        val dt = (System.nanoTime() - t0) / 1000000.0
        println("${decimalFormat.format(dt)}ms\t${indent(frame)}render ${displayName()}")
        res
      } else
        renderImpl(frame, measure, tViewport, tWindowOffset)
  fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult

  fun displayName() = displayNameImpl() + " #" + System.identityHashCode(this).toString(16)
  fun displayNameImpl() = javaClass.getSimpleName()
}

private fun depth(frame: Frame): Int = frame.depth
private fun indent(frame: Frame): String = "  ".repeat(depth(frame))

inline fun <T> nesting(frame: Frame, crossinline expr: () -> T): T {
  increaseDepth(frame)
  val res = expr()
  decreaseDepth(frame)

  return res
}

fun decreaseDepth(frame: Frame) {
  frame.depth--
}

fun increaseDepth(frame: Frame) {
  frame.depth++
}

typealias TRenderObject = Thunk<out RenderObject>

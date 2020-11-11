package noria.layout

import noria.*
import noria.scene.*
import kotlin.math.*

data class GridCell(val tChild: TRenderObject)

data class GridArgument(val cells: List<GridCell>,
                        val columns: Int,
                        val columnGap: Float,
                        val rowGap: Float,
                        val columnWidths: List<Dimension>,
                        val rowHeights: List<Dimension>)

object Grid: LayoutAlgorithm<GridArgument> {
  override fun layout(frame: Frame, cs: Constraints, arg: GridArgument): Layout {
    with(frame) {
      val columnsPx = ArrayList<Float>()
      val rowsPx = ArrayList<Float>()
      var col = 0
      var row = 0
      for (cell in arg.cells) {
        val colW = arg.columnWidths.get(col % arg.columnWidths.count())
        val rowH = arg.rowHeights.get(row % arg.rowHeights.count())
        val (minW, maxW) = when (colW.type) {
          DimensionType.PX -> Pair(colW.value, colW.value)
          DimensionType.PERCENT -> Pair(colW.value * cs.maxWidth, colW.value * cs.maxWidth)
          DimensionType.STRETCH -> TODO()
          DimensionType.HUG -> Pair(cs.minWidth, cs.maxWidth)
        }
        val (minH, maxH) = when (rowH.type) {
          DimensionType.PX -> Pair(rowH.value, rowH.value)
          DimensionType.PERCENT -> Pair(rowH.value * cs.maxHeight, rowH.value * cs.maxHeight)
          DimensionType.STRETCH -> TODO()
          DimensionType.HUG -> Pair(cs.minHeight, cs.maxHeight)
        }
        val tChild = cell.tChild
        val childCs = Constraints(minW, maxW, minH, maxH)
        val measure = scope(Pair(tChild, 0)) {
          memo(childCs) { read(tChild).measure(currentFrame, childCs) }
        }

        if (col >= columnsPx.count())
          columnsPx.add(measure.width)
        else
          columnsPx.set(col, max(columnsPx.get(col), measure.width))

        if (row >= rowsPx.count())
          rowsPx.add(measure.height)
        else
          rowsPx.set(row, max(rowsPx.get(row), measure.height))

        ++col
        if (col >= arg.columns) {
          ++row
          col = 0
        }
      }

      var offsetX = 0f
      var offsetY = 0f
      val positions = ArrayList<LayoutPosition>(arg.cells.count())
      col = 0
      row = 0
      for (cell in arg.cells) {
        val columnW = columnsPx.get(col)
        val rowH = rowsPx.get(row)
        val tChild = cell.tChild
        val childCs = Constraints.tight(columnW, rowH)
        val measure = scope(Pair(tChild, 1)) {
          memo(childCs) {
            read(tChild).measure(currentFrame, childCs)
          }
        }
        positions.add(LayoutPosition(cell.tChild, Point(offsetX, offsetY), measure))

        ++col
        offsetX += columnW + arg.columnGap
        if (col >= arg.columns) {
          ++row
          offsetY += rowH + arg.rowGap
          col = 0
          offsetX = 0f
        }
      }
      val width = columnsPx.sum() + arg.columnGap * (columnsPx.count() - 1)
      val height = rowsPx.sum() + arg.rowGap * (rowsPx.count() - 1)
      return Layout(Size(width, height), positions)
    }
  }

  override fun displayNameImpl(arg: GridArgument): String = "Grid[cells: ${arg.cells.size} columns: ${arg.columns}]"
}

fun grid(cells: List<GridCell>,
         columns: Int = Integer.MAX_VALUE,
         columnGap: Float = 0f,
         rowGap: Float = 0f,
         columnWidths: List<Dimension> = listOf(Dimension.hug),
         rowHeights: List<Dimension> = listOf(Dimension.hug),
         hitHandler: HitHandler? = null): Container<GridArgument> =
    Container(hitHandler, Grid, GridArgument(cells, columns, columnGap, rowGap, columnWidths, rowHeights))
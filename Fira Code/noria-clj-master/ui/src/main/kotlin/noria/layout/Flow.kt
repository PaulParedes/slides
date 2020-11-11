package noria.layout

import noria.*
import noria.scene.*

object Flow : LayoutAlgorithm<List<TRenderObject>> {
    override fun layout(frame: Frame, cs: Constraints, arg: List<TRenderObject>): Layout =
        with (frame) {
            var offsetX = 0f
            var offsetY = 0f
            var rowHeight = 0f
            val positions = ArrayList<LayoutPosition>(arg.count())
            val childKeys = ChildKeys()
            for (tchild in arg) {
                val measure = scope(childKeys.forChild(tchild)) {
                  memo {
                    read(tchild).measure(currentFrame, Constraints.INFINITE)
                  }
                }
                if (offsetX + measure.width > cs.maxWidth) {
                    offsetX = 0f
                    offsetY += rowHeight
                    rowHeight = 0f
                }
                positions.add(LayoutPosition(tchild, Point(offsetX, offsetY), measure))
                rowHeight = Math.max(rowHeight, measure.height)
                offsetX += measure.width
            }
            Layout(Size(offsetX, offsetY + rowHeight), positions)
        }
}

fun flow(children: List<TRenderObject>, hitHandler: HitHandler? = null): Container<List<TRenderObject>> =
    Container(hitHandler, Flow, children)

@file:Suppress("USELESS_CAST")

package noria.layout

import noria.*
import noria.scene.*

typealias NodeId = Long
typealias ThunkId = Long

val SceneKey = Attr<Scene>("noria.scene")
val WindowSizeKey = Attr<Thunk<Size>>("noria.windowSize")

fun Frame.scene(): Scene = SceneKey[this.bindings]

val LOG_LEVEL: String = System.getProperty("noria.log", "INFO")

fun renderRoot(frame: Frame, size: Size, tContent: TRenderObject) {
    with (frame) {
        val rootResult = implicitMemo(bindings = mapOf(WindowSizeKey to expr(size) { size })) {
            val content = read(tContent)
            val measure = content.measure(currentFrame, Constraints.tight(size))
            content.makeVisible(currentFrame, measure)
            val res = content.render(currentFrame, measure, expr(size) { Rect(0f, 0f, size.width, size.height) }, expr { Point.ZERO })
            res
        }
        val scene = scene()
        scene.setSceneSize(size)
        scene.stack(0, 
            ((if (rootResult.node != null) listOf(rootResult.node) else listOf()) + rootResult.overlays).toLongArray()
        )
        scene.setRoot(0)
    }
}

class Nil: RenderObject {
    override fun measureImpl(frame: Frame, cs: Constraints): Measure = Measure(0f, 0f)
    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
        RenderResult()
}

val tNil = ValueThunk(Nil())
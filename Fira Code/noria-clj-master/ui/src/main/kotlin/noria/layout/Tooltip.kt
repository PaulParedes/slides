package noria.layout

import noria.*
import noria.scene.*
import java.lang.Float.max
import java.lang.Float.min

data class TooltipOptions(val margin: Rect? = null,
                          val padding: Rect? = null,
                          val font: FontSpec? = null,
                          val bgColor: Color? = null,
                          val textColor: Color? = null,
                          val borderColor: Color? = null,
                          val maxWidth: Float? = null,
                          val wrap: PhotonApi.Wrap? = null) {
    companion object {
        val constructorParameterNames = listOf("margin", "padding", "font", "bgColor", "textColor", "borderColor", "maxWidth", "wrap")
    }

    fun merge(opts: TooltipOptions?) =
            if (opts == null)
                this
            else
                TooltipOptions(
                        margin = opts.margin ?: margin,
                        padding = opts.padding ?: padding,
                        font = opts.font ?: font,
                        bgColor = opts.bgColor ?: bgColor,
                        textColor = opts.textColor ?: textColor,
                        borderColor = opts.borderColor ?: borderColor,
                        maxWidth = opts.maxWidth ?: maxWidth,
                        wrap = opts.wrap ?: wrap)
}

private val defaultOpts = TooltipOptions(
  margin = Rect(5f, 5f, 5f, 5f),
  padding = Rect(5f, 5f, 5f, 5f),
  font = fontSystemRegular.withSize(11),
  bgColor = Color.css("f2f2f2"),
  textColor = Color.black,
  borderColor = Color.css("c5c5c5"),
  maxWidth = 200f,
  wrap = PhotonApi.Wrap.WORDS)

data class Tooltip(val text: String,
                   val tChild: TRenderObject,
                   val opts: TooltipOptions = defaultOpts): RenderObject {
    private val pointerSize = Size(12f, 6f)
    
    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        with (frame) {
            return read(tChild).measure(frame, cs)
        }
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
        with(frame) {
            val viewport = read(tViewport)
            val childBox = Rect(0f, 0f, measure.width, measure.height)
            val visible = childBox.intersect(viewport)
            
            val renderResult = memo(tChild, measure, tViewport, tWindowOffset) {
                read(tChild).render(currentFrame, measure, tViewport, tWindowOffset)
            }
            
            if (visible != null) {
                val fontInstance = FontManager.resolve(opts.font)
                val stackId = generateId()
                val bgId = generateId()
                
                val pointerImageUpId = generateId()
                val pointerImageDownId = generateId()
                val pointerId = generateId()
                val pointerStackId = generateId()
                
                val textId = generateId()
                val borderId = generateId()
                val scene = scene()
                val padding = opts.padding!!
                val margin = opts.margin!!
                val layout = memo(fontInstance, text, opts) {
                    PhotonApi.layoutText(fontInstance, opts.maxWidth!!, text, opts.wrap)
                }
                val innerSize = Size(max(layout.size().width, pointerSize.width), layout.size().height)
                val outerSize = innerSize.add(padding.left + padding.right, padding.top + padding.bottom)

                val windowOffset = read(tWindowOffset)
                val windowSize = read(WindowSizeKey[this.bindings])

                val position = when {
                    windowOffset.y + visible.bottom + margin.bottom + outerSize.height + margin.bottom <= windowSize.height -> "below"
                    else -> "above"
                }
                var left = windowOffset.x + (childBox.left + childBox.right - outerSize.width) / 2f
                left = min(left, windowSize.width - outerSize.width - margin.right)
                left = max(left, margin.left)
                var top = when (position) {
                    "below" -> windowOffset.y + visible.bottom + margin.bottom
                    else -> windowOffset.y - margin.top - outerSize.height
                }
                top = min(top, windowSize.height - outerSize.height - margin.bottom)
                top = max(top, margin.top)
                
                // bg
                scene.rect(bgId, Point(1f, 1f), outerSize.add(-2f, -2f), opts.bgColor)
                
                // text
                scene.text(textId,
                           Point(padding.left + (innerSize.width - layout.size().width) / 2f, padding.top), innerSize, opts.textColor, layout)
                
                // border
                scene.border(borderId, Point(0f, 0f), outerSize, opts.borderColor, 1f, Scene.BorderStyle.SOLID, BorderRadius.uniform(2f))

                // pointer
                var pointerLeft = windowOffset.x + measure.width / 2f - left - pointerSize.width / 2f // place at center of childBox
                // limit by tooltip boundaries
                pointerLeft = max(pointerLeft, padding.left)
                pointerLeft = min(pointerLeft, outerSize.width - pointerSize.width - padding.right)
                when (position) {
                    "below" -> {
                        memo(pointerImageUpId) {
                            PhotonApi.loadImageResource(pointerImageUpId, "noria/resources/tooltip_pointer_up.png")
                        }
                        scene.image(pointerId, Point(0f, 0f), pointerSize, pointerImageUpId)
                        scene.stack(pointerStackId, longArrayOf(pointerId))
                        val pointerTop = -pointerSize.height+1f
                        scene.setPosition(pointerStackId, Point(pointerLeft, pointerTop))
                    }
                    else -> {
                        memo(pointerImageDownId) {
                            PhotonApi.loadImageResource(pointerImageDownId, "noria/resources/tooltip_pointer_down.png")
                        }
                        scene.image(pointerId, Point(0f, 0f), pointerSize, pointerImageDownId)
                        scene.stack(pointerStackId, longArrayOf(pointerId))
                        val pointerTop = outerSize.height-1f
                        scene.setPosition(pointerStackId, Point(pointerLeft, pointerTop))
                    }
                }
                
                // stack
                scene.stack(stackId, longArrayOf(bgId, textId, borderId, pointerStackId))
                scene.setPosition(stackId, Point(left, top))
                
//                val debugStackId = generateId()
//                val debugBorderId = generateId()
//                scene.border(debugBorderId, childBox.intersect(viewport).origin().offset(windowOffset), childBox.intersect(viewport).size(), Color.css("cc333399"), 1f, Scene.BorderStyle.SOLID, 0f)
//                scene.stack(debugStackId, longArrayOf(debugBorderId))

                return RenderResult(renderResult.node, renderResult.overlays + stackId)
            } else
                return renderResult
        }
    }
}
package noria.examples.widgets

import noria.*
import noria.layout.*
import noria.layout.Path
import noria.scene.*
import noria.scene.UserEvent.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow

class State(private val values: ConcurrentMap<String, Any?>,
            private val readers: ConcurrentMap<String, MutableSet<Long>> = ConcurrentHashMap()) {
    var onWrite: (Set<Long>) -> Unit = {}

    @Synchronized
    fun read(frame: Frame?, key: String): Any? {
        frame?.run {
            val set = readers.getOrDefault(key, mutableSetOf())
            set.add(frame.id)
            readers.put(key, set)
        }
        return values.get(key)
    }

    @Synchronized
    fun write(key: String, value: Any?) {
        values.put(key, value)
        readers.get(key)?.run {
            readers.remove(key)
            onWrite(this)
        }
    }
}

val global: State = State(ConcurrentHashMap(mapOf("screenSize" to Size(1200f, 700f),
                                                "vsync" to false,
                                                "profiler" to false,
                                                "scroll" to "")))

const val defaultWindowId = 42L

data class ColorRect(val color: Color,
                     val title: String? = null,
                     val width: Dimension? = null,
                     val height: Dimension? = null,
                     val tOnEvents: HitHandler? = null,
                     val globalHandlers: Iterable<GlobalHandler>? = null) : RenderObject {

    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        return Measure(width?.convert(cs.maxWidth) ?: cs.maxWidth,
                       height?.convert(cs.maxHeight) ?: cs.maxHeight)
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
            with(frame) {
                val stackId = generateId()
                val contentId = generateId()
                val scene = scene()

                scene.rect(contentId, Point.ZERO, measure.asSize(), color)
                if (tOnEvents != null)
                    scene.onEvent(contentId, tOnEvents.tCallback.thunkId, tOnEvents.mask)
                if (globalHandlers != null)
                    for (h in globalHandlers)
                        this.child(h.eventType, GlobalHandlerReconciler, h)
                scene.stack(stackId, longArrayOf(contentId))
                
                RenderResult(stackId)
            }
}

data class MaskChanger(val color: Color, val colorChecked: Color, val width: Float, val height: Float): RenderObject {
    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        return Measure(width, height)
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
        with(frame) {
                val stackId = generateId()
                val rectId = generateId()
                val scene = scene()
                val tChecked = state { false }
                val checked = read(tChecked)

                val currentColor = if (checked) colorChecked else color
                scene.rect(rectId, Point.ZERO, measure.asSize(), currentColor)
                val mask = if (checked)
                    listOf(EventType.MOUSE_INPUT, EventType.CURSOR_MOVED)
                else
                    listOf(EventType.MOUSE_INPUT)
                subscribeHit(scene, rectId, mask) { e, _ ->
                    println("Event ${e.type()}")
                    if (e is MouseInput && e.button == MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED)
                        tChecked.update { !it }
                    Propagate.CONTINUE
                }
            
                scene.stack(stackId, longArrayOf(rectId))
                
                RenderResult(stackId)
        }
}

fun cubicBezier(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    return p0 * (1.0f - t).pow(3) +
            p1 * 3.0f * (1.0f - t).pow(2) * t +
            p2 * 3.0f * (1.0f - t) * t.pow(2) +
            p3 * t.pow(3)
}

data class VSyncSquare(val width: Float, val height: Float) : RenderObject {
    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        return Measure(width, height)
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
        with(frame) {
            val tFrameNumber = state { 0 }
            val stackId = generateId()
            val rectIds = listOf(generateId(), generateId(), generateId(), generateId())
            val scene = scene()

            scene.rect(rectIds[0], Point(0f, 0f), Size(width, height),
                       Color(0.875f, 0.875f, 0.875f, 1f))

            implicitExpr {
                val color = if (0 == read(tFrameNumber).rem(2))
                  Color(1f, 0.5f, 0.5f, 1f)
                else
                  Color(0.5f, 1f, 1f, 1f)

                val scene = scene()
                scene.rect(rectIds[1], Point(width / 7f, height / 3f),
                           Size(width / 7f, height / 3f), color)
                scene.rect(rectIds[2], Point(width * 3f / 7f, height / 3f),
                           Size(width / 7f, height / 3f), color)
                scene.rect(rectIds[3], Point(width * 5f / 7f, height / 3f),
                           Size(width / 7f, height / 3f), color)
            }

            val tEventHandler = implicitExpr {{ e: UserEvent, hit: NodeHit ->
                e as MouseInput
                if (e.button == MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED) {
                    global.write("vsync", !(global.read(null, "vsync") as Boolean))
                    Propagate.STOP
                } else
                    Propagate.CONTINUE
            }}
            scene.onEvent(rectIds[0], tEventHandler.thunkId, listOf(EventType.MOUSE_INPUT))

            implicitExpr {
                if (global.read(currentFrame, "vsync") as Boolean) {
                    subscribeGlobal(EventType.NEW_FRAME) { tFrameNumber.update(Int::inc) }
                }
            }

            scene.stack(stackId, rectIds.toLongArray())
            return RenderResult(stackId)
        }
}

class ClipExample : RenderObject {
    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        return Measure(300.0f, 60.0f)
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
        with(frame) {
            val scene = scene()
            val stackId = generateId()
            val rectId = generateId()
            val boxShadowId = generateId()
            val clipId = generateId()
            val size = measure.asSize()
            scene.rect(rectId, Point(.0f, .0f), size,
                       Color(0.1f, 0.8f, 0.5f, 1.0f))
            scene.clip(clipId,
                       Rect(Point(.0f, .0f), size),
                       listOf(Scene.ComplexClipRegion(
                         Rect(Point(.0f, .0f), size),
                         BorderRadius.uniform(20.0f),
                         Scene.ClipMode.CLIP),
                           Scene.ComplexClipRegion(
                             Rect(Point(10.0f, 10.0f), Size(20.0f, 20.0f)),
                             BorderRadius.uniform(10.0f),
                             Scene.ClipMode.CLIP_OUT)),
                       rectId)
            scene.boxShadow(boxShadowId,
                            Rect(Point(.0f, .0f), size),
                            Rect(Point(10.0f, 10.0f), Size(20.0f, 20.0f)),
                            Vector2D(0.6f, 0.6f),
                            Color(0.2f, 0.2f, 0.2f, 0.7f),
                            0.5f,
                            0.6f,
                            BorderRadius.uniform(10.0f),
                            Scene.BoxShadowClipMode.INSET)
            scene.stack(stackId, longArrayOf(clipId, boxShadowId))
            return RenderResult(stackId)
        }
    }
}

data class BlinkingSquare(val color: Color,
                          val name: String? = null,
                          val width: Dimension? = null,
                          val height: Dimension? = null) : RenderObject {

    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        return Measure(width?.convert(cs.maxWidth) ?: cs.maxWidth,
                height?.convert(cs.maxHeight) ?: cs.maxHeight)
    }

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult =
            with(frame) {
                val tFrameNumber = state { 0 }
                val tAnimationActive = state { false }
                val stackId = generateId()
                val contentId = generateId()
                val scene = scene()
                scene.rect(contentId, Point(0f, 0f),
                           Size(measure.width, measure.height), color)

                subscribeHit(scene, contentId, listOf(EventType.MOUSE_INPUT)) { e, _ ->
                    e as MouseInput
                    if (e.button == MouseButton.LEFT && e.state == UserEvent.ButtonState.PRESSED) {
                        tAnimationActive.update(Boolean::not)
                        Propagate.STOP
                    } else
                        Propagate.CONTINUE
                }

                implicitExpr {
                    val frameNumber = read(tFrameNumber)
                    val time = frameNumber * (1.0f / 60.0f)
                    val opacity = min(1.0f, cubicBezier(0.68f, -0.55f, 0.27f, 1.55f, time - floor(time)))
                    scene().setOpacity(stackId, opacity)
                }

                implicitExpr {
                    if (read(tAnimationActive)) {
                        subscribeGlobal(EventType.NEW_FRAME) { tFrameNumber.update(Int::inc) }
                    }
                }

                scene.stack(stackId, longArrayOf(contentId))
                return RenderResult(stackId)
            }
}

data class Image(val path: String,
                 val width: Dimension? = null,
                 val height: Dimension? = null,
                 val scrollCommand: ScrollCommand? = null): RenderObject {
    override fun measureImpl(frame: Frame, cs: Constraints): Measure {
        return Measure(
                width?.convert(cs.maxWidth) ?: cs.maxWidth,
                height?.convert(cs.maxHeight) ?: cs.maxHeight)
    }

    override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? = scrollCommand

    override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
        with(frame) {
            val imageId = generateId()
            val path = path
            implicitMemo {
                PhotonApi.loadImageResource(imageId, path)
            }
            val imageNodeId = generateId()
            val stackingContextId = generateId()
            scene().image(imageNodeId, Point(.0f, .0f), measure.asSize(), imageId)
            scene().stack(stackingContextId, longArrayOf(imageNodeId))
            return RenderResult(stackingContextId)
        }
    }
}

fun Frame.app(): RenderObject {
    val vscroll1 = implicitExpr {
        scroll(implicitExpr {
            column((1..2000).map {
                val shade = 0.4f + (it % 20) / 50f
                val width = (2100 - it) / 2100f
                implicitExpr(it) {
                    val rect = implicitExpr {
                        ColorRect(Color(shade, shade, shade, 1f),
                                  width = Dimension.percent(width),
                                  height = Dimension.px(80f))
                    }
                    Tooltip(it.toString(), rect)
                }
            })
        })
    }

    val vscroll2 = implicitExpr {
        scroll(implicitExpr {
            column((1..20).map {
                val shade = 0.4f + it / 50f
                val rect = implicitExpr(it) { ColorRect(Color(shade, shade, shade, 1f), height = Dimension.px(80f)) }
                when (it) {
                    7 -> implicitExpr { Tooltip("Short popup", rect) }
                    9 -> implicitExpr { Tooltip("Hello, this is a tooltip. How has your day been so far?", rect) }
                    else -> rect
                }
            })
        }, initialScrollPosition = Point(0f, 200f))
    }

    val vscroll3 = implicitExpr {
        scroll(implicitExpr {
            column(listOf(
              implicitExpr { ColorRect(Color(219f / 256f, 76f / 256f, 48f / 256f, 1f), height = Dimension.px(50f)) },
              implicitExpr { ColorRect(Color(241f / 256f, 144f / 256f, 53f / 256f, 1f), height = Dimension.px(50f)) },
              implicitExpr { ColorRect(Color(253f / 256f, 255f / 256f, 4f / 256f, 1f), height = Dimension.px(50f)) },
              implicitExpr { ColorRect(Color(0f / 256f, 192f / 256f, 1f / 256f, 1f), height = Dimension.px(50f)) },
              implicitExpr { ColorRect(Color(23f / 256f, 0f / 256f, 192f / 256f, 1f), height = Dimension.px(50f)) },
              implicitExpr { ColorRect(Color(122f / 256f, 0f / 256f, 191f / 256f, 1f), height = Dimension.px(50f)) }
        )) })
    }

    val vscrolls = implicitExpr {
        hbox(listOf(
          vscroll1      to Dimension(DimensionType.STRETCH, 1f),
          implicitExpr { Nil() } to Dimension.px(5f),
          vscroll2      to Dimension(DimensionType.STRETCH, 1f),
          implicitExpr { Nil() } to Dimension.px(5f),
          vscroll3      to Dimension(DimensionType.STRETCH, 1f)))
    }

    val hscroll1: TRenderObject = implicitExpr {
        scroll(implicitExpr {
            row((1..20).map {
                val shade = 0.4f + it / 50f
                implicitExpr(it) { ColorRect(Color(shade, shade, shade, 1f), width = Dimension.px(40f)) }
            })
        }, ScrollOptions(scrollDirection = ScrollDirection.HORIZONTAL))
    }

    val hscroll2: TRenderObject = implicitExpr {
        scroll(implicitExpr {
            row(listOf(
              implicitExpr { ColorRect(Color(219 / 256f, 76 / 256f, 48 / 256f, 1f), width = Dimension.px(40f)) },
              implicitExpr { ColorRect(Color(241 / 256f, 144 / 256f, 53 / 256f, 1f), width = Dimension.px(40f)) },
              implicitExpr { ColorRect(Color(253 / 256f, 255 / 256f, 4 / 256f, 1f), width = Dimension.px(40f)) },
              implicitExpr { ColorRect(Color(0 / 256f, 192 / 256f, 1 / 256f, 1f), width = Dimension.px(40f)) },
              implicitExpr { ColorRect(Color(23 / 256f, 0 / 256f, 192 / 256f, 1f), width = Dimension.px(40f)) },
              implicitExpr { ColorRect(Color(122 / 256f, 0 / 256f, 191 / 256f, 1f), width = Dimension.px(40f)) }
        ))}, ScrollOptions(scrollDirection = ScrollDirection.HORIZONTAL))
    }

    val hscrolls = implicitExpr {
        vbox(listOf(
          hscroll1 to Dimension.stretch(1f),
          implicitExpr { Nil() } to Dimension.px(5f),
          hscroll2 to Dimension.stretch(1f)))
    }

    val hvscroll = implicitExpr {
        scroll(implicitExpr {
            column((0..7).map { y ->
                implicitExpr(y) {
                    row((0..7).map { x ->
                        implicitExpr(x) {
                            val extra = (x + y * 8) / 64f * 0.5f
                            val color = if ((x + y) % 2 == 0) Color(0f, 0f, 0f, extra + 0.1f)
                            else Color(0f, 0f, 0f, extra + 0.3f)
                            ColorRect(color, width = Dimension.px(50f), height = Dimension.px(50f))
                        }
                    })
                }
            })
        }, ScrollOptions(scrollDirection = ScrollDirection.BOTH))
    }

    val left = implicitExpr {
        split(implicitExpr { padding(vscrolls, 5f, 5f, 5f, 5f) },
              implicitExpr {
                    split(implicitExpr { padding(hscrolls, 5f, 5f, 5f, 5f) },
                          implicitExpr { padding(hvscroll, 5f, 5f, 5f, 5f) },
                          initialRatio = 0.33f,
                          opts = SplitOptions(direction = SplitDirection.VERTICAL))
                },
              initialRatio = 0.5f,
              opts = SplitOptions(direction = SplitDirection.VERTICAL))
    }

    val middle = implicitExpr {
        scroll(implicitExpr {
            padding(
              implicitExpr {
                  column(listOf(
                    implicitExpr {
                          Constrainer(
                            implicitExpr {
                                  scroll(
                                    implicitExpr {
                                          val scroll = global.read(this, "scroll") as String
                                          Image(
                                              "pultius.jpg",
                                              width = Dimension.px(200f),
                                              height = Dimension.px(878f),
                                              scrollCommand = if (scroll == "top")
                                                  ScrollCommand(Point(0f, 0f)) {
                                                      println("scroll top finished"); global.write(
                                                      "scroll",
                                                      ""
                                                  )
                                                  }
                                              else null)
                                      }
                                  )
                              }) { Constraints(it.minWidth, it.maxWidth, 300f, 300f) }
                      },
                    implicitExpr {
                          Draggable(implicitExpr {
                              Tooltip(
                                "Drag me",
                                implicitExpr {
                                      ColorRect(
                                        Color.css("EBC735"),
                                        width = Dimension.px(250f),
                                        height = Dimension.px(50f)
                                      )
                                  })
                          })
                      },
                    implicitExpr {
                          Constrainer(
                            implicitExpr {
                                  scroll(
                                    implicitExpr {
                                          val scroll = global.read(this, "scroll") as String
                                          Image(
                                              "long-cat.jpg",
                                              width = Dimension.px(346f),
                                              height = Dimension.px(9904f),
                                              scrollCommand = if (scroll == "middle")
                                                  ScrollCommand(Point(173f,
                                                                      4952f)) { println("scroll middle finished"); global.write("scroll", "") }
                                              else null)
                                      }
                                  )
                              }
                          ) { Constraints(it.minWidth, it.maxWidth, 500f, 500f) }
                      },
                    implicitExpr {
                        Constrainer(
                          implicitExpr {
                                scroll(
                                  implicitExpr {
                                        val scroll = global.read(this, "scroll") as String
                                        Image(
                                            "ash.jpg",
                                            width = Dimension.px(325f),
                                            height = Dimension.px(549f),
                                            scrollCommand = if (scroll == "bottom")
                                                ScrollCommand(Point(0f, 549f)) {
                                                    println("scroll bottom finished"); global.write("scroll", "")
                                                }
                                            else null)
                                    }
                                )
                            }) { Constraints(it.minWidth, it.maxWidth, 300f, 300f) }
                    }
                  ))
              }, 0f, 6f, 0f, 5f)
        })
    }

    val tTextFont = state { FontSpec("New York", "Regular", 18, 400) }

    val textField1 = implicitExpr { textField(TextFieldState(text = "Some text", focused = true)) }
    val textField2 = implicitExpr { textField(opts = TextFieldOptions(placeholderText = "Please enter text here...", borderRadius = 100f, padding = Rect(
      16f, 8f, 16f, 8f))) }

    val scrollTopButton = implicitExpr { buttonStandard("Scroll top", implicitExpr {{ global.write("scroll", "top") }}) }
    val scrollMiddleButton = implicitExpr { buttonStandard("Scroll middle", implicitExpr {{ global.write("scroll", "middle") }}) }
    val scrollBottomButton = implicitExpr { buttonStandard("Scroll bottom", implicitExpr {{ global.write("scroll", "bottom") }}) }

    val boldButton  = implicitExpr {
        buttonPrimary("Bold", implicitExpr {
            { tTextFont.update { if (it.weight == 400) it.withWeight(700) else it.withWeight(400) }}
        })
    }
    val italicButton = implicitExpr {
        buttonAction("Italic", implicitExpr {
            { tTextFont.update { if (it.style == "Regular") it.withStyle("Italic") else it.withStyle("Regular") }}
        })
    }
    val profilerButton = implicitExpr {
        val profiler = global.read(this, key = "profiler") as Boolean
        buttonStandard(if (profiler) "No profiler" else "Profiler",
                       implicitExpr {{
                    global.write("profiler", !profiler)
                    PhotonApi.setDebugProfilerShowing(defaultWindowId, !profiler)
                }})
    }
    val disableButton = implicitExpr {
        val disabled = read(read(textField1).tState).disabled
        buttonStandard(if (disabled) "Enable" else "Disable",
                       implicitExpr {
                    val updateTextField1 = read(read(textField1).tUpdateState)
                    val updateTextField2 = read(read(textField2).tUpdateState)
                    val updateBoldButton = read(read(boldButton).tUpdateState)
                    val updateItalicButton = read(read(italicButton).tUpdateState)
                    val updateProfilerButton = read(read(profilerButton).tUpdateState)
                    val updateScrollTopButton = read(read(scrollTopButton).tUpdateState)
                    val updateScrollMiddleButton = read(read(scrollMiddleButton).tUpdateState)
                    val updateScrollBottomButton = read(read(scrollBottomButton).tUpdateState);
                    {
                        updateTextField1 { it.copy(disabled = !it.disabled) }
                        updateTextField2 { it.copy(disabled = !it.disabled) }
                        updateBoldButton { it.copy(disabled = !it.disabled) }
                        updateItalicButton { it.copy(disabled = !it.disabled) }
                        updateProfilerButton { it.copy(disabled = !it.disabled) }
                        updateScrollTopButton { it.copy(disabled = !it.disabled) }
                        updateScrollMiddleButton { it.copy(disabled = !it.disabled) }
                        updateScrollBottomButton { it.copy(disabled = !it.disabled) }
                    }
                })
    }

  val fileChooser = implicitExpr {
    val selectedPath = state { "No file selected" }
    val button = implicitExpr {
      buttonPrimary("Open file", implicitExpr {
        {
          PhotonApi.openFileDialog(
            "Open file",
            "/"
          ) { path -> selectedPath.update { path ?: "No file selected" } };
        }
      })
    }
    val path = read(selectedPath)
    hbox(listOf(
      implicitExpr { padding(button, 0f, 5f, 0f, 0f) } to Dimension.hug,
      implicitExpr { Path(path, Color(0f, 0f, 0f, 1f), read(tTextFont)) } to Dimension.stretch(1f)
    ))
  }

    val rect1 = implicitExpr { MaskChanger(Color.css("DE2E24"), Color.css("1ACC80"), 200f, 200f) }
    val rect2 = implicitExpr { ColorRect(Color.css("D52C82"), width = Dimension.px(200f), height = Dimension.px(100f)) }
    val rect3 = implicitExpr { ColorRect(Color.css("2787D5"), width = Dimension.px(200f), height = Dimension.px(100f)) }

    val right = implicitExpr {
        column(listOf(
          implicitExpr {
              flow(listOf(
                implicitExpr { padding(rect1, 0f, 5f, 5f, 0f) },
                implicitExpr { padding(implicitExpr { Draggable(implicitExpr {
                    Tooltip(
                      "Drag me, And then just touch me, 'Till I can get my satisfaction, Satisfaction, satisfaction, satisfaction, satisfaction.",
                      rect2)
                })
                }, 0f, 5f, 5f, 0f) },
                implicitExpr { padding(rect3, 0f, 0f, 5f, 0f) }))
          },
          implicitExpr {
              padding(
                implicitExpr { row(listOf(
                  implicitExpr { padding(scrollTopButton, 0f, 5f, 0f, 0f) },
                  implicitExpr { padding(scrollMiddleButton, 0f, 5f, 0f, 0f) },
                  implicitExpr { padding(scrollBottomButton, 0f, 5f, 0f, 0f) },
                  implicitExpr { padding(boldButton, 0f, 5f, 0f, 0f) },
                  implicitExpr { padding(italicButton, 0f, 5f, 0f, 0f) },
                  implicitExpr { padding(profilerButton, 0f, 5f, 0f, 0f) }
                ))}, 0f, 0f, 5f, 0f)
          },
          implicitExpr {
                val longText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n" +
                        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.\n" +
                        "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum."
                padding(implicitExpr { Text(longText, Color(0f, 0f, 0f, 1f), read(tTextFont), PhotonApi.Wrap.WORDS) }, 0f, 0f, 5f, 0f)
            },
          implicitExpr {
                padding(implicitExpr {
                    hbox(listOf(
                      textField1 to Dimension.stretch(1f),
                      implicitExpr { Nil() } to Dimension.px(5f),
                      textField2 to Dimension.stretch(2f),
                      implicitExpr { Nil() } to Dimension.px(5f),
                      disableButton to Dimension.hug))
                }, 0f, 0f, 5f, 0f)
          },
          implicitExpr {
            padding(implicitExpr {
            row(listOf(
              implicitExpr {
                padding(
                  implicitExpr { BlinkingSquare(Color(0f, 0f, 0f, 1f), "BlinkingSquare", Dimension.px(60f), Dimension.px(60f)) },
                  0f, 5f, 0f, 0f)
              },
              implicitExpr {
                    padding(
                      implicitExpr { VSyncSquare(140f, 60f) },
                      0f, 5f, 0f, 0f)
                },
              implicitExpr {
                    padding(
                      implicitExpr { ClipExample() },
                      0f, 5f, 0f, 0f)
                }))
            }, 0f, 0f, 5f, 0f)
          },
          implicitExpr { padding(fileChooser, 0f, 0f, 5f, 0f) },
          implicitExpr {
              row(listOf("icons/css.svg",
                         "icons/java.svg",
                         "icons/javaScript.svg",
                         "icons/json.svg",
                         "icons/text.svg",
                         "icons/xml.svg",
                         "icons/html.svg",
                         "icons/clojure.svg",
                         "icons/clojureFile.svg"
              ).map { iconName ->
                  implicitExpr(iconName) {
                      padding(
                        implicitExpr { Icon(iconName,
                                            Dimension.px(16.0f),
                                            Dimension.px(16.0f)) },
                        0f, 5f, 0f, 0f)
                  }
              })
          }
        ))
    }

    return split(left, implicitExpr { split(middle, implicitExpr { padding(right, 5f, 5f, 5f, 5f) }, initialRatio = 0.3f) }, initialRatio = 0.25f)
}

val frames = DoubleArray(60)
var frameIdx = 0

data class WindowState(val windowId: NodeId,
                       val noriaRef: AtomicReference<Noria<Unit, *>>,
                       val renderThread: ExecutorService)

fun createWindow(windowId: NodeId, size: Size, title: String): WindowState {
    val renderThread = Executors.newScheduledThreadPool(1)
    val dirtySetRef = AtomicReference<DirtySet>(io.lacuna.bifurcan.Map())
    val noriaRef = AtomicReference<Noria<Unit, *>>(null)
    val updateScheduled = AtomicBoolean(false)

    Thread {
        try {
            val scene: Scene = PhotonApi.createWindow(windowId, size, title, false)
            val update = Runnable {
                try {
                    updateScheduled.set(false)
                    val noria = noriaRef.get()
                    if (noria != null) {
                        val dirtySet = dirtySetRef.getAndSet(io.lacuna.bifurcan.Map())
                        if (dirtySet.size() > 0) {
                            val t0 = System.nanoTime()

                            if (global.read(null, key = "vsync") as Boolean)
                                Thread.sleep((Math.random() * 11.0).toLong())
                            noriaRef.set(noria.revaluate(dirtySet.toMap()))
                            scene.commit(0)

                            val dt = (System.nanoTime() - t0) / 1_000_000.0
                            frames[frameIdx] = dt
                            val avg = Arrays.stream(frames).average().asDouble
                            if ("DEBUG" == LOG_LEVEL)
                                println("[ perf ] frame %.3f ms".format(dt))
                            if ("INFO" == LOG_LEVEL && frameIdx == 0)
                                println("[ perf ] rolling avg %.3f ms".format(dt, avg))
                            frameIdx = (frameIdx + 1).rem(60)
                        }
                    }
                } catch (e: Exception) {
                    println("Commit failed")
                    e.printStackTrace()
                }
            }

            val updater = StateUpdater { nodeId, transform ->
                dirtySetRef.updateAndGet { dirtySet ->
                    markDirty(dirtySet, nodeId, transform)
                }
                if (!updateScheduled.getAndSet(true)) {
                    renderThread.schedule(update, 2, TimeUnit.MILLISECONDS)
                }
            }
            val rootBindings: HashMap<Any, Any> = hashMapOf(
                    SceneKey to scene,
                    UPDATER_KEY to updater)
            rootBindings.putAll(eventsBindings())
            val noria = NoriaImpl.noria(rootBindings) {
                val screenSize = global.read(currentFrame, "screenSize") as Size
                renderRoot(currentFrame, screenSize, implicitExpr { app() })
            }
            noriaRef.set(noria)
            scene.commit(0)
            global.onWrite = { dirtyIds ->
                for (nodeId in dirtyIds)
                    updater.accept(nodeId, Identity)
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }.start()
    return WindowState(windowId, noriaRef, renderThread)
}

fun shutdown(windows: Map<Long, WindowState>) {
    for (w in windows) {
        w.value.noriaRef.set(null)
    }
    PhotonApi.stopApplication()
}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { t: Thread, e: Throwable ->
        e.printStackTrace()
    }
    val windows = mapOf(
            defaultWindowId to createWindow(42, Size(1250.0f, 950.0f), "Noria Widgets Demo")
//            43L to createWindow(43, Size(1250.0f, 950.0f))
    )

    FontManager.define("NewYorkLarge-Regular.otf",       "New York", "Regular", 400)
    FontManager.define("NewYorkLarge-RegularItalic.otf", "New York", "Italic",  400)
    FontManager.define("NewYorkLarge-Bold.otf",          "New York", "Regular", 700)
    FontManager.define("NewYorkLarge-BoldItalic.otf",    "New York", "Italic",  700)
        
    PhotonApi.runEventLoop { events ->
        events.forEach { e ->
            val window = windows[defaultWindowId]!!
            when (e) {
                is WindowResize -> global.write("screenSize", e.size)

                is CloseRequest -> shutdown(windows)
                
                is KeyboardInput ->
                    if (e.keyCode == VirtualKeyCode.Q && e.modifiers.cmd)
                        shutdown(windows)
            }

            val n = window.noriaRef.get()
            if (n != null) {
                handleUserEvent(n, e)
            }
        }
    }
    for (w in windows) {
        w.value.renderThread.shutdown()
    }
}

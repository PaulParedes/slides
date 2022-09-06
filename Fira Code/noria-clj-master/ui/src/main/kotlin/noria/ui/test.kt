package noria.ui

import noria.*
import noria.scene.*
import java.io.File
import java.util.*
import kotlin.math.min

fun Frame.renderFile(file: File): View =
  padding(vertical = Dimension.px(2f)) {
    hbox {
      hug {
        if (file.isDirectory) {
          svg(Dimension.px(15f), Dimension.px(15f), "jetbrains.svg")
        }
        else {
          gap(width = 15f)
        }
      }
      hug {
        text(FontSpec("UI", "Regular", 14, 400), file.name, Color(0f, 1f, 0f, 0.7f))
      }
      hug {
        gap(width = 10f)
      }
      hug {
        text(FontSpec("UI", "Italic", 14, 400), file.absolutePath, Color(1f, 0f, 0f, 0.7f))
      }
    }
  }

fun Frame.counterView(): View {
  val counter = state { 0 }
  return hbox {
    hug {
      boundary {
        expr {
          text(font = FontSpec("UI", "Regular", 14, 400),
               text = "${read(counter)}",
               color = Color(0f, 1f, 1f, 1f))
        }
      }
    }
    hug {
      gap(width = 5f)
    }
    hug {
      jbbutton {
        counter.update { c -> c + 1 }
        println("hi")
      }
    }
  }
}

fun Frame.jbbutton(onClick: () -> Unit): View =
  view { constraints ->
    val size = Size(min(20f, constraints.maxWidth), min(20f, constraints.maxHeight))
    Layout(size, LayoutNode()) { viewport ->
      stack {
        hitBox(Rect(Point.ZERO, size),
               HitCallbacks(onMouseInput = { event, hit ->
                if (event.state == UserEvent.ButtonState.PRESSED &&
                    event.button == UserEvent.MouseButton.LEFT) {
                  onClick()
                  Propagate.STOP
                }
                else {
                  Propagate.CONTINUE
                }
              }))
        mount(Point.ZERO) {
          renderSVG(size, "jetbrains.svg")
        }
      }
    }
  }

fun Frame.app(): Scene {
  return window(Size(800f, 600f), "Hello world") {
    println("app")
    val page = state { 0 }

    val list1 = memo {
      val files = ArrayList<File>()
      listReq(File("/Users/jetzajac/Projects/on-air/frontend"), files)
      files.take(150)
    }

    val list2 = memo {
      val files = ArrayList<File>()
      listReq(File("/Users/jetzajac/Projects/on-air/noria-clj/ui/src"), files)
      files
    }

    val list = memo {
      if (read(page) == 0) {
        list1
      }
      else {
        list2
      }
    }
    decorate(backgroundColor = Color.black//,
//             borderRadius = 10f
    ) {
      padding(vertical = Dimension.px(5f), horizontal = Dimension.px(5f)) {
        vbox {
          hug { gap(height = 20f) }
          hug {
            hbox {
              hug {
                hitBox(onMouseInput = { mouseInput, hit ->
                  if (mouseInput.state == UserEvent.ButtonState.PRESSED) {
                    page.update { page -> (page + 1) % 2 }
                  }
                  Propagate.STOP
                }) {
                  text(font = defaultFont,
                       text = "${list.size} files",
                       color = Color.white)
                }
              }
              stretch(ratio = 1f) {
                gap()
              }
              hug {
                counterView()
              }
//              hug {
//                gap(width = 5f)
//              }
//              hug {
//                val profilerShowing = state { false }
//                hitBox(onMouseInput = { mouseInput, hit ->
//                  if (mouseInput.state == UserEvent.ButtonState.PRESSED) {
//                    PhotonApi.setDebugProfilerShowing(gloablTestOnlyWindowId, !profilerShowing.read(null))
//                    profilerShowing.update { showing -> !showing }
//                  }
//                  Propagate.STOP
//                }) {
//                  text(font = defaultFont,
//                       text = "profiler",
//                       color = Color(0f, 1f, 1f, 1f))
//                }
//              }
            }
          }
          hug {
            val position = state { Point.ZERO }
            scroll(position = position,
                   update = position::update) {
              constrain({ cs -> Constraints(cs.maxWidth, cs.maxWidth, cs.minHeight, cs.maxHeight) }) {
                vbox {
                  for (file in list) {
                    hug(file) {
                      renderFile(file)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}


fun listReq(file: File, out: MutableList<File>) {
  val files = file.listFiles()
  if (files != null) {
    out.addAll(files)
    for (f in files) {
      if (f.isDirectory) {
        listReq(f, out)
      }
    }
  }
}


val defaultFont: FontSpec = FontSpec("UI", "Regular", 14, 400)

fun main() {
  runSimpleOneWindowRenderLoop(EventsQueue()) {
    app()
  }
}





package noria.ui

import noria.Frame
import noria.scene.Size

fun Frame.gap(width: Float = 0f, height: Float = 0f): View =
  view { cs ->
    Layout(Size(width.coerceIn(cs.minWidth, cs.maxWidth),
                height.coerceIn(cs.minHeight, cs.maxHeight)), LayoutNode()) {
      stack {}
    }
  }
package noria.layout

import noria.scene.Size

data class Measure(@JvmField val width: Float, @JvmField val height: Float, @JvmField val extra: Any? = null) {

  fun asSize(): Size {
    return Size(width, height);
  }
}

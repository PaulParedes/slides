package noria.ui

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import noria.scene.*
import java.lang.IllegalStateException

fun layoutText(shape: PhotonApi.TextShape,
               fontMetrics: PhotonApi.FontMetrics,
               fontInstance: FontInstance,
               cs: Constraints): PhotonApi.TextLayout {
  val glyphIndexes = IntArray(shape.length)
  val glyphPositions = FloatArray(shape.length * 2)
  val scale = fontInstance.size.toFloat() / fontMetrics.unitsPerEm.toFloat()
  val ascent = fontMetrics.ascent * scale
  val lineHeight = (fontMetrics.ascent - fontMetrics.descent) * scale
  var xadvance = 0f
  var yadvance = 0f

  for (i in 0 until shape.length) {
    glyphIndexes[i] = shape.glyphIndexes[i]
    glyphPositions[i * 2] = xadvance + shape.xOffsets[i] * scale;
    glyphPositions[i * 2 + 1] = yadvance + shape.yOffsets[i] * scale + ascent;
    xadvance += scale * shape.xAdvances[i]
    yadvance += scale * shape.yAdvances[i]
  }
  val size = Size(xadvance, lineHeight)
  return PhotonApi.TextLayout(
    shape.length,
    glyphIndexes,
    shape.clusters,
    glyphPositions,
    fontInstance,
    size)
}

internal val shapeCache: LoadingCache<Pair<FontInstance, String>, PhotonApi.TextShape> = Caffeine.newBuilder()
  .maximumSize(10240)
  .executor(Runnable::run)
  .recordStats()
  .build { (font, text) ->
    PhotonApi.shapeText(font, text)
  }

fun text(font: FontSpec, text: String, color: Color): View {
  val fontInstance = FontManager.resolve(font)
  val textShape: PhotonApi.TextShape = shapeCache.get(fontInstance to text)!!
  return { constraints ->
    val textLayout: PhotonApi.TextLayout = layoutText(textShape, FontManager.fontMetrics(fontInstance), fontInstance, constraints)
    val size = textLayout.size()
    val clampedSize = Size(size.width.coerceIn(constraints.minWidth, constraints.maxWidth),
                           size.height.coerceIn(constraints.minHeight, constraints.maxHeight))
    Layout(clampedSize, LayoutNode()) {
      stack {
        text(Point.ZERO, clampedSize, color, textLayout)
      }
    }
  }
}


//fun text(font: FontSpec, text: String, color: Color): View {
//  val fontInstance = FontManager.resolve(font)
//  val textLayout: PhotonApi.TextLayout = cachedLayout(fontInstance, text)
//  return { constraints ->
//    val size = textLayout.size()
//    val clampedSize = Size(size.width.coerceIn(constraints.minWidth, constraints.maxWidth),
//                           size.height.coerceIn(constraints.minHeight, constraints.maxHeight))
//    Layout(clampedSize, LayoutNode()) {
//      stack { text(Point.ZERO, clampedSize, color, textLayout) }
//    }
//  }
//}


fun PhotonApi.TextLayout.glyphPosition(glyphIndex: Int): Point {
  return if (glyphIndex * 2 == glyphPositions.size) {
    Point(size.width, size.height)
  }
  else {
    val glyphX = glyphPositions[glyphIndex * 2]
    val glyphY = glyphPositions[glyphIndex * 2 + 1]
    Point(glyphX, glyphY)
  }
}

fun PhotonApi.TextLayout.glyphIndexByCodepoint(text: String, offset: Long): Int {
  val offsetByCodePoint = text.offsetByCodePoints(0, offset.toInt())
  if (offsetByCodePoint == text.length) return glyphClusters.size
  val utf8Length = text.substring(0, offsetByCodePoint).toByteArray().size
  val glyphIndex = glyphClusters.indexOf(utf8Length)
  if (glyphIndex < 0) {
    throw IllegalStateException("Glyph not found")
  }
  return glyphIndex
}

fun PhotonApi.TextLayout.sublayout(from: Int, to: Int): PhotonApi.TextLayout {
  val range = from until to
  val x = glyphPosition(from).x

  val glyphPositionsPrime = glyphPositions.sliceArray(from * 2 until to * 2)
  for (i in 0 until glyphPositionsPrime.size step 2) {
    glyphPositionsPrime[i] -= x
  }
  return PhotonApi.TextLayout((to - from),
                              glyphIndexes.sliceArray(range),
                              glyphClusters.sliceArray(range),
                              glyphPositionsPrime,
                              fontInstance,
                              Size(0f, 0f))
}



package noria.layout

import noria.scene.*
import com.github.benmanes.caffeine.cache.*
import noria.Frame
import noria.Thunk
import noria.generateId
import java.util.regex.*

val fontSystemRegular = FontSpec("UI", 13)
val fontSystemSemibold = FontSpec("UI", 13, 600)

val wordCache = Caffeine.newBuilder()
  .maximumSize(10240)
  .executor(Runnable::run)
  .recordStats()
  .build { key: Pair<FontInstance, String> ->
    PhotonApi.layoutText(key.first, Float.MAX_VALUE, key.second, PhotonApi.Wrap.LINES)
  }

fun cachedLayout(fontInstance: FontInstance, text: String): PhotonApi.TextLayout =
  wordCache.get(Pair(fontInstance, text))!!

private val pattern = Pattern.compile("(?: +|\n+|[^ \n]+)")

fun layout(fontInstance: FontInstance, text: String, cs: Constraints): Measure {

  val space = cachedLayout(fontInstance, " ")
  val spaceWidth = space.size().width
  val lineHeight = space.size().height

  var offsetX = 0f
  var offsetY = 0f
  val pieces = ArrayList<Pair<Point, PhotonApi.TextLayout>>()
  val matcher = pattern.matcher(text)

  while (matcher.find()) {
    val match: String = matcher.group()
    when (match[0]) {
      ' ' -> offsetX += match.length * spaceWidth
      '\n' -> {
        offsetX = 0f
        offsetY += match.length * lineHeight
      }
      else -> {
        val piece = cachedLayout(fontInstance, match)
        if (offsetX > 0f && offsetX + piece.size().width > cs.maxWidth) {
          offsetX = 0f
          offsetY += lineHeight
        }
        pieces.add(Pair(Point(offsetX, offsetY), piece))
        offsetX += piece.size().width
      }
    }
  }

  return Measure(offsetX, offsetY + lineHeight, pieces)
}

fun layoutText(text: String,
               shape: PhotonApi.TextShape,
               fontMetrics: PhotonApi.FontMetrics,
               fontInstance: FontInstance,
               cs: Constraints): PhotonApi.TextLayout {
  val glyphIndexes = IntArray(shape.length);
  val glyphPositions = FloatArray(shape.length);
  val scale = fontInstance.size.toFloat() / fontMetrics.unitsPerEm.toFloat()
  val lineHeight = (fontMetrics.ascent - fontMetrics.descent) * scale;
  var xoffset = 0f;
  var yoffset = 0f;

  for (i in 0..shape.length) {
    glyphIndexes[i] = shape.glyphIndexes[i];
    glyphPositions[i * 2] = xoffset;
    glyphPositions[i * 2 + 1] = yoffset;
    xoffset += scale * shape.xAdvances[i];
  }
  val size = Size(xoffset, lineHeight);
  return PhotonApi.TextLayout(shape.length, glyphIndexes, shape.clusters, glyphPositions, fontInstance, size)
}

data class Text(val text: String,
                val color: Color,
                val fontSpec: FontSpec,
                val wrapMode: PhotonApi.Wrap) : RenderObject {
  private val fontInstance = FontManager.resolve(fontSpec)

  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    return layout(fontInstance, text, cs)
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val scene = scene()
      val stackId = generateId()
      val pieces = measure.extra as ArrayList<Pair<Point, PhotonApi.TextLayout>>

      val ids = pieces.mapIndexed { idx, (origin, layout) ->
        val id = generateId(idx)
        scene.text(id, origin, layout.size(), color, layout)
        id
      }

      scene.stack(stackId, ids.toLongArray())
      return RenderResult(stackId)
    }
  }

  override fun displayName(): String {
    val subtext = if (text.length < 33) {
      text
    }
    else {
      text.substring(0, 30) + "..."
    }
    return "Text[\"$subtext\"]"
  }
}

private val separator = System.getProperty("file.separator")

data class Path(val path: String,
                val color: Color,
                val fontSpec: FontSpec) : RenderObject {
  private val fontInstance = FontManager.resolve(fontSpec)
  private val separatorLayout = cachedLayout(fontInstance, separator)
  private val ellipsis = cachedLayout(fontInstance, "...")

  fun layout(layouts: List<PhotonApi.TextLayout>,
             separator: PhotonApi.TextLayout,
             ellipsis: PhotonApi.TextLayout,
             cs: Constraints): List<PhotonApi.TextLayout> {

    val separatorWidth = separator.size().width
    val resultLayoutFront = mutableListOf<PhotonApi.TextLayout>()
    val resultLayoutBack = mutableListOf<PhotonApi.TextLayout>()

    resultLayoutBack.add(layouts.last())
    var totalWidth = layouts.last().size().width

    var (startIdx, endIdx) = Pair(0, layouts.size - 2)
    for (c in 0..layouts.size - 2) {
      val (nextItem, resultPart) = if (c % 2 == 0) {
        Pair(layouts[startIdx++], resultLayoutFront)
      }
      else {
        Pair(layouts[endIdx--], resultLayoutBack)
      }

      val nextItemWidth = if (c == layouts.size - 2) {
        // last item, no place to tuck "..."
        nextItem.size().width
      }
      else {
        nextItem.size().width + ellipsis.size().width
      }

      val expectedWidth = totalWidth + nextItemWidth + (resultLayoutBack.size + resultLayoutFront.size + 1) * separatorWidth

      if (expectedWidth < cs.maxWidth) {

        totalWidth += nextItem.size().width
        resultPart.add(nextItem)

      }
      else {
        resultPart.add(ellipsis)
        break
      }
    }

    resultLayoutFront.addAll(resultLayoutBack.reversed())
    return resultLayoutFront.flatMap { listOf(it, separator) }.dropLast(1)

  }

  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    val entries = path.split(separator)
    val layouts = entries.map { cachedLayout(fontInstance, it) }
    val resultLayout = layout(layouts, separatorLayout, ellipsis, cs)
    val (width, height) = resultLayout.fold(Pair(0f, 0f),
                                            { (w, h), layout -> Pair(w + layout.size().width, Math.max(h, layout.size().height)) })

    return Measure(width, height, resultLayout)
  }

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val scene = scene()
      val stackId = generateId()
      val layout = measure.extra as List<PhotonApi.TextLayout>

      var offset = 0f
      val nodes = LongArray(layout.size)
      for ((i, l) in layout.withIndex()) {
        val nodeId = generateId(i)
        scene.text(nodeId, Point(offset, 0.0f), l.size(), color, l)
        nodes[i] = nodeId
        offset += l.size().width
      }

      scene.stack(stackId, nodes)
      return RenderResult(stackId)
    }
  }
}
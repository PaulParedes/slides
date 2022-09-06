package noria.layout

import noria.*
import noria.scene.Constraints
import noria.scene.PhotonApi
import noria.scene.Point
import noria.scene.Rect
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors


interface ImageDescriptor {
  fun getContent(): ByteArray?
  fun width(): Float
  fun height(): Float
}

data class ResourceBasedImageDescriptor(val path: String, val width: Float, val height: Float) : ImageDescriptor {
  override fun width(): Float = width

  override fun height(): Float = height

  override fun getContent(): ByteArray? {
    val stream = javaClass.classLoader.getResourceAsStream(path)
    val bytes = stream?.readBytes() ?: return null
    // TODO handle svg image decoding in rust along with other image formats
    return if (File(path).extension.toLowerCase() == "svg") {
      transcode(bytes, width, height)
    }
    else {
      bytes
    }
  }
}

fun transcode(byteArray: ByteArray, width: Float, height: Float): ByteArray {
  val t = PNGTranscoder()
  t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, width * 2.0f)   // TODO magic constant means factor between hardware and virtual pixels
  t.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, height * 2.0f)
  val input = TranscoderInput(ByteArrayInputStream(byteArray))
  val outputByteArray = ByteArrayOutputStream()
  t.transcode(input, TranscoderOutput(outputByteArray))
  return outputByteArray.toByteArray()
}

class ImageStore {
  private val cache = ConcurrentHashMap<ImageDescriptor, CompletableFuture<Long?>>()
  private var nextImageId = 0L
  private val executor = Executors.newSingleThreadExecutor { r ->

    val thread = Thread(r)
    thread.isDaemon = true
    thread
  }

  fun loadImage(descriptor: ImageDescriptor): CompletableFuture<Long?> {
    return cache.getOrPut(descriptor) {
      val result = CompletableFuture<Long?>()
      executor.execute {
        try {
          val byteArray = descriptor.getContent()
          if (byteArray != null) {
            val id = this.nextImageId++
            PhotonApi.loadImage(id, byteArray)
            result.complete(id)
//            result.complete(null) // no icons for now
          }
          else {
            result.complete(null)
          }
        }
        catch (t: Throwable) {
          result.completeExceptionally(t)
        }
      }
      result
    }
  }
}

val imageStore = ImageStore()

data class Icon(val path: String,
                val width: Dimension? = null,
                val height: Dimension? = null,
                val scrollCommand: ScrollCommand? = null) : RenderObject {
  override fun measureImpl(frame: Frame, cs: Constraints): Measure {
    return Measure(
      width?.convert(cs.maxWidth) ?: cs.maxWidth,
      height?.convert(cs.maxHeight) ?: cs.maxHeight)
  }

  override fun makeVisibleImpl(frame: Frame, measure: Measure): ScrollCommand? = scrollCommand

  override fun renderImpl(frame: Frame, measure: Measure, tViewport: Thunk<Rect>, tWindowOffset: Thunk<Point>): RenderResult {
    with(frame) {
      val stackingContextId = generateId()
      val imageNodeId = generateId()
      val imageIdFuture = imageStore.loadImage(ResourceBasedImageDescriptor(path, measure.width, measure.height))
      if (imageIdFuture.isDone) {
        val imageId = imageIdFuture.get()
        if (imageId != null) {
          scene().image(imageNodeId, Point(.0f, .0f), measure.asSize(), imageId)
          scene().stack(stackingContextId, longArrayOf(imageNodeId))
          return RenderResult(stackingContextId)
        }
        else {
          return RenderResult.NIL
        }
      }
      else {
        val imageIdThunk = state<Long?> { null }
        imageIdFuture.thenAccept { imageId ->
          if (imageId != null) {
            imageIdThunk.update {
              imageId
            }
          }
        }
        expr {
          val imageId = read(imageIdThunk)
          if (imageId != null) {
            scene().image(imageNodeId, Point(.0f, .0f), measure.asSize(), imageId)
            scene().stack(stackingContextId, longArrayOf(imageNodeId))
          } else {
            scene().stack(stackingContextId, LongArray(0))
          }
        }
        return RenderResult(stackingContextId)
      }
    }
  }

  override fun displayName(): String = "Icon[$path]"
}

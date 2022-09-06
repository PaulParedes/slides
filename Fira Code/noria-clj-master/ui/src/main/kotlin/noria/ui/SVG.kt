package noria.ui

import noria.Frame
import noria.layout.ResourceBasedImageDescriptor
import noria.layout.imageStore
import noria.scene.Point
import noria.scene.Rect
import noria.scene.Size
import noria.state

fun SceneContext.renderSVG(size: Size, path: String): SceneNode.Stack {
  val imageIdFuture = imageStore.loadImage(ResourceBasedImageDescriptor(path, size.width, size.height))
  return if (imageIdFuture.isDone) {
    stack {
      val imageId = imageIdFuture.get()
      if (imageId != null) {
        image(Rect(Point(.0f, .0f), size), imageId)
      }
    }
  }
  else {
    renderBoundary(size, path) {
      val imageIdThunk = state<Long?> { null }
      imageIdFuture.thenAccept { imageId ->
        if (imageId != null) {
          imageIdThunk.update {
            imageId
          }
        }
      }
      val imageId = read(imageIdThunk)
      if (imageId != null) {
        image(Rect(Point(.0f, .0f), size), imageId)
      }
    }
  }
}

fun Frame.svg(width: Dimension?, height: Dimension?, path: String): View =
  view { cs ->
    val size = Size(width?.convert(cs.maxWidth) ?: cs.maxWidth,
                    height?.convert(cs.maxHeight) ?: cs.maxHeight)
    Layout(size, LayoutNode()) { _ ->
      renderSVG(size, path)
    }
  }


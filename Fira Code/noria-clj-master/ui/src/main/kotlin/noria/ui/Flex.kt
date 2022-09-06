package noria.ui

import noria.Frame
import noria.currentFrame
import noria.genKey
import noria.scene.Constraints
import noria.scene.Point
import noria.scene.Size
import noria.scope
import kotlin.math.max

class Flex(val frame: Frame) {
  val children: MutableList<Child> = ArrayList()

  sealed class Child {
    abstract val key: Any

    data class Px(override val key: Any, val px: Float, val child: View) : Child()
    data class Percent(override val key: Any, val percent: Float, val child: View) : Child()
    data class Stretch(override val key: Any, val ratio: Float, val child: View) : Child()
    data class Hug(override val key: Any, val child: View) : Child()
  }

  fun flexChild(arg: Child) {
    children.add(arg)
  }
}

inline fun Flex.px(key: Any = genKey(), px: Float, crossinline builder: Component) {
  flexChild(Flex.Child.Px(key, px, builder(frame)))
}

inline fun Flex.hug(key: Any = genKey(), crossinline builder: Component) {
  flexChild(Flex.Child.Hug(key, builder(frame)))
}

inline fun Flex.percent(key: Any = genKey(), percent: Float, crossinline builder: Component) {
  flexChild(Flex.Child.Percent(key, percent, builder(frame)))
}

inline fun Flex.stretch(key: Any = genKey(), ratio: Float, crossinline builder: Component) {
  flexChild(Flex.Child.Stretch(key, ratio, builder(frame)))
}

fun horizontal(flex: Flex): View =
  view { cs ->
    var taken = 0f
    var totalStretch = 0f
    val layouts = flex.children.mapTo(ArrayList()) { child ->
      when (child) {
        is Flex.Child.Px -> {
          val layout = scope(child.key) {
            child.child(currentFrame, Constraints(child.px, child.px, cs.minHeight, cs.maxHeight))
          }
          taken += child.px
          layout
        }
        is Flex.Child.Percent -> {
          val pixels = child.percent * cs.maxWidth
          val childCs = Constraints(pixels, pixels, cs.minHeight, cs.maxHeight)
          taken += pixels
          scope(child.key) {
            child.child(currentFrame, childCs)
          }
        }
        is Flex.Child.Hug -> {
          val childCs = Constraints(0f, cs.maxWidth - taken, cs.minHeight, cs.maxHeight)
          val layout = scope(child.key) {
            child.child(currentFrame, childCs)
          }
          taken += layout.size.width
          layout
        }
        is Flex.Child.Stretch -> {
          totalStretch += child.ratio
          null
        }
      }
    }
    val free = max(0f, cs.maxWidth - taken)
    var maxHeight = 0f
    var width = taken
    flex.children.forEachIndexed { i, child ->
      if (child is Flex.Child.Stretch) {
        val pixels = child.ratio / totalStretch * free
        val childCs = Constraints(pixels, pixels, cs.minHeight, cs.maxHeight)
        val layout = scope(child.key) {
          child.child(currentFrame, childCs)
        }
        width += layout.size.width
        layouts[i] = layout
      }
      maxHeight = max(maxHeight, layouts[i]!!.size.height)
    }

    val children = ArrayList<ContainerChild>(flex.children.size)
    var offset = 0f
    flex.children.forEachIndexed { i, child ->
      val layout = layouts[i]!!
      children.add(ContainerChild(layout = layout,
                                  key = child.key,
                                  position = Point(offset, 0f)))
      offset += layout.size.width
    }
    container(Size(width, maxHeight), children)
  }

fun vertical(flex: Flex): View =
  view { cs ->
    var taken = 0f
    var totalStretch = 0f
    val layouts = flex.children.mapTo(ArrayList()) { child ->
      when (child) {
        is Flex.Child.Px -> {
          val childCs = Constraints(cs.minWidth, cs.maxWidth, child.px, child.px)
          val layout = scope(child.key) {
            child.child(currentFrame, childCs)
          }
          taken += child.px
          layout
        }
        is Flex.Child.Percent -> {
          val pixels = child.percent * cs.maxHeight
          val childCs = Constraints(cs.minWidth, cs.maxWidth, pixels, pixels)
          taken += pixels
          scope(child.key) {
            child.child(currentFrame, childCs)
          }
        }
        is Flex.Child.Hug -> {
          val childCs = Constraints(cs.minWidth, cs.maxWidth, 0f, cs.maxHeight - taken)
          val layout = scope(child.key) {
            child.child(currentFrame, childCs)
          }
          taken += layout.size.height
          layout
        }
        is Flex.Child.Stretch -> {
          totalStretch += child.ratio
          null
        }
      }
    }
    val free = max(0f, cs.maxHeight - taken)
    var maxWidth = 0f
    var height = taken
    flex.children.forEachIndexed { i, child ->
      if (child is Flex.Child.Stretch) {
        val pixels = child.ratio / totalStretch * free
        val childCs = Constraints(cs.minWidth, cs.maxWidth, pixels, pixels)
        val render = scope(child.key) {
          child.child(currentFrame, childCs)
        }
        height += render.size.height
        layouts[i] = render
      }
      maxWidth = max(maxWidth, layouts[i]!!.size.width)
    }

    val children = ArrayList<ContainerChild>(flex.children.size)
    var offset = 0f
    flex.children.forEachIndexed { i, child ->
      val layout = layouts[i]!!
      children.add(ContainerChild(layout = layout,
                                  key = child.key,
                                  position = Point(0f, offset)))
      offset += layout.size.height
    }
    container(Size(maxWidth, height), children)
  }

inline fun Frame.hbox(crossinline builder: Flex.() -> Unit): View {
  val flex = Flex(currentFrame)
  flex.builder()
  return horizontal(flex)
}

inline fun Frame.vbox(crossinline builder: Flex.() -> Unit): View {
  val flex = Flex(currentFrame)
  flex.builder()
  return vertical(flex)
}
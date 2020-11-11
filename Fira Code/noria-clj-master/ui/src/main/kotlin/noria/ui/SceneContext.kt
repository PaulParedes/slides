package noria.ui

import noria.Frame
import noria.OpenFrame
import noria.currentFrame
import noria.expr
import noria.scene.*

typealias StackBuilder = StackingContext.() -> Unit

@Suppress("UNCHECKED_CAST")
class SceneContext(val frame: Frame,
                   val hitCallbacksRegistry: HitCallbacksRegistry,
                   val scene: Scene) : OpenFrame<Nothing, Nothing, Nothing> by frame as OpenFrame<Nothing, Nothing, Nothing> {

  inline fun stack(id: NodeId = frame.nextId(), crossinline builder: StackBuilder): SceneNode.Stack {
    val stack = SceneNode.Stack(id)
    val stackingContext = StackingContext(stack, this)
    stackingContext.builder()
    scene.stack(id, stackingContext.children.toNativeArray())
    return stack
  }

  inline fun renderBoundary(vararg deps: Any, crossinline builder: StackBuilder): SceneNode.Stack {
    val stack = SceneNode.Stack(-1)
    val stackId = this.frame.expr(*deps) {
      val stackId = id
      val context = StackingContext(stack, SceneContext(currentFrame, hitCallbacksRegistry, scene))
      context.builder()
      scene.stack(stackId, context.children.toNativeArray())
    }.thunkId
    stack.id = stackId
    return stack
  }
}




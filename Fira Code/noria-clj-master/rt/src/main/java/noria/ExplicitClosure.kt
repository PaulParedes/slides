package noria

import java.util.*

abstract class Closure<T>(val env: Array<out Any?>) {
  abstract fun invoke(frame: OpenFrame<*, T, *>): Thunk<T>
}

val WILDCARD = Object()

class ClosureReconciler<T> : Reconciler<Closure<T>, T, Closure<T>> {
  override fun needsReconcile(frame: SealedFrame<Closure<T>, T, Closure<T>>, newArg: Closure<T>): Boolean {
    val env = newArg.env
    return if (env.size == 1 && env[0] === WILDCARD) {
      true
    } else {
      return newArg.javaClass != frame.state?.javaClass || !Arrays.equals(frame.state?.env, newArg.env)
    }
  }

  override fun reconcile(frame: OpenFrame<Closure<T>, T, Closure<T>>, newArg: Closure<T>) {
    frame.state = newArg
    val oldV = frame.value
    val newV = newArg.invoke(frame)
    if (oldV != newV) {
      frame.value = newV
      frame.propagate()
    }
  }

  override fun destroy(frame: SealedFrame<Closure<T>, T, Closure<T>>) {
  }
}

val TheClosureReconciler = ClosureReconciler<Any?>()

inline fun <T> Frame.thunkImpl(env: Array<out Any?>, crossinline l: Frame.() -> Thunk<T>): Computation<T> {
  val closure = object : Closure<T>(env) {
    override fun invoke(frame: OpenFrame<*, T, *>): Thunk<T> {
      return frame.l()
    }
  }
  return child(closure.javaClass, TheClosureReconciler as ClosureReconciler<T>, closure)
}

package noria

interface StateArg<T> {
  fun init(): T
  fun destroy(state: T) {}
}

class Box<T>(val v: T)

class StateReconciler<T> : Reconciler<Box<T>, T, StateArg<T>> {
  override fun needsReconcile(frame: SealedFrame<Box<T>, T, StateArg<T>>,
                              newArg: StateArg<T>): Boolean {
    return false
  }

  override fun reconcile(frame: OpenFrame<Box<T>, T, StateArg<T>>,
                         newArg: StateArg<T>) {
    if (frame.state == null) {
      val v = newArg.init()
      frame.state = Box(v)
      frame.value = v
    }
    else {
      val newValue = frame.state!!.v
      val oldValue = frame.value
      frame.value = newValue
      if (newValue != oldValue) {
        frame.propagate()
      }
    }
  }

  override fun destroy(frame: SealedFrame<Box<T>, T, StateArg<T>>) {
    frame.argument!!.destroy(frame.value as T)
  }
}


data class StateThunk<T>(val thunk: Computation<T>,
                         val updater: StateUpdater) : Computation<T> {
  override val thunkId: Long
    get() = thunk.thunkId

  override fun read(frame: Frame?): T = thunk.read(frame)

  fun update(transform: Transformation<T>) {
    updater.accept(thunk.thunkId, StateUpdate { box ->
      Box(transform((box as Box<T>).v))
    })
  }
}

val TheStateReconciler = StateReconciler<Any?>()

inline fun <T> Frame.state(key: Any = generateKey(), crossinline initializer: () -> T): StateThunk<T> =
  StateThunk(
    this.child(
      key,
      TheStateReconciler,
      object : StateArg<Any?> {
        override fun init(): Any? {
          return initializer()
        }
      }) as Computation<T>,
    UPDATER_KEY[this.bindings])

inline fun <T> Frame.resource(key: Any = generateKey(),
                              crossinline initializer: () -> T,
                              crossinline destroyer: (T) -> Unit): StateThunk<T> =
  StateThunk(
    this.child(
      key,
      TheStateReconciler,
      object : StateArg<Any?> {
        override fun init(): Any? {
          return initializer()
        }

        override fun destroy(state: Any?) {
          destroyer(state as T)
        }
      }) as Computation<T>,
    UPDATER_KEY[this.bindings])

inline fun Frame.onDestroy(crossinline f: () -> Unit) {
  this.child(generateKey(), TheStateReconciler as StateReconciler<Unit>, object : StateArg<Unit> {
    override fun init() {}
    override fun destroy(state: Unit) {
      f()
    }
  })
}

inline fun Frame.once(crossinline f: () -> Unit) {
  this.child(generateKey(), TheStateReconciler as StateReconciler<Unit>, object : StateArg<Unit> {
    override fun init() {
      f()
    }
  })
}

class WithStateReconciler<T, U>: Reconciler<T, U, Pair<T, (T?, U?) -> U>> {
  override fun needsReconcile(frame: SealedFrame<T, U, Pair<T, (T?, U?) -> U>>, newArg: Pair<T, (T?, U?) -> U>): Boolean {
    return true
  }

  override fun reconcile(frame: OpenFrame<T, U, Pair<T, (T?, U?) -> U>>, newArg: Pair<T, (T?, U?) -> U>) {
    val (arg, f) = newArg
    val value = f(frame.state, frame.value as U?)
    frame.state = arg
    frame.value = value
    frame.propagate()
  }

  override fun destroy(frame: SealedFrame<T, U, Pair<T, (T?, U?) -> U>>) {
  }
}

val TheWithStateReconciler = WithStateReconciler<Any, Any>()

fun<T, U> Frame.withState(t: T, f: (T?, U?) -> U): Thunk<U> {
  return this.child(1, TheWithStateReconciler as WithStateReconciler<T, U>, t to f)
}

package noria

import java.util.function.Function

interface Computation<T> : Thunk<T> {
  val thunkId: Long
}

interface Reconciler<TState, TValue, TArg> {
  fun needsReconcile(frame: SealedFrame<TState, TValue, TArg>, newArg: TArg): Boolean
  fun reconcile(frame: OpenFrame<TState, TValue, TArg>, newArg: TArg)
  fun destroy(frame: SealedFrame<TState, TValue, TArg>)
}

typealias Frame = OpenFrame<*, *, *>

interface SealedFrame<TState, TValue, TArg> {
  val bindings: Bindings
  val state: TState?
  val value: Any?
  val argument: TArg?
  val id: Long
}

interface OpenFrame<TState, TValue, TArg> : SealedFrame<TState, TValue, TArg> {

  fun <TValue : Any?, TArg : Any?> child(key: Any?,
                                         reconciler: Reconciler<*, TValue, TArg>,
                                         arg: TArg,
                                         bindings: Bindings): Computation<TValue>

  fun <TValue : Any?, TArg : Any?> child(key: Any?, reconciler: Reconciler<*, TValue, TArg>, arg: TArg): Computation<TValue> {
    return this.child(key, reconciler, arg, bindings)
  }

  override var state: TState?
  override var value: Any?
  var depth: Int
  fun propagate()

  fun <T> read(t: Thunk<T>): T {
    return t.read(this)
  }

  fun enterScope(scope: Any)
  fun enterScope(scope: Int)
  fun exitScope()

  fun nextId(): Long
}

interface Noria<T, TArg> {
  fun revaluate(arg: TArg, dirtySet: Map<Long, Function<Any?, Any?>?>): Noria<T, TArg>

  fun revaluate(dirtySet: Map<Long, Function<Any?, Any?>?>): Noria<T, TArg> {
    return revaluate(this.arg, dirtySet)
  }

  val result: T
  val rootId: Long
  val arg: TArg
  fun <U> read(id: Long): U?
  fun <U> read(thunk: Thunk<U>): U
  val rootBindings: Bindings
  fun destroy()
}

typealias Middleware = (Reconciler<Any?, Any?, Any?>) -> Reconciler<Any?, Any?, Any?>

interface Runtime {
  fun <T, TArg> evaluate(reconciler: Reconciler<*, T, TArg>, arg: TArg, rootBindings: Bindings, middleware: Middleware = {it}): Noria<T, TArg>
}

@JvmField
val UPDATER_KEY: Attr<StateUpdater> = Attr("noria.updater")

typealias Expr<T> = Frame.() -> T

typealias ThunkExpr<T> = Expr<Thunk<T>>

val Frame.currentFrame get() = this

inline fun <T> Frame.scope(key: Any, crossinline f: Expr<T>): T {
  enterScope(key)
  val res = f()
  exitScope()
  return res
}

inline fun <T> Frame.thunk(vararg env: Any?, crossinline l: Frame.() -> Thunk<T>): Computation<T> {
  return thunkImpl(env, l)
}

inline fun <T> Frame.expr(vararg env: Any?, crossinline l: Expr<T>): Computation<T> {
  return thunkImpl(env) { Thunk.of(l()) }
}

inline fun <T> Frame.memo(vararg env: Any?, crossinline l: Expr<T>): T {
  return read(thunkImpl(env) { Thunk.of(l()) })
}









typealias Transformation<T> = (T) -> T
typealias StateUpdate = Function<Any?, Any?>
typealias StateUpdater = java.util.function.BiConsumer<Long, StateUpdate>
typealias DirtySet = io.lacuna.bifurcan.IMap<Long, Function<Any?, Any?>?>

val Identity = Function<Any?, Any?> { it }

fun markDirty(m: DirtySet, id: Long, update: StateUpdate = Identity): DirtySet {
  val old = m.get(id, null)
  return if (old != null)
    m.put(id, Function { state -> update.apply(old.apply(state)) })
  else
    m.put(id, Function { update.apply(it) })
}

inline fun Frame.generateId(key: Any? = null) = implicitExpr(key = key) { null }.thunkId



inline fun genKey(): Any {
  return object{}.javaClass
}

class IdentityObject(val x: Any?) {
  override fun equals(other: Any?): Boolean {
    return other is IdentityObject && other.x === x
  }

  override fun hashCode(): Int {
    return System.identityHashCode(x)
  }
}

fun identity(x: Any?) : Any {
  return IdentityObject(x)
}

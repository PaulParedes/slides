@file:Suppress("unused")

package noria

import gnu.trove.TLongObjectHashMap
import java.lang.AssertionError
import java.lang.IllegalStateException
import java.util.*
import java.util.function.Function

internal fun comparePaths(p1: IntArray, p2: IntArray): Int {
  var i = 0
  while (i < p1.size && i < p2.size) {
    if (p1[i] != p2[i]) {
      return Integer.compare(p1[i], p2[i])
    }
    ++i
  }
  return Integer.compare(p1.size, p2.size)
}

internal fun isPrefixPath(parent: IntArray, child: IntArray): Boolean {
  return if (parent.size < child.size) {
    for (i in 1 until parent.size) {
      if (parent[i] != child[i]) {
        return false
      }
    }
    true
  }
  else {
    false
  }
}

@Suppress("UNCHECKED_CAST")
internal class FrameImpl(val parent: FrameImpl?,
                         val context: NoriaImpl,
                         override val id: Long,
                         val reconciler: Reconciler<Any?, Any?, Any?>,
                         override var argument: Any?,
                         override var bindings: Bindings,
                         override var depth: Int) : OpenFrame<Any?, Any?, Any?>, Computation<Any?> {

  var order: Int = -1
  var deps: ArrayList<FrameImpl>? = null
  var dependants: MutableSet<FrameImpl>? = null
  var childrenByKeys: MutableMap<Any?, FrameImpl>? = null
  var committedValue: Any? = null
  override var state: Any? = null
  var mutation: Mutation? = null

  class Mutation {
    var valid: Boolean = false
    var changed: Boolean = false
    var updateState: ((Any?) -> Any?)? = null
    var newDeps: ArrayList<FrameImpl>? = null
    var newChildren: MutableSet<FrameImpl>? = null
    var updatedValue: Any? = null
    var newOrder: Int = -1
  }

  override var value: Any?
    get() {
      return mutation!!.updatedValue
    }
    set(newValue) {
      mutation!!.updatedValue = newValue
    }

  override fun propagate() {
    mutation!!.changed = true
    val heap = context.heap
    dependants?.forEach { dependant ->
      if (!dependant.isValid()) {
        heap.add(resolvePath(dependant))
      }
    }
  }

  override fun toString(): String {
    return "Thunk#$id"
  }

  override val thunkId: Long
    get() {
      return id
    }

  override fun read(frame: OpenFrame<*, *, *>?): Any? {
    return read(this, frame)
  }

  override fun <TValue : Any?, TArg : Any?> child(key: Any?,
                                                  reconciler: Reconciler<*, TValue, TArg>,
                                                  arg: TArg,
                                                  bindings: Bindings): Computation<TValue> {
    return reconcileChild(this@FrameImpl, key, arg, reconciler as Reconciler<Any?, Any?, Any?>, bindings) as Computation<TValue>
  }

  override fun enterScope(scope: Any) {
    context.scopes.push(scope)
  }

  override fun enterScope(scope: Int) {
    context.scopes.push(scope)
  }

  override fun exitScope() {
    context.scopes.pop()
  }

  override fun nextId(): Long {
    return context.nextId()
  }
}

internal fun read(thunk: FrameImpl, frame: OpenFrame<*, *, *>?): Any? {
  if (frame != null) {
    addDependency(frame as FrameImpl, thunk)
  }
  val value =
    if (thunk.mutation == null) {
      thunk.committedValue
    }
    else {
      thunk.mutation!!.updatedValue
    }
  return if (value is Thunk<*>) {
    value.read(frame)
  }
  else {
    value
  }
}

internal typealias Path = IntArray

internal class FrameWithPath(val path: Path, val frame: FrameImpl)

internal fun FrameImpl.isChanged(): Boolean {
  return this.mutation?.changed == true
}

internal fun FrameImpl.isValid(): Boolean {
  return this.mutation?.valid == true
}

internal fun resolvePath(frame: FrameImpl): FrameWithPath {
  fun resolvePathAux(frame: FrameImpl, theFrame: FrameImpl, depth: Int): IntArray {
    val parent = frame.parent
    return if (parent == null) {
      IntArray(depth)
    }
    else {
      val result = resolvePathAux(parent, theFrame, depth + 1)
      result[result.size - depth - 1] = frame.order
      result
    }
  }
  return FrameWithPath(path = resolvePathAux(frame, frame, 0), frame = frame)
}

internal fun reconcileChild(parent: FrameImpl,
                            key1: Any?,
                            arg: Any?,
                            reconciler: Reconciler<Any?, Any?, Any?>,
                            bindings: Bindings): Computation<*> {
  val key = scopedKey(parent.context, key1)
  val child = parent.childrenByKeys?.get(key)
  if (child != null) {
    val mutation = parent.mutation()
    if (mutation.newChildren == null) {
      mutation.newChildren = hashSetOf()
    }
    child.mutation().newOrder = mutation.newChildren!!.size
    mutation.newChildren!!.add(child)

    if (child.isValid()) {
      return child
    }

//    if (reconciler != child.reconciler) {
//      throw IllegalStateException("reconciler has changed!")
//    }
    if (child.isChanged() ||
        child.deps?.any { dep -> dep.isChanged() } == true ||
        bindings != child.bindings ||
        child.reconciler.needsReconcile(child, arg)) {
      child.bindings = bindings
      child.argument = arg
      eval(child)
    }
    else {
      pumpDescendants(child)
      child.argument = arg
    }
    return child
  }
  else {
    return makeChild(parent, reconciler, arg, bindings, key)
  }
}

internal fun scopedKey(context: NoriaImpl, key1: Any?): Any? {
  val stack = context.scopes
  val key = if (!stack.isEmpty()) {
    val k = ArrayList(stack)
    k.add(key1)
    k
  }
  else {
    key1
  }
  return key
}

internal fun pumpDescendants(child: FrameImpl) {
  val childPath = resolvePath(child)
  val sink = arrayListOf<FrameWithPath>()
  val heap = child.context.heap
  while (!heap.isEmpty()) {
    val peek = heap.peek()
    if (peek.frame.isValid()) {
      heap.poll()
    }
    else {
      val cmp = comparePaths(peek.path, childPath.path)
      if (cmp == -1 || cmp == 0) {
        heap.poll()
        sink.add(peek)
      }
      else {
        while (!heap.isEmpty()) {
          val peek1 = heap.peek()
          if (peek1.frame == child || isPrefixPath(childPath.path, peek1.path)) {
            heap.poll()
            if (!peek1.frame.isValid()) {
              eval(peek1.frame)
            }
          }
          else {
            break
          }
        }
        break
      }
    }
  }
  heap.addAll(sink)
}

internal fun makeChild(parent: FrameImpl,
                       reconciler1: Reconciler<Any?, Any?, Any?>,
                       arg: Any?,
                       bindings: Bindings,
                       key: Any?): FrameImpl {
  val mutation = parent.mutation()
  val context = parent.context
  val stack = context.scopes
  val reconciler = context.middleware(reconciler1)
  val child = FrameImpl(parent, context, context.nextId(), reconciler, arg, bindings, parent.depth)

  val childMutation = child.mutation()
  childMutation.newOrder = mutation.newChildren?.size ?: 0
  childMutation.valid = true
  childMutation.changed = true
  context.scopes = ArrayDeque()
  reconciler.reconcile(child, arg)
  child.argument = arg
  context.scopes = stack
  child.deps = childMutation.newDeps

  if (mutation.newChildren == null) {
    mutation.newChildren = HashSet()
  }
  mutation.newChildren!!.add(child)
  if (parent.childrenByKeys == null) {
    parent.childrenByKeys = HashMap()
  }
  parent.childrenByKeys!!.put(key, child)
  context.thunksById.put(child.id, child)
  return child
}

internal fun addDependency(dependant: FrameImpl, dependency: FrameImpl) {
  val mutation = dependant.mutation!!
  if (mutation.newDeps == null) {
    mutation.newDeps = ArrayList()
  }
  mutation.newDeps!!.add(dependency)
  if (dependency.dependants == null) {
    dependency.dependants = hashSetOf()
  }
  dependency.dependants!!.add(dependant)
}

internal fun eval(frame: FrameImpl) {
  val stack = frame.context.scopes
  frame.context.scopes = ArrayDeque()
  // mark as already processed
  val mutation = frame.mutation()
  mutation.valid = true

  if (mutation.updateState != null) {
    frame.state = mutation.updateState!!(frame.state)
  }

  frame.reconciler.reconcile(frame, frame.argument)

  // update childrenByKeys:
  val childrenByKeys1 = frame.childrenByKeys
  val newChildren1 = mutation.newChildren
  if (childrenByKeys1 != null) {
    val iterator = childrenByKeys1.entries.iterator()
    var allDead = true
    while (iterator.hasNext()) {
      val kv = iterator.next()
      val child = kv.value
      if (newChildren1?.contains(child) == false) {
        destroy(child)
        iterator.remove()
      }
      else {
        allDead = false
      }
    }
    if (allDead) {
      frame.childrenByKeys = null
    }
  }

  // update dependants:
  val deps1 = frame.deps
  val newDeps1 = mutation.newDeps
  if (deps1 != null) {
    for (dep in deps1) {
      if (newDeps1 == null || !newDeps1.contains(dep)) {
        dep.dependants!!.remove(frame)
      }
    }
  }
  frame.deps = newDeps1
  frame.context.scopes = stack
}

internal fun destroy(frame: FrameImpl) {
  frame.mutation().valid = true
  frame.reconciler.destroy(frame)
  frame.context.thunksById.remove(frame.id)
  frame.childrenByKeys?.forEach { k, child -> destroy(child) }
  frame.deps?.forEach { dependency ->
    dependency.dependants?.remove(frame)
  }
}

internal fun FrameImpl.commit() {
  val mutation = mutation!!
  order = mutation.newOrder
  committedValue = mutation.updatedValue
  this.mutation = null
}

internal fun FrameImpl.mutation(): FrameImpl.Mutation {
  if (mutation == null) {
    val mutation1 = FrameImpl.Mutation()
    mutation1.updatedValue = committedValue
    mutation1.newOrder = order
    mutation = mutation1
    context.updated.add(this)
  }
  return mutation!!
}

internal object RootReconciler : Reconciler<Any?, Any?, Any?> {
  override fun needsReconcile(frame: SealedFrame<Any?, Any?, Any?>, newArg: Any?): Boolean {
    throw AssertionError()
  }

  override fun reconcile(frame: OpenFrame<Any?, Any?, Any?>, newArg: Any?) {
    throw AssertionError()
  }

  override fun destroy(frame: SealedFrame<Any?, Any?, Any?>) {
    throw AssertionError()
  }
}

@Suppress("UNCHECKED_CAST")
class NoriaImpl(rootBindings: Bindings,
                val middleware: Middleware = { it }) : Noria<Any?, Any?> {
  internal val heap: PriorityQueue<FrameWithPath> = PriorityQueue(Comparator<FrameWithPath> { o1, o2 ->
    comparePaths(o1!!.path, o2!!.path)
  })
  internal lateinit var root: FrameImpl
  internal val rootParent: FrameImpl = FrameImpl(parent = null,
                                                 context = this,
                                                 id = 0,
                                                 reconciler = RootReconciler,
                                                 argument = null,
                                                 bindings = rootBindings,
                                                 depth = 0)
  internal val updated: MutableList<FrameImpl> = ArrayList()
  internal val updateLock = Object()
  internal var nextId: Long = 1
  internal val thunksById: TLongObjectHashMap<FrameImpl> = TLongObjectHashMap()
  internal var scopes: ArrayDeque<Any?> = ArrayDeque()

  override val arg: Any?
    get() = root.argument

  companion object : Runtime {
    internal val rootKey = Object()
    override fun <T : Any?, TArg : Any?> evaluate(reconciler: Reconciler<*, T, TArg>, arg: TArg, rootBindings: Bindings, middleware: Middleware): Noria<T, TArg> {
      val res = NoriaImpl(rootBindings, middleware)
      val rootFrame = res.rootParent.child(rootKey, reconciler, arg) as FrameImpl
      res.commit()
      res.thunksById.put(rootFrame.id, rootFrame)
      res.root = rootFrame
      if (!res.heap.isEmpty()) {
        throw AssertionError()
      }
      return res as Noria<T, TArg>
    }
  }

  fun <T> readConsistently(f: () -> T) {
    synchronized(updateLock) {
      f()
    }
  }

  fun nextId(): Long {
    return this.nextId++
  }

  override val rootId: Long
    get() {
      return this.root.id
    }

  override fun revaluate(arg: Any?, dirtySet: Map<Long, Function<Any?, Any?>?>): NoriaImpl {
    dirtySet.forEach { (id, f) ->
      val frame = thunksById.get(id)
      if (frame != null) {
        val mutation = frame.mutation()
        if (f != null) {
          mutation.updateState = { state -> f.apply(state) }
        }
        heap.add(resolvePath(frame))
        mutation.changed = true
      }
    }
    rootParent.child(rootKey, root.reconciler, arg)

    //sanity check
    for (f in heap) {
      if (!f.frame.isValid()) {
        throw AssertionError()
      }
    }
    //
    heap.clear()
    commit()
    return this
  }

  private fun commit() {
    synchronized(updateLock) {
      updated.forEach { frame ->
        frame.commit()
      }
      updated.clear()
    }
  }

  override val result: Any?
    get() = read(root)

  override fun <U : Any?> read(id: Long): U? {
    val thunk = thunksById.get(id)
    if (thunk == null) {
      println("thunk doesn't exist")
      return null
    }
    else {
      return read(thunk as Thunk<U>)
    }
  }

  override fun <U : Any?> read(thunk: Thunk<U>): U {
    return thunk.read(null)
  }

  override val rootBindings: Bindings
    get() = root.bindings

  override fun destroy() {
    destroy(root)
  }
}

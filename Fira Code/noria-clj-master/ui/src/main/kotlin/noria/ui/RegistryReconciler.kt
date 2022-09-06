package noria.ui

import noria.OpenFrame
import noria.Reconciler
import noria.SealedFrame

typealias Registry<T> = MutableMap<Long, T>
typealias RegistryEntry<T> = Pair<Registry<T>, T>

class RegistryReconciler<T>: Reconciler<Unit, Unit, RegistryEntry<T>> {
  override fun needsReconcile(frame: SealedFrame<Unit, Unit, RegistryEntry<T>>, newArg: RegistryEntry<T>): Boolean {
    return true
  }

  override fun reconcile(frame: OpenFrame<Unit, Unit, RegistryEntry<T>>, newArg: RegistryEntry<T>) {
    val (map, v) = newArg
    map.put(frame.id, v)
  }

  override fun destroy(frame: SealedFrame<Unit, Unit, RegistryEntry<T>>) {
    val (map, _) = frame.argument!!
    map.remove(frame.id)
  }
}

package noria

import io.lacuna.bifurcan.IMap
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator

data class Bindings(val impl: IMap<Any, Any?>) {
  operator fun get(attr: Any): Any? {
    return impl[attr, null]
  }

  inline fun <T> updateRef(attr: Attr<AtomicReference<T>>, crossinline f: (T) -> T): T {
    return attr[this].updateAndGet { value -> f(value) }
  }

  fun assoc(attr: Any, value: Any?): Bindings {
    return Bindings(impl.put(attr, value))
  }

  fun merge(m: Map<*, *>?): Bindings {
    return if (m == null) {
      this
    }
    else {
      var i = impl.linear()
      for (b in m.entries) {
        val binding = b as Map.Entry<*, *>
        i = i.put(binding.key, binding.value)
      }
      Bindings(i.forked())
    }
  }

  companion object {
    @JvmStatic
    fun fromMap(m: Map<Any, Any?>?): Bindings {
      return Bindings(io.lacuna.bifurcan.Map.from(m))
    }
  }
}

data class Attr<T>(val ident: String)

@Suppress("UNCHECKED_CAST")
operator fun <T> Attr<T>.get(bindings: Bindings): T {
  return bindings.get(this) as T
}
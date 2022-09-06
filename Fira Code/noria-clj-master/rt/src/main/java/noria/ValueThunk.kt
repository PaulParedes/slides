package noria

import java.util.*

class ValueThunk<T>(private val value: T) : Thunk<T> {
  override fun read(frame: OpenFrame<*, *, *>?): T {
    return if (value is Thunk<*>) (value as Thunk<*>).read(frame) as T else value
  }

  override fun toString(): String {
    return "ValueThunk[$value]"
  }

  override fun equals(o: Any?): Boolean {
    if (this === o) return true
    if (o == null || javaClass != o.javaClass) return false
    val that = o as ValueThunk<*>
    return value == that.value
  }

  override fun hashCode(): Int {
    return Objects.hash(value)
  }

}
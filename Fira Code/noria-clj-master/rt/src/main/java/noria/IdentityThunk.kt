package noria

class IdentityThunk<T>(private val value: T) : Thunk<T> {
  override fun read(frame: OpenFrame<*, *, *>?): T {
    return value
  }

  override fun toString(): String {
    return "IdentityThunk[$value]"
  }

  override fun equals(o: Any?): Boolean {
    if (this === o) return true
    if (o == null || javaClass != o.javaClass) return false
    val that = o as IdentityThunk<*>
    return value === that.value
  }

  override fun hashCode(): Int {
    return System.identityHashCode(value) + 1
  }
}
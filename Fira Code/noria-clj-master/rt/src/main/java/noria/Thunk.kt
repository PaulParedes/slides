package noria

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

interface Thunk<T> : Future<T> {

  fun read(frame: OpenFrame<*, *, *>?): T

  // Future
  @Suppress("UNCHECKED_CAST")
  override fun get(): T {
    return read(FRAME.get() as OpenFrame<*, T, *>?)
  }

  @Suppress("UNCHECKED_CAST")
  override fun get(timeout: Long, unit: TimeUnit): T {
    return read(FRAME.get() as OpenFrame<*, T, *>?)
  }

  override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
    throw UnsupportedOperationException()
  }

  override fun isCancelled(): Boolean {
    throw UnsupportedOperationException()
  }

  override fun isDone(): Boolean {
    throw UnsupportedOperationException()
  }

  companion object {
    @JvmStatic
    fun <T> of(value: T): Thunk<T> {
      return ValueThunk(value)
    }

    @JvmStatic
    fun <T> identity(value: T): Thunk<T> {
      return IdentityThunk(value)
    }

    @JvmField
    val FRAME = ThreadLocal<OpenFrame<*, *, *>?>()
  }
}
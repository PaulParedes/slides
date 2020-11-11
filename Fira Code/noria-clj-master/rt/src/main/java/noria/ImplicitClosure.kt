package noria

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger

interface ImplicitClosure<out T> {
  fun apply(frame: OpenFrame<*, *, *>): T
}

interface ClosureEquality {
  fun eq(c1: ImplicitClosure<*>, c2: ImplicitClosure<*>): Boolean
}

class ClosureState(val cachedClosure: ImplicitClosure<*>,
                   var equality: ClosureEquality?)

object DynamicClassLoader {
  val defineClass: Method

  init {
    defineClass = ClassLoader::class.java.getDeclaredMethod("defineClass", String::class.java, ByteArray::class.java, Integer.TYPE,
                                                            Integer.TYPE)
    defineClass.setAccessible(true)
  }

  fun makeClass(cl: ClassLoader, name: String, bytecode: ByteArray): Class<*> {
    return defineClass.invoke(cl, name, bytecode, 0, bytecode.size) as Class<*>
  }
}

fun generateEquality(sourceClass: Class<*>, id: Int): ClosureEquality {
  val sourceClassName = Type.getInternalName(sourceClass)

  val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
  val newClassName = "${sourceClass.`package`.name.replace('.', '/')}/closureEquality${id}"
  cw.visit(Opcodes.V1_8,
           Opcodes.ACC_PUBLIC,
           newClassName,
           null,
           "java/lang/Object",
           arrayOf("noria/ClosureEquality"))

  // constructor
  val constr = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
  constr.visitVarInsn(Opcodes.ALOAD, 0) // push `this` to the operand stack
  constr.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V") // call the constructor of super class
  constr.visitInsn(Opcodes.RETURN)
  constr.visitMaxs(2, 1)
  constr.visitEnd()

  val objects = Type.getInternalName(java.util.Objects::class.java)
  val objectsEqualsMethod = java.util.Objects::class.java.getMethod("equals", Object::class.java, Object::class.java)
  val objectsEqualsMethodDesc = Type.getMethodDescriptor(objectsEqualsMethod)

  val eqMethod = ClosureEquality::class.java.getMethod("eq", ImplicitClosure::class.java, ImplicitClosure::class.java)

  val mv = cw.visitMethod(
    Opcodes.ACC_PUBLIC,
    "eq",
    Type.getMethodDescriptor(eqMethod),
    null,
    null)
  mv.visitCode()
  val exitLabel = Label()
  val noLabel = Label()

  mv.visitVarInsn(Opcodes.ALOAD, 1)
  mv.visitTypeInsn(Opcodes.CHECKCAST, sourceClassName)
  mv.visitVarInsn(Opcodes.ASTORE, 3)

  mv.visitVarInsn(Opcodes.ALOAD, 2)
  mv.visitTypeInsn(Opcodes.CHECKCAST, sourceClassName)
  mv.visitVarInsn(Opcodes.ASTORE, 4)

  for (field in sourceClass.declaredFields) {
    field.isAccessible = true
    val fieldTypeName = Type.getDescriptor(field.type)
    mv.visitVarInsn(Opcodes.ALOAD, 3)
    mv.visitFieldInsn(Opcodes.GETFIELD, sourceClassName, field.name, fieldTypeName)

    mv.visitVarInsn(Opcodes.ALOAD, 4)
    mv.visitFieldInsn(Opcodes.GETFIELD, sourceClassName, field.name, fieldTypeName)

    // now there are two values on stack: o1.field and o2.field
    if (field.type == java.lang.Integer.TYPE ||
        field.type == java.lang.Character.TYPE ||
        field.type == java.lang.Short.TYPE ||
        field.type == java.lang.Byte.TYPE ||
        field.type == java.lang.Boolean.TYPE) {
      mv.visitJumpInsn(Opcodes.IF_ICMPNE, noLabel)
    }
    else if (field.type == java.lang.Long.TYPE) {
      mv.visitInsn(Opcodes.LCMP)
      mv.visitJumpInsn(Opcodes.IFNE, noLabel)
    }
    else if (field.type == java.lang.Float.TYPE) {
      mv.visitInsn(Opcodes.FCMPL)
      mv.visitJumpInsn(Opcodes.IFNE, noLabel)
    }
    else if (field.type == java.lang.Double.TYPE) {
      mv.visitInsn(Opcodes.DCMPL)
      mv.visitJumpInsn(Opcodes.IFNE, noLabel)
    }
    else if (field.type.isArray) {
      throw UnsupportedOperationException("closing over arrays is not supported yet")
    }
    else {
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, objects, "equals", objectsEqualsMethodDesc)
      mv.visitJumpInsn(Opcodes.IFEQ, noLabel)
    }
  }
  mv.visitInsn(Opcodes.ICONST_1)
  mv.visitJumpInsn(Opcodes.GOTO, exitLabel)
  mv.visitLabel(noLabel)
  mv.visitInsn(Opcodes.ICONST_0)
  mv.visitLabel(exitLabel)
  mv.visitInsn(Opcodes.IRETURN)
  mv.visitMaxs(2, 2)
  mv.visitEnd()
  cw.visitEnd()
  val bytecode = cw.toByteArray()
  val newClass = DynamicClassLoader.makeClass(sourceClass.classLoader, "${sourceClass.`package`.name}.closureEquality${id}", bytecode)
  return newClass.newInstance() as ClosureEquality
}

object EqualityCache {
  val cache: ConcurrentMap<Class<*>, ClosureEquality> = ConcurrentHashMap()
  val nextId: AtomicInteger = AtomicInteger(0)
  fun cachedEquality(c: Class<*>): ClosureEquality {
    val eq = cache.computeIfAbsent(c) { cls ->
      generateEquality(cls, nextId.incrementAndGet())
    }
    return eq
  }
}

object ImplicitClosureReconciler : Reconciler<ClosureState, Any?, ImplicitClosure<Thunk<*>>> {
  override fun needsReconcile(frame: SealedFrame<ClosureState, Any?, ImplicitClosure<Thunk<*>>>,
                              closure: ImplicitClosure<Thunk<*>>): Boolean {
    val state = frame.state!!
    return if (state.cachedClosure.javaClass == closure.javaClass) {
      val eq =
        if (state.equality != null) {
          state.equality!!
        }
        else {
          val eq = EqualityCache.cachedEquality(closure.javaClass)
          state.equality = eq
          eq
        }
      !eq.eq(state.cachedClosure, closure)
    }
    else {
      true
    }
  }

  override fun destroy(frame: SealedFrame<ClosureState, Any?, ImplicitClosure<Thunk<*>>>) {
  }

  override fun reconcile(frame: OpenFrame<ClosureState, Any?, ImplicitClosure<Thunk<*>>>,
                         closure: ImplicitClosure<Thunk<*>>) {
    val v = closure.apply(frame) as Thunk<Any?>
    frame.state = ClosureState(closure, frame.state?.equality)
    if (frame.value != v) {
      frame.propagate()
    }
    frame.value = v
  }
}

inline fun generateKey(): Any = object {}.javaClass

inline fun <T> Frame.implicitThunk(key: Any? = null, bindings: Map<*, *>? = null, crossinline f: ThunkExpr<T>): Computation<T> {
  val closure = object : ImplicitClosure<Thunk<T>> {
    override fun apply(frame: Frame): Thunk<T> {
      return frame.f()
    }
  }
  return this.child(
    key ?: closure.javaClass,
    ImplicitClosureReconciler,
    closure as ImplicitClosure<Thunk<*>>,
    if (bindings != null) {
      this.bindings.merge(bindings)
    }
    else {
      this.bindings
    }) as Computation<T>
}

inline fun <T> Frame.implicitMemo(key: Any? = null, bindings: Map<*, *>? = null, crossinline f: Expr<T>): T =
  this.read(this.implicitExpr(key, bindings) { f() })

inline fun <T> Frame.implicitExpr(key: Any? = null, bindings: Map<*, *>? = null, crossinline f: Expr<T>): Computation<T> =
  this.implicitThunk(key, bindings) {
    Thunk.of(this.f())
  }

inline fun <T> Runtime.noria(rootBindings: Map<Any, Any> = hashMapOf(), crossinline f: Expr<T>): Noria<T, *> {
  val closure = object : ImplicitClosure<Thunk<T>> {
    override fun apply(frame: Frame): Thunk<T> {
      return Thunk.of(f(frame))
    }
  }
  return evaluate(ImplicitClosureReconciler, closure, Bindings(io.lacuna.bifurcan.Map.from<Any, Any>(rootBindings))) as Noria<T, *>
}

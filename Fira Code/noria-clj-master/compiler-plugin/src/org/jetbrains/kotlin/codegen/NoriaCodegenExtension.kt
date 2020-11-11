package org.jetbrains.kotlin.codegen

import org.jetbrains.kotlin.backend.jvm.intrinsics.IrIntrinsicFunction
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.codegen.intrinsics.IntrinsicCallable
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameOrNull
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperInterfaces
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.typeUtil.representativeUpperBound
import org.jetbrains.org.objectweb.asm.Type

val FRAME_INTERNAL_NAME = "noria/OpenFrame"
val FRAME_TYPE: Type = Type.getObjectType(FRAME_INTERNAL_NAME)
val FRAME_INTERFACE_NAME: String = "noria.OpenFrame"
val FRAME_INTERFACE_FQN: FqName = FqName(FRAME_INTERFACE_NAME)

fun toClassDescriptor(type: KotlinType): ClassDescriptor? {
  val descriptor = type.constructor.declarationDescriptor
  return when (descriptor) {
    is ClassDescriptor -> descriptor
    is TypeParameterDescriptor -> toClassDescriptor(descriptor.representativeUpperBound)
    else -> null
  }
}

private fun transitiveInterfaces(cd: ClassDescriptor?): Set<FqName> {
  if (cd == null) {
    return emptySet()
  }
  val res = HashSet<FqName>()
  fun supers(cd: ClassDescriptor) {
    val s = cd.getSuperInterfaces()
    val name = cd.fqNameOrNull()
    if (name != null) {
      res.add(name)
    }
    s.forEach { i -> supers(i) }
  }
  supers(cd)
  return res
}

private fun isFrameType(recType: KotlinType): Boolean {
  return transitiveInterfaces(toClassDescriptor(recType)).contains(FRAME_INTERFACE_FQN)
}

object NoriaCodegenExtension : ExpressionCodegenExtension {
  var key: Int = 0

  override fun applyFunction(receiver: StackValue, resolvedCall: ResolvedCall<*>, c: ExpressionCodegenExtension.Context): StackValue? {
    val codegen: ExpressionCodegen = c.codegen
    val context = codegen.context
    val fd: FunctionDescriptor = codegen.accessibleFunctionDescriptor(resolvedCall)
    val call = resolvedCall.call
    val superCallTarget: ClassDescriptor? = codegen.getSuperCallTarget(call)
    val fdPrime = context.getAccessorForSuperCallIfNeeded(fd, superCallTarget, codegen.state);
    val callable = codegen.resolveToCallable(fdPrime, superCallTarget != null, resolvedCall)
    val recType = fdPrime.extensionReceiverParameter?.type
    return if (callable !is IntrinsicCallable &&
               callable !is IrIntrinsicFunction &&
               !fd.isInline &&
               recType != null &&
               isFrameType(recType)) {
      StackValue.functionCall(callable.returnType, resolvedCall.resultingDescriptor.original.returnType) { v ->
        val varIndex = codegen.frameMap.enterTemp(FRAME_TYPE)
        val callGenerator = codegen.getOrCreateCallGenerator(resolvedCall)
        val descriptor = resolvedCall.resultingDescriptor
        val argumentGenerator = CallBasedArgumentGenerator(codegen, callGenerator, descriptor.valueParameters, callable.valueParameterTypes)
        val callGeneratorPrime = object : CallGenerator {
          override fun genCallInner(callableMethod: Callable,
                                    resolvedCall: ResolvedCall<*>?,
                                    callDefault: Boolean,
                                    codegen: ExpressionCodegen) {
            v.load(varIndex, FRAME_TYPE)
            v.iconst(key++)
            v.invokeinterface(FRAME_INTERNAL_NAME, "enterScope", "(I)V")
            callGenerator.genCallInner(callableMethod, resolvedCall, callDefault, codegen)
            v.load(varIndex, FRAME_TYPE)
            v.invokeinterface(FRAME_INTERNAL_NAME, "exitScope", "()V")
            codegen.frameMap.leaveTemp(FRAME_TYPE)
          }

          override fun genValueAndPut(valueParameterDescriptor: ValueParameterDescriptor?,
                                      argumentExpression: KtExpression,
                                      parameterType: JvmKotlinType,
                                      parameterIndex: Int) {
            callGenerator.genValueAndPut(valueParameterDescriptor, argumentExpression, parameterType, parameterIndex)
          }

          override fun processAndPutHiddenParameters(justProcess: Boolean) {
            v.dup()
            v.store(varIndex, FRAME_TYPE)
          }

          override fun putCapturedValueOnStack(stackValue: StackValue, valueType: Type, paramIndex: Int) {
            callGenerator.putCapturedValueOnStack(stackValue, valueType, paramIndex)
          }

          override fun putHiddenParamsIntoLocals() {
            callGenerator.putHiddenParamsIntoLocals()
          }

          override fun putValueIfNeeded(parameterType: JvmKotlinType, value: StackValue, kind: ValueKind, parameterIndex: Int) {
            callGenerator.putValueIfNeeded(parameterType, value, kind, parameterIndex)
          }

          override fun reorderArgumentsIfNeeded(actualArgsWithDeclIndex: List<ArgumentAndDeclIndex>, valueParameterTypes: List<Type>) {
            callGenerator.reorderArgumentsIfNeeded(actualArgsWithDeclIndex, valueParameterTypes)
          }

        }
        codegen.invokeMethodWithArguments(callable, resolvedCall, receiver, callGeneratorPrime, argumentGenerator)
      }
    }
    else {
      callable.invokeMethodWithArguments(resolvedCall, receiver, codegen)
    }
  }

}

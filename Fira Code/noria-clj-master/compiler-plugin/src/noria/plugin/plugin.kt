package noria.plugin

import org.jetbrains.kotlin.codegen.NoriaCodegenExtension
import org.jetbrains.kotlin.codegen.extensions.ExpressionCodegenExtension
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

class NoriaCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String = "noria"
  override val pluginOptions: Collection<AbstractCliOption> = emptyList()
}

class NoriaComponentRegistrar : ComponentRegistrar {
  override fun registerProjectComponents(project: MockProject, configuration: CompilerConfiguration) {
    ExpressionCodegenExtension.registerExtension(project, NoriaCodegenExtension)
  }
}


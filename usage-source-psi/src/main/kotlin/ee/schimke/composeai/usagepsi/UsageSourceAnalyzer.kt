package ee.schimke.composeai.usagepsi

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Parses Kotlin source and reports the structure the usage cleaner needs, as JSON. It runs in an
 * isolated classloader holding a Kotlin frontend; JSON means the caller shares no classes with it
 * and the reflective surface is a single `analyze(String): String`. Parse only — no classpath or
 * resolution: setup ~0.5 s per process, ~3 ms per file (`docs/design/PSI_PARSE_SPIKE.md`). It
 * reports facts, never decisions: e.g. it hands over a receiver as an exact string so the caller
 * can check it against the catalog's rules.
 */
// `createForProduction` needs this opt-in since Kotlin 2.4.20; it remains the supported way to
// build a parse-only frontend (see `docs/design/PSI_PARSE_SPIKE.md`).
@OptIn(
  CompilerConfiguration.Internals::class,
  K1Deprecation::class,
  CoreEnvironmentDeprecation::class,
  ExperimentalCompilerApi::class,
)
class UsageSourceAnalyzer : AutoCloseable {

  private val disposable: Disposable = Disposer.newDisposable("usage-source-psi")

  private val factory: PsiFileFactory by lazy {
    val env =
      KotlinCoreEnvironment.createForProduction(
        disposable,
        // Kotlin 2.4.20 reads `configuration.extensionsStorage` while wiring plugin extension
        // points; parsing needs no plugins, so an empty storage suffices (the property also exists
        // in 2.4.10).
        CompilerConfiguration().apply {
          extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
    PsiFileFactory.getInstance(env.project)
  }

  /**
   * [source] → a JSON object of `calls` and `declarations`, or `{"error":"…"}` if unparseable.
   * Never throws across the reflective boundary (the caller couldn't name the exception's class).
   * Offsets are 0-based, end-exclusive character indices into [source].
   */
  fun analyze(source: String): String =
    try {
      val file =
        factory.createFileFromText("Usage.kt", KotlinFileType.INSTANCE, source) as? KtFile
          ?: return json { field("error", "not a Kotlin file") }
      json {
        arrayField("calls", PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)) {
          call(it)
        }
        // Top-level declarations in source order, so the caller doesn't infer boundaries from
        // formatting (over-selecting would merge declarations and misattribute calls).
        arrayField("declarations", file.declarations) { declaration ->
          // `textRange` includes the preceding KDoc and annotations, the widest honest span.
          number("start", declaration.textRange.startOffset)
          number("end", declaration.textRange.endOffset)
        }
      }
    } catch (e: Throwable) {
      json { field("error", e::class.java.simpleName + ": " + (e.message ?: "")) }
    }

  override fun close() = Disposer.dispose(disposable)

  private fun JsonWriter.call(call: KtCallExpression) {
    field("callee", call.calleeExpression?.text ?: "")
    number("start", call.textRange.startOffset)
    number("end", call.textRange.endOffset)

    // The parenthesised argument list, absent entirely for `counted { }` — the shape a regex
    // requiring `(` missed, and the one most scaffolding wrappers are written in.
    val argList = call.valueArgumentList
    number("argsStart", argList?.textRange?.startOffset ?: -1)
    number("argsEnd", argList?.textRange?.endOffset ?: -1)

    val lambda = call.lambdaArguments.firstOrNull()
    number("lambdaStart", lambda?.textRange?.startOffset ?: -1)
    number("lambdaEnd", lambda?.textRange?.endOffset ?: -1)
    val body = lambda?.getLambdaExpression()?.bodyExpression
    number("lambdaBodyStart", body?.textRange?.startOffset ?: -1)
    number("lambdaBodyEnd", body?.textRange?.endOffset ?: -1)

    // The whole `receiver.callee(...)` expression when qualified, so the caller can replace or keep
    // it as one unit rather than guessing where the receiver began.
    val qualified = call.parent as? KtDotQualifiedExpression
    val isSelector = qualified?.selectorExpression === call
    field("receiver", if (isSelector) qualified.receiverExpression.text else null)
    number("qualifiedStart", if (isSelector) qualified.textRange.startOffset else -1)
    number("qualifiedEnd", if (isSelector) qualified.textRange.endOffset else -1)

    // `KtLambdaArgument` is a `KtValueArgument`, so a trailing lambda would take a positional slot;
    // it is reported separately above.
    arrayField(
      "args",
      call.valueArguments.filterIsInstance<KtValueArgument>().filter { it !is KtLambdaArgument },
    ) { arg ->
      field("name", arg.getArgumentName()?.asName?.asString())
      val expr = arg.getArgumentExpression()
      field("text", expr?.text ?: "")
      number("start", expr?.textRange?.startOffset ?: -1)
      number("end", expr?.textRange?.endOffset ?: -1)
    }
  }
}

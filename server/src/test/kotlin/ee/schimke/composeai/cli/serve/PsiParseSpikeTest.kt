@file:OptIn(org.jetbrains.kotlin.CoreEnvironmentDeprecation::class)

package ee.schimke.composeai.cli.serve

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Spike for replacing [PlaygroundSourceCleaner]'s text passes with a real parse. The cleaner scans
 * text because the Kotlin frontend is kept off the CLI's runtime classpath (staged into `lib-bta/`
 * and loaded in an isolated classloader only to compile), and most corpus-found defects were
 * parser-shaped (named-argument binding, receiver chains vs package qualifiers, trailing lambdas,
 * qualified calls). It asks:
 * 1. Does parse-only PSI work with no analysis, classpath or resolution?
 * 2. What does it cost (setup once, then per file)?
 * 3. Does the tree carry what the cleaner needs (call names, argument names, qualifiers)?
 *    `testImplementation` keeps the frontend off the runtime classpath; a real change would load it
 *    via the existing `lib-bta/` classloader ([PlaygroundBtaCompiler.installJars]). Timings are
 *    printed, not asserted, to avoid machine-dependent flakes.
 */
@OptIn(
  CompilerConfiguration.Internals::class,
  K1Deprecation::class,
  CoreEnvironmentDeprecation::class,
  ExperimentalCompilerApi::class,
)
class PsiParseSpikeTest {

  /**
   * Since Kotlin 2.4.20 `createForProduction` reads `configuration.extensionsStorage`, which a bare
   * `CompilerConfiguration()` lacks; parsing needs no plugins, so an empty storage suffices.
   */
  private fun parseOnlyConfiguration(): CompilerConfiguration =
    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() }

  /**
   * Where `scripts/usage-corpus.sh` writes: `<repo>/build/usage-corpus`, not this project's
   * `build/` (tests run from the project directory). Reading the wrong one silently measures other
   * files.
   */
  private fun corpusDir(): File {
    System.getProperty("composeai.usageCorpus.out")
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return File(it)
      }
    val repoRoot = File(repoRoot(), "build/usage-corpus")
    return if (repoRoot.isDirectory) repoRoot else File("build/usage-corpus")
  }

  private fun sampleSources(): Pair<File, List<Pair<String, String>>> {
    val corpus = corpusDir()
    val generated =
      corpus
        .walkTopDown()
        // `source-set-fixture` is another test's scratch tree, not generated usage code. Counting
        // it inflated the measurement by 15 files.
        .onEnter { it.name != "source-set-fixture" }
        .filter { it.isFile && it.extension == "kt" }
        .map { it.name to it.readText() }
        .toList()
    // The fixture stands in when no corpus has been generated on this machine, so the spike still
    // reports something rather than silently measuring nothing — and the printed path says which.
    return if (generated.isEmpty()) File("(built-in fixture)") to listOf("Fixture.kt" to FIXTURE)
    else corpus to generated
  }

  @Test
  fun `parse-only PSI is available, and this is what it costs`() {
    val disposable = Disposer.newDisposable("psi-parse-spike")
    try {
      lateinit var factory: PsiFileFactory
      val setup = measureTime {
        // No classpath, no roots, no analysis: a parser needs none of it. This is the whole claim
        // being tested — the expensive part of the frontend is resolution, not parsing.
        val env =
          KotlinCoreEnvironment.createForProduction(
            disposable,
            parseOnlyConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
          )
        factory = PsiFileFactory.getInstance(env.project)
      }

      val (corpus, sources) = sampleSources()
      var files = 0
      var functions = 0
      var calls = 0
      var namedArgs = 0
      var qualified = 0
      var bytes = 0L

      // Warm up by parsing and walking: PSI is lazy, so parsing alone wouldn't build a tree and the
      // first timed walk would pay the parser's initialisation.
      for ((name, text) in sources) {
        (factory.createFileFromText(name, KotlinFileType.INSTANCE, text) as? KtFile)
          ?.collectDescendantsOfType<KtCallExpression>()
      }
      val parsing = measureTime {
        for ((name, text) in sources) {
          val ktFile =
            factory.createFileFromText(name, KotlinFileType.INSTANCE, text) as? KtFile ?: continue
          files++
          bytes += text.length
          functions += ktFile.collectDescendantsOfType<KtNamedFunction>().size
          val callNodes = ktFile.collectDescendantsOfType<KtCallExpression>()
          calls += callNodes.size
          namedArgs += callNodes.sumOf { call ->
            call.valueArguments.count { it.getArgumentName() != null }
          }
          qualified += ktFile.collectDescendantsOfType<KtDotQualifiedExpression>().size
        }
      }

      println(
        """
        |
        |=== parse-only PSI spike ===
        |  corpus            : ${corpus.absolutePath}
        |  environment setup : $setup   (once per process)
        |  parsed            : $files files, $bytes chars, in $parsing
        |  per file          : ${if (files > 0) parsing / files else parsing}
        |
        |  what the tree carried, which the text passes each had to guess at:
        |    named functions        : $functions
        |    call expressions       : $calls
        |    named arguments        : $namedArgs   (bind by label; positional still needs `params`)
        |    qualified expressions  : $qualified   (receiver vs package, structurally)
        """
          .trimMargin()
      )

      assertTrue(files > 0, "no sources parsed")
      assertTrue(functions > 0, "parsed but found no functions — the tree is not usable")
    } finally {
      Disposer.dispose(disposable)
    }
  }

  /**
   * Every shape the corpus review rounds got wrong, in one file; a parse must distinguish them
   * without rules.
   */
  @Test
  fun `the tree distinguishes the shapes the text passes could not`() {
    val disposable = Disposer.newDisposable("psi-parse-spike-shapes")
    try {
      val env =
        KotlinCoreEnvironment.createForProduction(
          disposable,
          parseOnlyConfiguration(),
          EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
      val ktFile =
        PsiFileFactory.getInstance(env.project)
          .createFileFromText("Shapes.kt", KotlinFileType.INSTANCE, FIXTURE) as KtFile

      val calls = ktFile.collectDescendantsOfType<KtCallExpression>()
      val byName = calls.groupBy { it.calleeExpression?.text }

      // 1. A parse gives the argument's label and nothing more, so `UsageRules.Scaffold.params`
      //    survives: positional calls carry no label (only the signature knows the slot), and a
      //    labelled call still needs a name→index map for templates like `plain = "$1"`. It would
      //    only retire if templates used named placeholders like `${default}`.
      val overrides = byName["previewOverrideString"].orEmpty()
      val labelled = overrides.filter { call ->
        call.valueArguments.any { it.getArgumentName() != null }
      }
      val positional = overrides - labelled.toSet()
      val defaults = labelled.mapNotNull { call ->
        call.valueArguments
          .firstOrNull { it.getArgumentName()?.asName?.asString() == "default" }
          ?.getArgumentExpression()
          ?.text
      }
      println("previewOverrideString: ${labelled.size} labelled → defaults $defaults")
      println(
        "previewOverrideString: ${positional.size} positional → " +
          "${positional.map { c -> c.valueArguments.map { it.getArgumentExpression()?.text } }}" +
          " (no label; still needs the callee's parameter list)"
      )
      assertTrue(defaults == listOf("\"Shopping\""), "named-argument binding failed: $defaults")
      // Two of them: the bare `("subtitle", "Basket")` and the package-qualified `("k", "v")`.
      assertTrue(positional.size == 2, "expected two positional calls, got ${positional.size}")
      assertTrue(
        positional.all { call -> call.valueArguments.all { it.getArgumentName() == null } },
        "a positional call must carry no argument names — that is the whole point",
      )

      // 2. A trailing-lambda call is a call, with or without parentheses; each of the three forms
      //    is identified structurally.
      val tally = byName["counted"].orEmpty()
      val trailingOnly = tally.filter {
        it.lambdaArguments.isNotEmpty() && it.valueArgumentList == null
      }
      val parenthesised = tally.filter { it.valueArgumentList != null }
      val qualifiedTrailing = trailingOnly.filter { it.parent is KtDotQualifiedExpression }
      println(
        "counted: ${tally.size} calls — ${trailingOnly.size} trailing-lambda " +
          "(${qualifiedTrailing.size} of them qualified), ${parenthesised.size} parenthesised"
      )
      assertTrue(tally.size == 3, "expected 3 counted calls, got ${tally.size}")
      assertTrue(trailingOnly.size == 2, "trailing-lambda calls: ${trailingOnly.size}")
      assertTrue(parenthesised.size == 1, "parenthesised calls: ${parenthesised.size}")
      assertTrue(
        qualifiedTrailing.size == 1,
        "qualified trailing-lambda: ${qualifiedTrailing.size}",
      )

      // 3. A qualified call yields its receiver as a whole expression, so the package allow-list
      //    becomes an exact lookup. The classification runs here, since both forms are the same
      //    `KtDotQualifiedExpression` shape and only the allow-list distinguishes them.
      val scaffoldPackages = setOf("ee.schimke.composeai.overrides")
      val qualifiers =
        ktFile.collectDescendantsOfType<KtDotQualifiedExpression>().mapNotNull { dq ->
          val callee = (dq.selectorExpression as? KtCallExpression)?.calleeExpression?.text
          if (callee == null) null else dq.receiverExpression.text to callee
        }
      val (packageQualified, receiverChains) = qualifiers.partition { it.first in scaffoldPackages }
      println("package-qualified (unqualify): $packageQualified")
      println("receiver chains (leave alone): $receiverChains")
      assertTrue(
        packageQualified.map { it.second } == listOf("previewOverrideString"),
        "allow-list did not classify the package-qualified call: $packageQualified",
      )
      assertTrue(
        receiverChains.map { it.first } == listOf("state.metrics"),
        "allow-list wrongly classified a receiver chain: $receiverChains",
      )

      // 4. Destructuring (the `toggleable` / `editable` gap), read off the tree, not `ktFile.text`
      //    (which just echoes the input).
      val destructuring = ktFile.collectDescendantsOfType<KtDestructuringDeclaration>()
      val entries = destructuring.map { d -> d.entries.map { it.name } }
      val initialisers = destructuring.map { it.initializer?.text }
      println("destructuring declarations: $entries ← $initialisers")
      assertTrue(
        entries == listOf(listOf("checked", "onCheckedChange")),
        "destructuring entries not exposed by PSI: $entries",
      )
      assertTrue(
        initialisers.single()?.startsWith("toggleable(") == true,
        "destructuring initialiser not exposed: $initialisers",
      )
    } finally {
      Disposer.dispose(disposable)
    }
  }

  /**
   * The deployment route: load the parser from `lib-bta/` through an isolated classloader, as
   * `PlaygroundBtaCompiler` loads the compiler, proving the frontend never needs the CLI's runtime
   * classpath. Jars come from the `composePreviewBta` configuration, so this runs in plain
   * `:cli:test`.
   */
  @Test
  fun `the parser loads from the isolated lib-bta classloader`() {
    // From `composePreviewBta`, the same artifacts staged into `lib-bta/`; the installed directory
    // doesn't exist on clean CI checkouts.
    val jars =
      System.getProperty("composeai.libBtaJars")
        .orEmpty()
        .split(File.pathSeparator)
        .filter { it.endsWith(".jar") }
        .map(::File)
        .filter { it.isFile }
    assertTrue(jars.isNotEmpty(), "composeai.libBtaJars was not forwarded by the build")

    // Platform parent, so nothing resolves against the CLI's own classpath: if this works, the
    // frontend is reachable without ever being a dependency of the serve host.
    val loader =
      URLClassLoader(
        jars.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
    var callees = emptyList<String>()
    val elapsed = measureTime {
      val disposer = loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer")
      val disposable =
        disposer.getMethod("newDisposable", String::class.java).invoke(null, "lib-bta-spike")
      val envClass = loader.loadClass("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
      val companion = envClass.getField("Companion").get(null)
      val config =
        loader
          .loadClass("org.jetbrains.kotlin.config.CompilerConfiguration")
          .getDeclaredConstructor()
          .newInstance()
      // Same Kotlin 2.4.20 requirement as `parseOnlyConfiguration()` above, reached reflectively so
      // the storage class comes from the isolated loader rather than this test's own.
      val storage =
        loader
          .loadClass(
            "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar\u0024ExtensionStorage"
          )
          .getDeclaredConstructor()
          .newInstance()
      loader
        .loadClass("org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt")
        .getMethod(
          "setExtensionsStorage",
          loader.loadClass("org.jetbrains.kotlin.config.CompilerConfiguration"),
          loader.loadClass(
            "org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar\u0024ExtensionStorage"
          ),
        )
        .invoke(null, config, storage)
      val jvmConfigFiles =
        loader
          .loadClass("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles")
          .getField("JVM_CONFIG_FILES")
          .get(null)
      // By exact signature: `Class.getMethods()` order is unspecified, so a predicate could pick a
      // different overload per run.
      val create =
        companion.javaClass.getMethod(
          "createForProduction",
          loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.Disposable"),
          loader.loadClass("org.jetbrains.kotlin.config.CompilerConfiguration"),
          loader.loadClass("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles"),
        )
      val env = create.invoke(companion, disposable, config, jvmConfigFiles)
      val project = env.javaClass.getMethod("getProject").invoke(env)

      val fileTypeClass = loader.loadClass("org.jetbrains.kotlin.idea.KotlinFileType")
      val fileType = fileTypeClass.getField("INSTANCE").get(null)
      val factoryClass = loader.loadClass("org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory")
      val factory =
        factoryClass
          .getMethod(
            "getInstance",
            loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.project.Project"),
          )
          .invoke(null, project)
      val createFile =
        factoryClass.getMethod(
          "createFileFromText",
          String::class.java,
          loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType"),
          CharSequence::class.java,
        )
      val psi = createFile.invoke(factory, "Shapes.kt", fileType, FIXTURE)

      // Walk the tree rather than reading `getText()`, which can return the original buffer without
      // building a tree.
      val treeUtil = loader.loadClass("org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil")
      val findChildren =
        treeUtil.getMethod(
          "findChildrenOfType",
          loader.loadClass("org.jetbrains.kotlin.com.intellij.psi.PsiElement"),
          Class::class.java,
        )
      val callClass = loader.loadClass("org.jetbrains.kotlin.psi.KtCallExpression")
      @Suppress("UNCHECKED_CAST")
      val callNodes = findChildren.invoke(null, psi, callClass) as Collection<Any>
      callees = callNodes.map { call ->
        val callee = callClass.getMethod("getCalleeExpression").invoke(call)
        callee?.javaClass?.getMethod("getText")?.invoke(callee) as? String ?: "?"
      }

      disposer
        .getMethod(
          "dispose",
          loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.Disposable"),
        )
        .invoke(null, disposable)
    }

    println(
      "lib-bta isolated load + parse: $elapsed over ${jars.size} jars; " +
        "${callees.size} call nodes walked: ${callees.distinct()}"
    )
    assertTrue(
      callees.containsAll(listOf("previewOverrideString", "counted", "toggleable")),
      "no Kotlin AST through the isolated loader; callees were $callees",
    )
  }

  private companion object {
    /** Not a tidy sample: every one of these lines is a defect the corpus found. */
    val FIXTURE =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun Shapes() {
        Text(previewOverrideString(key = "title", default = "Shopping"))
        Text(previewOverrideString("subtitle", "Basket"))
        counted { }
        counted("label")
        ee.schimke.composeai.overrides.previewOverrideString("k", "v")
        state.metrics.counted { }
        val (checked, onCheckedChange) = toggleable("on", true)
      }
      """
        .trimIndent()
  }
}

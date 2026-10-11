import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink

plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
}

val materialIconGeneratorClasspath =
  configurations.create("materialIconGeneratorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }

dependencies {
  materialIconGeneratorClasspath(libs.material.icons.extended.desktop)
  materialIconGeneratorClasspath(libs.material.icons.core.desktop)
}

val generateMaterialIconInventory =
  tasks.register<GenerateMaterialIconInventory>("generateMaterialIconInventory") {
    group = "code generation"
    description = "Indexes every vector in the shipped Compose Material Icons artifact."
    iconClasspath.from(materialIconGeneratorClasspath)
    output.set(layout.buildDirectory.file("generated/materialIcons/material-icon-inventory.tsv"))
  }

val generateMaterialIconUiSources =
  tasks.register<GenerateMaterialIconUiSources>("generateMaterialIconUiSources") {
    group = "code generation"
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    outputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder"))
  }

val generateMaterialIconExportSource =
  tasks.register<GenerateMaterialIconExportSource>("generateMaterialIconExportSource") {
    group = "code generation"
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    outputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder-export"))
  }

val m3MaterialIconCatalogFixture =
  layout.projectDirectory.file("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json")
val generateMaterialIconCatalogFixture =
  tasks.register<GenerateMaterialIconCatalogFixture>("generateMaterialIconCatalogFixture") {
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    catalog.set(m3MaterialIconCatalogFixture)
    output.set(
      layout.buildDirectory.file("generated/materialIcons/m3-catalog-capabilities-v1.json")
    )
  }

// `wear-m3-capabilities-v1.json` is absent on purpose: it is a golden owned by
// `SynthesisedCatalogGoldenTest`, and two writers with different byte layouts could never both
// satisfy `VerifyMatchingFile`. `m3-catalog-capabilities-v1.json` has no golden, so it is updated
// here.
tasks.register<UpdateMaterialIconCatalogFixtures>("updateMaterialIconCatalogFixture") {
  group = "code generation"
  description = "Updates the m3 icon allowlist from the shipped Material Icons artifact."
  generatedM3.set(generateMaterialIconCatalogFixture.flatMap { it.output })
  checkedInM3.set(m3MaterialIconCatalogFixture)
}

val checkMaterialIconCatalogFixture =
  tasks.register<VerifyMatchingFile>("checkMaterialIconCatalogFixture") {
    checkedIn.set(m3MaterialIconCatalogFixture)
    expected.set(generateMaterialIconCatalogFixture.flatMap { it.output })
  }

tasks.named("check") { dependsOn(checkMaterialIconCatalogFixture) }

tasks.named("check") {
  group = "verification"
  // UI-builder modules live in yschimke/compose-ui-builder and are verified by its CI, not
  // `:server:check`.
  dependsOn(
    ":server:check",
    ":mcp:check",
    ":native-catalog-m3:check",
    ":usage-source-psi:check",
    ":wasm-ui:check",
  )
}

// ---------------------------------------------------------------------------
// One Kotlin/Wasm executable links at a time.
// ---------------------------------------------------------------------------
//
// `kotlin.daemon.jvmargs=-Xmx6g` above is sized for ONE ir2wasm pass. There is one Kotlin daemon
// for the whole build, `org.gradle.parallel=true`, and twenty-six of these link tasks across seven
// modules — so `check` runs several of them inside that single 6 GB heap and it dies with "Not
// enough memory to run compilation".
//
// That is what took `main` red from #710 (the complete Material icon inventory) through #711's
// 4g -> 6g bump, which raised the ceiling without changing how many passes share it. The failure
// moved between `:ui-builder:compileTestDevelopmentExecutableKotlinWasmJs` and
// `:ui-builder-renderer:compileDevelopmentExecutableKotlinWasmJs` run to run — whichever pair
// happened to overlap, which is the signature of contention rather than of one task being too big.
//
// The evidence that a single pass does fit: the `visual-harness` job compiles four of these
// executables at the same 6 GB and passes, because it runs `--no-daemon --no-parallel
// --max-workers=1`. And `:ui-builder:compileTestDevelopmentExecutableKotlinWasmJs` — the one CI
// dies on — links in 5m13s at 6 GB on a 15 GB machine when nothing else shares the daemon.
//
// So the constraint is stated where it is true, rather than by serialising a whole CI job or by
// raising a number that has to be raised again the next time the icon set grows. A shared build
// service with `maxParallelUsages = 1` is Gradle's way to say "these tasks must not run
// concurrently with each other"; everything else in the build stays parallel.
//
// Matched by TASK TYPE, not by name. A name pattern is the same hand-kept list `ktfmtCheckAll`
// was, one rename away from silently matching nothing and letting the OOM back in with no test to
// notice — and there is no cheap test for "CI has enough memory". `KotlinJsIrLink` is the type
// Kotlin gives every executable link; if it is renamed the build stops compiling here instead.
abstract class WasmLinkLane : BuildService<BuildServiceParameters.None>

val wasmLinkLane =
  gradle.sharedServices.registerIfAbsent("wasmLinkLane", WasmLinkLane::class) {
    maxParallelUsages.set(1)
  }

subprojects {
  tasks.withType<KotlinJsIrLink>().configureEach { usesService(wasmLinkLane) }
}

// The formatting aggregates — DERIVED, never listed, for the reason the Maven set below is.
//
// They were hand-kept lists and had drifted by four modules: `mcp`, `native-catalog-m3`,
// `ui-builder-artwork` and `ui-builder-export`. `AGENTS.md` tells every contributor and every
// agent to run `ktfmtCheckAll` before committing, CI's `check` reaches those four anyway, so the
// gate that was supposed to save a round trip was the thing costing one — twice in one session on
// the same file. A list of modules that has to be edited when a module is added is not a gate,
// it is a reminder.
//
// Keyed on the ktfmt plugin rather than on the module list: a module formats if and only if it
// applies the plugin, which is the same fact from the only place that states it.
val ktfmtCheckAll = tasks.register("ktfmtCheckAll") { group = "verification" }

val ktfmtFormatAll = tasks.register("ktfmtFormat") { group = "formatting" }

subprojects {
  plugins.withId("com.ncorti.ktfmt.gradle") {
    ktfmtCheckAll.configure { dependsOn(tasks.named("ktfmtCheck")) }
    ktfmtFormatAll.configure { dependsOn(tasks.named("ktfmtFormat")) }
  }
}

// No Maven publishing: this repository ships only the GitHub release tarballs built by
// `:server:distTar` and `:mcp:distTar`, which `compose-preview serve`/`browse`/`ui-builder`/`mcp
// serve` launch. The editor archive is compose-ui-builder's own release asset, resolved by
// coordinate.

// One compile-time choice for the editor/server and the independently published MCP adapter.
// No environment, URL or request parameter can turn a released build's feature set on.
val remoteComposeAuthoring = providers.gradleProperty("uiBuilderRemoteCompose").orElse("false").map {
  require(it == "true" || it == "false") { "uiBuilderRemoteCompose must be true or false" }
  it.toBooleanStrict()
}
listOf(
  Triple("generateUiBuilderBuildFeatures", "ee.schimke.composeai.uibuilder", "UiBuilderBuildFeatures"),
  Triple("generateMcpBuildFeatures", "ee.schimke.composeai.mcp", "McpBuildFeatures"),
).forEach { (taskName, packageName, objectName) ->
  tasks.register(taskName) {
    val enabled = remoteComposeAuthoring
    val output = layout.buildDirectory.dir("generated/$taskName")
    inputs.property("uiBuilderRemoteCompose", enabled)
    outputs.dir(output)
    doLast {
      val directory = output.get().asFile.apply { mkdirs() }
      directory.resolve("$objectName.kt").writeText(
        "package $packageName\n\n" +
          "/** Build-time feature selection. Enable with -PuiBuilderRemoteCompose=true. */\n" +
          "object $objectName {\n  const val remoteCompose: Boolean = ${enabled.get()}\n}\n"
      )
    }
  }
}

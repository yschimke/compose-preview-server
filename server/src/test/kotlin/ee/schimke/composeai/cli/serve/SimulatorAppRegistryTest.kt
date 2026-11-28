package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.SimulatorAppRegistry.Result
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SimulatorAppRegistryTest {
  @TempDir lateinit var dir: Path

  private val registry by lazy { SimulatorAppRegistry(dir.toFile(), clock = { 1_000 }, onLog = {}) }

  @Test
  fun `an app's modes follow its APKs`() {
    val phone =
      assertIs<Result.Ok>(
        registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk()),
      )
    assertEquals(listOf("phone"), phone.app.modes)
    assertEquals(SimulatorAppConfig(), phone.app.config, "a new app starts with the safe defaults")
    val both =
      assertIs<Result.Ok>(
        registry.installApk("demo", SimulatorDevice.WATCH, SimulatorFixtures.watchApk()),
      )
    assertEquals(listOf("phone", "watch", "paired"), both.app.modes)
    assertTrue(
      both.app.warnings.single().contains("same package"),
      "different packages cannot pair",
    )
    registry.installApk(
      "same",
      SimulatorDevice.PHONE,
      SimulatorFixtures.phoneApk("com.example.app"),
    )
    val same =
      assertIs<Result.Ok>(
        registry.installApk(
          "same",
          SimulatorDevice.WATCH,
          SimulatorFixtures.watchApk("com.example.app"),
        ),
      )
    assertEquals(emptyList(), same.app.warnings)
    assertEquals(
      mapOf("phone" to "com.example.phone", "watch" to "com.example.watch"),
      both.app.apks.associate { it.device to it.packageName },
    )
    val watchOnly = assertIs<Result.Ok>(registry.removeApk("demo", SimulatorDevice.PHONE))
    assertEquals(listOf("watch"), watchOnly.app.modes)
  }

  @Test
  fun `a slot refuses the other form factor and non-APKs`() {
    assertIs<Result.Invalid>(
      registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.watchApk()),
    )
    assertIs<Result.Invalid>(
      registry.installApk("demo", SimulatorDevice.WATCH, SimulatorFixtures.phoneApk()),
    )
    assertIs<Result.Invalid>(
      registry.installApk(
        "demo",
        SimulatorDevice.PHONE,
        SimulatorFixtures.apk("a.b", watch = false, dex = false),
      ),
    )
    assertIs<Result.Invalid>(
      registry.installApk("demo", SimulatorDevice.PHONE, "not a zip".toByteArray()),
    )
    assertIs<Result.Invalid>(
      registry.installApk("../etc", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk()),
    )
    assertNull(registry.get("demo"))
  }

  @Test
  fun `a replaced APK is kept until the next replacement`() {
    registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk("a.one"))
    val first = assertNotNull(registry.apkFile("demo", SimulatorDevice.PHONE))
    registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk("a.two"))
    val second = assertNotNull(registry.apkFile("demo", SimulatorDevice.PHONE))
    assertTrue(first.isFile, "a session started on the first build keeps its file")
    registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk("a.three"))
    assertTrue(!first.isFile && second.isFile)
  }

  @Test
  fun `configs are validated`() {
    assertIs<Result.Invalid>(registry.putConfig("demo", SimulatorAppConfig(access = "everyone")))
    assertIs<Result.Invalid>(registry.putConfig("demo", SimulatorAppConfig(network = "lan")))
    assertIs<Result.Invalid>(
      registry.putConfig("demo", SimulatorAppConfig(embed = SimulatorEmbedPolicy(enabled = true))),
    )
    assertIs<Result.Invalid>(
      registry.putConfig(
        "demo",
        SimulatorAppConfig(
          embed = SimulatorEmbedPolicy(enabled = true, origins = listOf("https://*.example.com")),
        ),
      ),
    )
    assertIs<Result.Invalid>(
      registry.putConfig(
        "demo",
        SimulatorAppConfig(
          embed =
            SimulatorEmbedPolicy(enabled = true, origins = listOf("https://example.com/docs")),
        ),
      ),
    )
    val ok =
      assertIs<Result.Ok>(
        registry.putConfig(
          "demo",
          SimulatorAppConfig(
            label = "Demo",
            embed =
              SimulatorEmbedPolicy(
                enabled = true,
                origins = listOf("https://docs.example.com", "http://localhost:3000"),
              ),
          ),
        ),
      )
    assertEquals(emptyList(), ok.app.modes, "configured but no APK yet")
  }

  @Test
  fun `delete removes everything`() {
    registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk())
    assertIs<Result.Ok>(registry.delete("demo"))
    assertTrue(!File(dir.toFile(), "demo").exists())
    assertEquals(Result.NotFound, registry.delete("demo"))
  }
}

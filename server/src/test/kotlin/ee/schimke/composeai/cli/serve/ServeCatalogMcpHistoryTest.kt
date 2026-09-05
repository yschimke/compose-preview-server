package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `history_list` over the catalog MCP, in each of the three shapes a deployment can honestly
 * produce.
 *
 * The modes are not interchangeable and the distinction is the point: a hosted catalog's timeline
 * lives on its delivery branch and this server does not hold it, while project mode derives one
 * from the checkout. Reporting either as an empty list would read as "this preview has never
 * changed", which is a different and wrong claim.
 */
class ServeCatalogMcpHistoryTest {

  // The bundle addresses its render as `previews/<id>.png`, so the id has to be filename-safe;
  // `baselines.json` then keys the same id to the delivery-branch path the timeline is read from.
  private val previewId = "ProfilePreview"
  private val sha = "df4aa9c00fcc8b1747e159b71d3fbc75cdc27b80"
  private val blobA = "a".repeat(40)
  private val blobB = "b".repeat(40)
  private val resolvedRef = "d".repeat(40)

  private val baselines =
    """{"$previewId": {"module": "samples:compose-m3", "renderBasename": "ProfilePreview.png"}}"""

  /** `git log --format=%x01%H%x1f%aI%x1f%s` — see [PreviewHistory.logArgs]. */
  private fun header(commit: String, date: String, subject: String) =
    "\u0001$commit\u001F$date\u001F$subject"

  private fun raw(blob: String, path: String) = ":100644 100644 ${"0".repeat(40)} $blob M\t$path"

  private val renderPath = "renders/samples:compose-m3/ProfilePreview.png"

  private val log =
    listOf(
        header(sha, "2026-05-22T11:08:37+00:00", "Update preview baselines from 57ac24f3"),
        raw(blobA, renderPath),
        header(
          "8b9f6f2bc953756edcb13963e09cd57c54866570",
          "2026-05-07T08:34:51+00:00",
          "Update preview baselines from cf69a4a0",
        ),
        raw(blobB, renderPath),
      )
      .joinToString("\n")

  private class FakeGit(
    private val files: Map<String, String>,
    private val log: String,
    private val resolvedRef: String,
  ) : GitRunner {
    override fun run(workdir: File, args: List<String>): GitResult =
      when {
        args.firstOrNull() == "rev-parse" -> GitResult(0, "$resolvedRef\n")
        args.firstOrNull() == "show" ->
          files[args.last()]?.let { GitResult(0, it) } ?: GitResult(128, "")
        args.contains("log") -> GitResult(0, log)
        else -> GitResult(1, "")
      }
  }

  private fun png(marker: Byte): ByteArray =
    byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), marker)

  private fun projectHistory(logText: String = log, blobs: Map<String, ByteArray>) =
    ServeProjectHistory(
      repoRoot = File("/repo"),
      git = FakeGit(mapOf("$resolvedRef:baselines.json" to baselines), logText, resolvedRef),
      readBlobBytes = { _, s -> blobs[s] },
      now = { 0L },
    )

  /** A minimal published bundle carrying one preview, optionally with delivery provenance. */
  private fun bundleHost(provenance: ServeWeb.CatalogProvenance? = null): ServeBundleHost {
    val dir = Files.createTempDirectory("history-bundle").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png(1))
    File(dir, "previews.json")
      .writeText(
        """{"module":":m","variant":"debug","previews":[
             {"id":"$previewId","functionName":"$previewId","className":"ProfileKt"}]}"""
      )
    return ServeBundleHost(dir, label = "m3", provenance = provenance)
  }

  private fun call(host: ServeHost, projectHistory: ServeProjectHistory? = null): JsonObject {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("m3", host = host)
    val mcp = ServeCatalogMcp(registry, Semaphore(1), projectHistory = projectHistory)
    val request =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"history_list",
              "arguments":{"catalog":"m3","previewId":"$previewId"}}}"""
        )
        .jsonObject
    return requireNotNull(
      runBlocking {
        mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
      }
        .body
    )
  }

  private fun JsonObject.payload(): JsonObject {
    val text =
      this["result"]!!
        .jsonObject["content"]!!
        .jsonArray[0]
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    return Json.parseToJsonElement(text).jsonObject
  }

  @Test
  fun `a delivery-branch catalog points at the manifest it publishes`() {
    // The hosted shape. This server does not hold the timeline: the manifest is a whole-catalog
    // document on a branch that moves independently of this process, so the honest answer is where
    // to fetch it rather than a cache of it with its own staleness.
    val host =
      bundleHost(ServeWeb.CatalogProvenance(repo = "yschimke/design", branch = "design/m3"))
    val body = call(host).payload()

    assertEquals("published", body["mode"]!!.jsonPrimitive.content)
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/design/design/m3/history.json",
      body["manifestUrl"]!!.jsonPrimitive.content,
    )
    assertEquals("yschimke/design", body["repo"]!!.jsonPrimitive.content)
    assertTrue(
      body["renderUrlTemplate"]!!.jsonPrimitive.content.contains("{commit}"),
      "an agent needs to address a single historical render, not just the manifest",
    )
  }

  @Test
  fun `an uploaded bundle says why there is no timeline rather than returning an empty one`() {
    val body = call(bundleHost()).payload()

    assertEquals("none", body["mode"]!!.jsonPrimitive.content)
    assertTrue(body["reason"]!!.jsonPrimitive.content.contains("uploaded bundle"))
    assertTrue(
      body["versions"] == null,
      "an empty version list would read as a preview that has never changed",
    )
  }

  @Test
  fun `project mode returns the local timeline with addressable renders`() {
    val history = projectHistory(blobs = mapOf(blobA to png(1), blobB to png(2)))
    val body = call(bundleHost(), projectHistory = history).payload()

    assertEquals("local", body["mode"]!!.jsonPrimitive.content)
    val versions = body["versions"]!!.jsonArray
    assertEquals(2, versions.size)
    assertEquals(
      "/history/render/$blobA.png",
      versions[0].jsonObject["renderUrl"]!!.jsonPrimitive.content,
      "each version is addressed by blob sha through this server's content-addressed lane",
    )
    assertTrue(versions[0].jsonObject["commit"] != null)
    assertTrue(versions[0].jsonObject["date"] != null)
  }

  @Test
  fun `delivery provenance wins over a local checkout`() {
    // Mutually exclusive in the viewer by construction, and the precedence matters: a catalog
    // fetched from a delivery branch has already published what it rendered, and that — not
    // whatever this box's clone happens to contain — is the truth about it.
    val history = projectHistory(blobs = mapOf(blobA to png(1), blobB to png(2)))
    val host =
      bundleHost(ServeWeb.CatalogProvenance(repo = "yschimke/design", branch = "design/m3"))
    val body = call(host, projectHistory = history).payload()

    assertEquals("published", body["mode"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a preview with one render reports why rather than an empty timeline`() {
    val single =
      listOf(header(sha, "2026-05-22T11:08:37+00:00", "one"), raw(blobA, renderPath))
        .joinToString("\n")
    val history = projectHistory(logText = single, blobs = mapOf(blobA to png(1)))
    val body = call(bundleHost(), projectHistory = history).payload()

    assertEquals("local", body["mode"]!!.jsonPrimitive.content)
    assertEquals(0, body["versions"]!!.jsonArray.size)
    assertTrue(body["reason"]!!.jsonPrimitive.content.contains("fewer than two"))
  }
}

package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.Base64
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

  private fun call(
    host: ServeHost,
    projectHistory: ServeProjectHistory? = null,
    tool: String = "history_list",
    extraArgs: String = "",
  ): JsonObject {
    val registry = ServeSessionRegistry(open = { null })
    registry.register("m3", host = host)
    val mcp = ServeCatalogMcp(registry, Semaphore(1), projectHistory = projectHistory)
    val request =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool",
              "arguments":{"catalog":"m3","previewId":"$previewId"$extraArgs}}}"""
        )
        .jsonObject
    return requireNotNull(
      runBlocking {
        mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
      }
        .body
    )
  }

  private fun JsonObject.isError(): Boolean =
    this["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.content == "true"

  private fun JsonObject.errorText(): String =
    this["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

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

  // ---- published mode carrying the publisher's manifest -----------------------------------------

  private val newest =
    PreviewHistoryManifest.ManifestVersion(
      blob = blobA,
      commit = "1".repeat(40),
      date = "2026-08-27T10:58:22Z",
      sourceSha = "2ef7b877",
      commits = 1,
    )

  private val older =
    PreviewHistoryManifest.ManifestVersion(
      blob = blobB,
      commit = "2".repeat(40),
      date = "2026-08-27T07:18:15Z",
      commits = 1,
    )

  private fun manifest(
    versions: List<PreviewHistoryManifest.ManifestVersion> = listOf(newest, older),
    unstable: Boolean = false,
  ) =
    PreviewHistoryManifest.Manifest(
      generatedFrom = "3".repeat(40),
      previews =
        mapOf(
          previewId to
            PreviewHistoryManifest.PreviewTimeline(
              path = "images/profile/ideal__default__dark.png",
              versions = versions,
              observations = versions.size,
              unstable = unstable,
              flapCount = if (unstable) 3 else 0,
            )
        ),
    )

  private fun publishedHost(
    indexed: PreviewHistoryManifest.Manifest? = manifest(),
    pinnedRenders: Map<String, ByteArray> = mapOf(newest.commit to png(7)),
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("history-published").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/$previewId.png").writeBytes(png(1))
    File(dir, "previews.json")
      .writeText(
        """{"module":":m","variant":"debug","previews":[
             {"id":"$previewId","functionName":"$previewId","className":"ProfileKt"}]}"""
      )
    return ServeBundleHost(
      dir,
      label = "m3",
      provenance =
        ServeWeb.CatalogProvenance(
          repo = "yschimke/design",
          branch = "design/m3",
          commit = "4".repeat(40),
        ),
      indexedPreviewHistory = indexed,
      fetchPinnedAsset = { commit, _ -> pinnedRenders[commit] },
    )
  }

  @Test
  fun `a published catalog answers with the slice it already holds`() {
    // The whole point of step 2: the load already fetched history.json from the same immutable tree
    // as catalog.json, so sending the caller to re-fetch a 1 MB whole-catalog document to read one
    // 497-byte row was pure overfetch.
    val body = call(publishedHost()).payload()

    assertEquals("published", body["mode"]!!.jsonPrimitive.content)
    assertEquals("4".repeat(40), body["pinnedCommit"]!!.jsonPrimitive.content)
    val versions = body["versions"]!!.jsonArray
    assertEquals(2, versions.size)
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/design/${newest.commit}/images/profile/ideal__default__dark.png",
      versions[0].jsonObject["renderUrl"]!!.jsonPrimitive.content,
      "each version carries the URL serving those exact bytes; the caller never joins commit to path",
    )
    assertEquals(false, body["unstable"]!!.jsonPrimitive.content.toBoolean())
    // The pointer stays, for a caller that wants the whole catalog's timeline.
    assertTrue(body["manifestUrl"] != null)
  }

  @Test
  fun `a publisher shipping no manifest still gets the URL it had before`() {
    val body = call(publishedHost(indexed = null)).payload()

    assertEquals("published", body["mode"]!!.jsonPrimitive.content)
    assertTrue(body["versions"] == null)
    assertTrue(body["reason"]!!.jsonPrimitive.content.contains("no history.json"))
    assertTrue(body["manifestUrl"] != null, "the degraded path is the old behaviour, not an error")
  }

  // ---- history_diff -----------------------------------------------------------------------------

  @Test
  fun `diff defaults to the two newest versions`() {
    val body = call(publishedHost(), tool = "history_diff").payload()

    assertEquals(newest.commit, body["to"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
    assertEquals(older.commit, body["from"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
    assertEquals(true, body["changed"]!!.jsonPrimitive.content.toBoolean())
    assertEquals(0, body["versionsBetween"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun `diff of a version against itself reports no change`() {
    val body =
      call(
          publishedHost(),
          tool = "history_diff",
          extraArgs = ""","from":"${newest.commit}","to":"${newest.commit}"""",
        )
        .payload()

    assertEquals(false, body["changed"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `diff warns when the preview is unstable`() {
    // The signal that makes this worth having: on a nondeterministic preview a byte difference is
    // not evidence of a real change, which is exactly what flake triage has to establish.
    val body =
      call(publishedHost(indexed = manifest(unstable = true)), tool = "history_diff").payload()

    assertEquals(true, body["unstable"]!!.jsonPrimitive.content.toBoolean())
    assertTrue(body["note"]!!.jsonPrimitive.content.contains("not evidence of a real change"))
  }

  @Test
  fun `diff refuses a preview with a single recorded render`() {
    val body =
      call(publishedHost(indexed = manifest(versions = listOf(newest))), tool = "history_diff")

    assertTrue(body.isError())
    assertTrue(body.errorText().contains("a diff needs two"), body.errorText())
  }

  @Test
  fun `diff refuses a commit the timeline does not name`() {
    val body = call(publishedHost(), tool = "history_diff", extraArgs = ""","to":"deadbeef"""")

    assertTrue(body.isError())
    assertTrue(body.errorText().contains("no recorded render at commit"), body.errorText())
  }

  // ---- history_read -----------------------------------------------------------------------------

  @Test
  fun `read returns the pixels published at a commit`() {
    val body =
      call(publishedHost(), tool = "history_read", extraArgs = ""","commit":"${newest.commit}"""")
    val content = body["result"]!!.jsonObject["content"]!!.jsonArray

    assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(
      Base64.getEncoder().encodeToString(png(7)),
      content[0].jsonObject["data"]!!.jsonPrimitive.content,
      "the bytes served are the ones the branch published at that commit, not today's render",
    )
  }

  @Test
  fun `read accepts a commit prefix`() {
    val body =
      call(
        publishedHost(),
        tool = "history_read",
        extraArgs = ""","commit":"${newest.commit.take(8)}"""",
      )

    assertTrue(!body.isError(), "a commit prefix must resolve to the version it uniquely names")
    assertEquals(
      "image",
      body["result"]!!
        .jsonObject["content"]!!
        .jsonArray[0]
        .jsonObject["type"]!!
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `read refuses a version the timeline does not name`() {
    val body = call(publishedHost(), tool = "history_read", extraArgs = ""","commit":"beefbeef"""")

    assertTrue(body.isError())
    assertTrue(body.errorText().contains("names no recorded render"), body.errorText())
  }

  @Test
  fun `read reports a branch that cannot serve those bytes`() {
    // Distinct from "no such version": the timeline names it, the branch would not hand it over.
    val body =
      call(
        publishedHost(pinnedRenders = emptyMap()),
        tool = "history_read",
        extraArgs = ""","commit":"${newest.commit}"""",
      )

    assertTrue(body.isError())
    assertTrue(body.errorText().contains("no render for"), body.errorText())
  }

  // ---- project mode: diff and read
  // ---------------------------------------------------------------

  // These lanes were covered for `history_list` only. The published path and the local one reach
  // the timeline through completely different code — a parsed manifest held by the bundle host
  // versus JSON re-parsed out of ServeProjectHistory — and only the published half was exercised,
  // so the local half of `historyView` was reachable in production and untested.

  @Test
  fun `diff works against a locally derived timeline`() {
    val history = projectHistory(blobs = mapOf(blobA to png(1), blobB to png(2)))
    val body = call(bundleHost(), projectHistory = history, tool = "history_diff").payload()

    assertEquals("local", body["mode"]!!.jsonPrimitive.content)
    assertEquals(blobA, body["to"]!!.jsonObject["blob"]!!.jsonPrimitive.content)
    assertEquals(blobB, body["from"]!!.jsonObject["blob"]!!.jsonPrimitive.content)
    assertEquals(true, body["changed"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun `a local diff addresses renders through this server, not GitHub`() {
    // The local lane has no repo to build a raw URL from; its renders are served by blob sha out of
    // the checkout. Getting this wrong would hand back a URL that resolves nowhere.
    val history = projectHistory(blobs = mapOf(blobA to png(1), blobB to png(2)))
    val body = call(bundleHost(), projectHistory = history, tool = "history_diff").payload()

    assertEquals(
      "/history/render/$blobA.png",
      body["to"]!!.jsonObject["renderUrl"]!!.jsonPrimitive.content,
    )
    assertTrue(
      !body["from"]!!.jsonObject["renderUrl"]!!.jsonPrimitive.content.contains("githubusercontent")
    )
  }

  @Test
  fun `diff refuses a single-render preview in local mode too`() {
    val single =
      listOf(header(sha, "2026-05-22T11:08:37+00:00", "one"), raw(blobA, renderPath))
        .joinToString("\n")
    val history = projectHistory(logText = single, blobs = mapOf(blobA to png(1)))
    val body = call(bundleHost(), projectHistory = history, tool = "history_diff")

    assertTrue(body.isError())
    // `timelineJsonFor` returns null below two versions, so this is the no-timeline refusal rather
    // than the "needs two" one — both are honest, and the message must not claim a timeline exists.
    assertTrue(body.errorText().contains("history_list"), body.errorText())
  }

  @Test
  fun `read serves a local blob out of the repository`() {
    val history = projectHistory(blobs = mapOf(blobA to png(1), blobB to png(2)))
    val body =
      call(
        bundleHost(),
        projectHistory = history,
        tool = "history_read",
        extraArgs = ""","blob":"$blobA"""",
      )
    val content = body["result"]!!.jsonObject["content"]!!.jsonArray

    assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(
      Base64.getEncoder().encodeToString(png(1)),
      content[0].jsonObject["data"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `read resolves a local version by its commit as well as its blob`() {
    val history = projectHistory(blobs = mapOf(blobA to png(1), blobB to png(2)))
    val body =
      call(
        bundleHost(),
        projectHistory = history,
        tool = "history_read",
        extraArgs = ""","commit":"${sha.take(8)}"""",
      )
    val content = body["result"]!!.jsonObject["content"]!!.jsonArray

    assertEquals("image", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(
      Base64.getEncoder().encodeToString(png(1)),
      content[0].jsonObject["data"]!!.jsonPrimitive.content,
      "the newest commit carries blobA, so addressing by either must land on the same bytes",
    )
  }

  @Test
  fun `read reports a blob the timeline names but the repository has lost`() {
    // The local counterpart of "the branch will not serve it": the timeline is derived from the
    // log, so it can name an object a shallow or pruned clone no longer holds.
    val history = projectHistory(blobs = mapOf(blobB to png(2)))
    val body =
      call(
        bundleHost(),
        projectHistory = history,
        tool = "history_read",
        extraArgs = ""","blob":"$blobA"""",
      )

    assertTrue(body.isError())
    assertTrue(body.errorText().contains("no object"), body.errorText())
  }
}

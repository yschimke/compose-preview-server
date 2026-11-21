package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A Kobento design's `.rc` is Kobento's `convert` of its component JSON, with the server token. */
class KobentoConvertExportTest {
  private val rc = byteArrayOf(0, 4, 0x8c.toByte(), 0, 1, 2, 3)

  private fun answer(bytes: ByteArray = rc): String = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", 1)
    put(
      "result",
      buildJsonObject {
        put(
          "content",
          buildJsonArray {
            add(
              buildJsonObject {
                put("type", "text")
                put(
                  "text",
                  buildJsonObject {
                    put("rc_path", "out/doc.rc")
                    put("bytes", bytes.size.toString())
                    put("rc_base64", Base64.getEncoder().encodeToString(bytes))
                  }
                    .toString(),
                )
              },
            )
          },
        )
      },
    )
  }
    .toString()

  @Test
  fun `convert is called with the token and the design, and its bytes come back`() {
    var sent: Triple<URI, String, String>? = null
    val compiler =
      KobentoConvertCompiler("service-token") { uri, token, body ->
        sent = Triple(uri, token, body)
        200 to answer()
      }
    val bytes = compiler.compile("""{"root":{"column":{}}}""")
    assertEquals(rc.toList(), bytes.toList())
    val (uri, token, body) = checkNotNull(sent)
    assertEquals(KobentoConvertCompiler.DEFAULT_ENDPOINT, uri)
    assertEquals("service-token", token)
    val request = Json.parseToJsonElement(body).jsonObject
    assertEquals("tools/call", request.getValue("method").jsonPrimitive.content)
    val params = request.getValue("params").jsonObject
    assertEquals("convert", params.getValue("name").jsonPrimitive.content)
    val arguments = params.getValue("arguments").jsonObject
    assertEquals(
      Json.parseToJsonElement("""{"root":{"column":{}}}"""),
      arguments.getValue("json"),
    )
    assertEquals("true", arguments.getValue("include_base64").jsonPrimitive.content)
  }

  @Test
  fun `an event-stream answer is read too`() {
    val compiler =
      KobentoConvertCompiler("t") { _, _, _ -> 200 to "event: message\ndata: ${answer()}\n\n" }
    assertEquals(rc.toList(), compiler.compile("{}").toList())
  }

  @Test
  fun `refusals reach the client as sentences`() {
    val badToken = KobentoConvertCompiler("t") { _, _, _ -> 401 to "" }
    assertContains(
      assertFailsWith<IllegalStateException> { badToken.compile("{}") }.message!!,
      "token",
    )

    val refused =
      KobentoConvertCompiler("t") { _, _, _ ->
        200 to
          """{"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[{"type":"text","text":"Unknown component Foo"}]}}"""
      }
    assertContains(
      assertFailsWith<IllegalArgumentException> { refused.compile("{}") }.message!!,
      "Unknown component Foo",
    )

    val rpcError =
      KobentoConvertCompiler("t") { _, _, _ ->
        200 to """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad json"}}"""
      }
    assertContains(
      assertFailsWith<IllegalArgumentException> { rpcError.compile("{}") }.message!!,
      "bad json",
    )
  }

  private val document =
    blankUiBuilderDocument(
        "k",
        catalogPin =
          Json.parseToJsonElement(
              """{"systemId":"kobento","catalogRevision":"r","capabilityDigest":"d",
                 "nativeRuntimeId":"n"}""",
            )
            .jsonObject,
        environment =
          Json.parseToJsonElement(
              """{"widthDp":360,"heightDp":320,"density":1.0,"theme":"dark","locale":"en-GB",
                 "fontScale":1.0,"layoutDirection":"ltr"}""",
            )
            .jsonObject,
      )
      .toDesignDocumentV1()

  private fun catalog(statusSemantics: JsonObject): CatalogCapabilityV1 =
    benchmarkCatalogExecutor()
      .listCatalogs()
      .first()
      .newBuilder()
      .also { it.statusSemantics = statusSemantics }
      .build()

  private fun request(format: ExportFormatV1, catalog: CatalogCapabilityV1) =
    RevisionPinnedUiBuilderExport(
      actor = AuthenticatedUiBuilderActor("owner"),
      designId = "k",
      revision = 3,
      documentHash = "hash",
      document = document,
      catalog = catalog,
      format = format,
    )

  private val json =
    ExportArtifactV1(
      ExportFormatV1.KOBENTO_JSON,
      "application/json",
      ExportEncodingV1.UTF8,
      """{"root":{"column":{}}}""",
      "json-digest",
    )

  @Test
  fun `a kobento rc is compiled from the delegate's kobento json`() {
    val asked = mutableListOf<ExportFormatV1>()
    val delegate = UiBuilderExportExecutor {
      asked += it.format
      json
    }
    var compiled: String? = null
    val executor =
      DocumentCompileExportExecutor(
        delegate,
        mapOf(
          KobentoConvertCompiler.FORMAT_ID to
            DocumentCompiler {
              compiled = it
              rc
            },
        ),
      )
    val kobento = catalog(buildJsonObject { put("kobento", JsonObject(emptyMap())) })

    val artifact = executor.export(request(ExportFormatV1.RC, kobento))

    assertEquals(listOf(ExportFormatV1.KOBENTO_JSON), asked)
    assertEquals(json.content, compiled)
    assertEquals(ExportFormatV1.RC, artifact.format)
    assertEquals(ExportEncodingV1.BASE64, artifact.encoding)
    assertEquals(rc.toList(), Base64.getDecoder().decode(artifact.content).toList())
    assertEquals(
      MessageDigest.getInstance("SHA-256").digest(rc).joinToString("") { "%02x".format(it) },
      artifact.contentDigest,
    )
  }

  @Test
  fun `every other request passes through`() {
    val passed =
      ExportArtifactV1(
        ExportFormatV1.RC,
        "application/octet-stream",
        ExportEncodingV1.BASE64,
        "",
        "d",
      )
    val delegate = UiBuilderExportExecutor { passed }
    val executor =
      DocumentCompileExportExecutor(
        delegate,
        mapOf(KobentoConvertCompiler.FORMAT_ID to DocumentCompiler { error("not asked") }),
      )
    val kobento = catalog(buildJsonObject { put("kobento", JsonObject(emptyMap())) })
    val compose = catalog(JsonObject(emptyMap()))
    // Kobento JSON itself, a `.rc` of a catalog with no document format, and a host with no
    // compiler.
    assertSame(passed, executor.export(request(ExportFormatV1.KOBENTO_JSON, kobento)))
    assertSame(passed, executor.export(request(ExportFormatV1.RC, compose)))
    assertSame(
      passed,
      DocumentCompileExportExecutor(delegate, emptyMap())
        .export(request(ExportFormatV1.RC, kobento)),
    )
  }
}

package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A published UID snapshot, bound to its reference by a digest rather than mutable editor state.
 */
internal object ServeUidReference {
  const val MAX_BYTES: Int = 2 * 1024 * 1024

  fun isUid(reference: DesignReference): Boolean =
    reference.source.provider == "ui-builder" &&
      reference.artifact?.kind == "uid" &&
      reference.artifact?.path?.let(ServeDesignReferenceStore::isSafeRelativePath) == true &&
      reference.source.attributes["documentSha256"]?.matches(Regex("[a-f0-9]{64}")) == true

  fun valid(reference: DesignReference, bytes: ByteArray): Boolean = runCatching {
    require(isUid(reference) && bytes.size <= MAX_BYTES)
    val hash =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    require(hash == reference.source.attributes["documentSha256"])
    val doc = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    require(
      doc["schema"]?.jsonPrimitive?.content in
        setOf("compose-ui-builder-document/v1", "compose-ui-builder-document/v1-candidate")
    )
    require(doc["id"]?.jsonPrimitive?.content == reference.source.attributes["designId"])
    val system = doc.getValue("catalogPin").jsonObject.getValue("systemId").jsonPrimitive.content
    require(system.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")))
    true
  }
    .getOrDefault(false)

  fun spec(references: List<DesignReference>, basePath: String): ServeWeb.FigmaSpec? =
    references.firstOrNull(::isUid)?.let {
      ServeWeb.FigmaSpec(
        "$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.html?sha=${it.source.attributes.getValue("documentSha256")}",
        it.label,
        "UI Builder",
      )
    }

  fun page(
    reference: DesignReference,
    document: ByteArray,
    basePath: String,
    codeHref: String? = null,
  ): String {
    val payload = document.decodeToString().replace("<", "\\u003c")
    val preview = WebEscaping.urlEncodeSegment(reference.previewId)
    val id = WebEscaping.urlEncodeSegment(reference.id)
    val back = WebEscaping.htmlEscape("$basePath/compare/$preview?reference=$id")
    val source =
      reference.source.uri.orEmpty().takeIf {
        it.matches(
          Regex(
            "https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/blob/[a-f0-9]{40}/[^?#]+\\.uid"
          )
        )
      }
    val sourceLink =
      source
        ?.let {
          "<a href=\"${WebEscaping.htmlEscape(it)}\" target=\"_blank\" rel=\"noopener\">Source .uid</a>"
        }
        .orEmpty()
    val codeLink =
      codeHref
        ?.let {
          "<a href=\"${WebEscaping.htmlEscape(it)}\" target=\"_blank\" rel=\"noopener\">App code</a>"
        }
        .orEmpty()
    val previewLink = WebEscaping.htmlEscape("$basePath/p/$preview")
    return """<!doctype html><html lang="en"><meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1">
      <title>UI Builder reference · ${WebEscaping.htmlEscape(reference.label)}</title>
      <style>body{margin:0;font:14px system-ui;background:#101218;color:#eee}header{padding:12px;display:flex;gap:18px;align-items:center}a{color:#bacaff}button{padding:8px}iframe{border:0;width:100%;height:calc(100vh - 96px)}#status{margin:0 12px 8px}</style>
      <header><a href="$back">← Compare preview</a><a href="$previewLink">Preview</a>$codeLink$sourceLink<button id="download">Download .uid</button><span>Reference snapshot · edits stay in this tab</span></header>
      <p id="status">Loading UI Builder…</p><iframe id="editor" title="UI Builder reference editor"></iframe>
      <script id="uid-document" type="application/json">$payload</script>
      <script src="${ServeWebAssets.href("uid-reference.js")}"></script></html>"""
      .trimIndent()
  }
}

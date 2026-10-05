package ee.schimke.composeai.mcp.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

// ---------------------------------------------------------------------------
// Internal DTOs for the daemon-facing tool/resource catalog.
//
// The MCP Kotlin SDK owns the wire and session layer. These types only decouple
// `DaemonMcpServer`'s tool code from it, so an SDK bump touches one adapter
// (`McpServer.kt`'s `toSdk*` functions). Never mirror an SDK wire shape here:
// several daemon-protocol and SDK types share simple names, and a duplicate
// invites the wrong auto-import.
//
// References:
// - https://modelcontextprotocol.io/specification/2025-06-18/basic
// - https://modelcontextprotocol.io/specification/2025-06-18/server/resources
// - https://modelcontextprotocol.io/specification/2025-06-18/server/tools
// ---------------------------------------------------------------------------

// =====================================================================
// tools/list, tools/call
// =====================================================================

@Serializable
data class ToolDef(
  val name: String,
  val description: String,
  val inputSchema: JsonElement,
  val meta: JsonObject? = null,
  /** MCP `title`: the human-readable name a host shows, e.g. on an OpenAI entrypoint. */
  val title: String? = null,
  /** MCP `icons` (2025-11-25): shown beside an entrypoint tool in host navigation. */
  val icons: List<ToolIcon>? = null,
  /** MCP `outputSchema`: the shape of `structuredContent`, e.g. OpenAI's `SettingsReadResult`. */
  val outputSchema: JsonObject? = null,
  /** MCP `annotations.readOnlyHint`: true for a tool that never changes anything. */
  val readOnlyHint: Boolean? = null,
)

/** One MCP `Icon`: a `src` URI (a `data:` URI for inline SVG), its type and sizes. */
@Serializable
data class ToolIcon(
  val src: String,
  val mimeType: String? = null,
  val sizes: List<String>? = null,
)

@Serializable
data class CallToolResult(
  val content: List<ContentBlock>,
  val isError: Boolean? = null,
  /** MCP `_meta`: debug data for clients and tests, kept out of what the agent reads. */
  val meta: JsonObject? = null,
  /** MCP `structuredContent`: the machine-readable form of the result, for apps and agents. */
  val structuredContent: JsonObject? = null,
)

@Serializable
sealed interface ContentBlock {
  @Serializable @SerialName("text") data class Text(val text: String) : ContentBlock

  @Serializable
  @SerialName("image")
  data class Image(val data: String, val mimeType: String) : ContentBlock

  @Serializable
  @SerialName("resource_link")
  data class ResourceLink(
    val uri: String,
    val name: String,
    val mimeType: String? = null,
    val description: String? = null,
  ) : ContentBlock

  /**
   * MCP 2025-06-18 spec — `EmbeddedResource` content block. Wraps a [ResourceContents] (text or
   * blob) so a tool can return non-image binary payloads (audio, video, arbitrary `application`
   * mime types) without misusing the `image` block — strict clients reject mismatched mimeTypes on
   * `image`.
   *
   * Use this for `record_preview` mp4/webm responses (mimeType `video/mp4` / `video/webm`) and any
   * other tool that needs to inline non-image bytes. The wrapped [ResourceContents.Blob] carries
   * the same `{uri, mimeType, blob}` shape `resources/read` uses, so a client that already knows
   * how to render resources reads the same code path.
   */
  @Serializable
  @SerialName("resource")
  data class EmbeddedResource(val resource: ResourceContents) : ContentBlock
}

// =====================================================================
// resources/list, resources/read
// =====================================================================

@Serializable
data class ResourceDescriptor(
  val uri: String,
  val name: String,
  val description: String? = null,
  val mimeType: String? = null,
  val size: Long? = null,
  /** Optional MCP `_meta` payload (MCP App UI hints, local client-only resource details). */
  val meta: JsonObject? = null,
)

@Serializable data class ReadResourceResult(val contents: List<ResourceContents>)

@Serializable(with = ResourceContentsSerializer::class)
sealed interface ResourceContents {
  @Serializable
  @SerialName("text")
  data class Text(
    val uri: String,
    val mimeType: String? = null,
    val text: String,
    val meta: JsonObject? = null,
  ) : ResourceContents

  @Serializable
  @SerialName("blob")
  data class Blob(
    val uri: String,
    val mimeType: String? = null,
    val blob: String,
    val meta: JsonObject? = null,
  ) : ResourceContents
}

object ResourceContentsSerializer : KSerializer<ResourceContents> {
  override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

  override fun deserialize(decoder: Decoder): ResourceContents {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw SerializationException("ResourceContents can only be decoded from JSON")
    val element = jsonDecoder.decodeJsonElement()
    val obj = element.jsonObject
    return when {
      "text" in obj -> jsonDecoder.json.decodeFromJsonElement<ResourceContents.Text>(element)
      "blob" in obj -> jsonDecoder.json.decodeFromJsonElement<ResourceContents.Blob>(element)
      else -> throw SerializationException("ResourceContents must contain either 'text' or 'blob'")
    }
  }

  override fun serialize(encoder: Encoder, value: ResourceContents) {
    val jsonEncoder =
      encoder as? JsonEncoder
        ?: throw SerializationException("ResourceContents can only be encoded to JSON")
    when (value) {
      is ResourceContents.Text ->
        jsonEncoder.encodeSerializableValue(ResourceContents.Text.serializer(), value)
      is ResourceContents.Blob ->
        jsonEncoder.encodeSerializableValue(ResourceContents.Blob.serializer(), value)
    }
  }
}

package ee.schimke.composeai.uibuilder

/**
 * What a design URL says it means, beyond which design it is.
 *
 * The canonical URL for a design is `/ui-builder/<catalog>/<designId>`, and the only thing it says
 * is which catalog and which design. Identity and transport — `actor`, `clientId`, `token`,
 * `endpoint` — live in the query because they configure *who* is editing. Neither says **what** in
 * the design a link means, so "look at this thread" could not be pasted into a chat and an agent
 * told "the button on the checkout design" had to search for it.
 *
 * These three selectors are that missing half, and they are additive: a URL carrying none of them
 * opens exactly what it opened before.
 *
 * - [revision] pins the design to one committed revision, shown read-only.
 * - [nodeId] selects one layer as the design opens.
 * - [threadId] opens the Talk panel on one conversation.
 *
 * ### Why the thread is a fragment and the other two are not
 *
 * [revision] and [nodeId] are read as a query; [threadId] is read from the URL **fragment**, and
 * the difference is deliberate rather than cosmetic. A fragment is never sent to the server: it
 * does not reach the request line, the access log, a proxy, or a referrer header. A thread id is
 * the one selector that names a *discussion* — the private half of a private design — so the id of
 * a conversation somebody linked to should not accumulate in logs that outlive the link. The
 * revision and the node are properties of the document the reader is about to be served anyway.
 *
 * ### What is never in one of these URLs
 *
 * A link built by [designUrlPath] carries the path and the selectors and nothing else — no value
 * from [DESIGN_URL_IDENTITY_KEYS], and above all no token. A shared link is an address, never a
 * credential; whoever opens it presents their own, which is the rule the export lane's Copy link
 * already follows.
 */
data class DesignUrlSelectors(
  /** A committed revision to show read-only, or null for the living design. */
  val revision: Long? = null,
  /** A node to select as the design opens, or null. */
  val nodeId: String? = null,
  /** A comment thread to open the Talk panel on, or null. */
  val threadId: String? = null,
) {
  val isEmpty: Boolean
    get() = revision == null && nodeId == null && threadId == null
}

/**
 * What `?revision=` did to this editor: which revision was asked for, and which one is on screen.
 *
 * Two fields rather than one, because "the link named a revision" and "you are looking at it" are
 * different facts and the banner has to say which. A revision that was trimmed out of the retained
 * window, or that a design never reached, cannot be shown — and refusing to open the design at all
 * would be the worst possible answer to a stale link somebody pasted a month ago. So the editor
 * opens the living design and the banner says why, which is the same rule the catalog-less design
 * URL follows: answer the question the reader actually has.
 */
data class DesignRevisionPin(
  /** The revision the URL asked for. */
  val requested: Long,
  /** True while that revision is what the canvas is drawing. */
  val pinned: Boolean,
) {
  /**
   * Whether the design may be edited.
   *
   * A pinned revision is history: an edit to it would either have to fork the design or be applied
   * to the head it is not showing, and both are worse than a canvas that says it is read-only. Only
   * *document* edits are refused — a comment, a reference mark and a panel are not the design.
   */
  val readOnly: Boolean
    get() = pinned
}

/** `?revision=<n>` — the committed revision a link names. */
const val DESIGN_URL_REVISION_KEY: String = "revision"

/** `?node=<nodeId>` — the layer a link names. */
const val DESIGN_URL_NODE_KEY: String = "node"

/** `#thread=<threadId>` — the conversation a link names, in the fragment. */
const val DESIGN_URL_THREAD_KEY: String = "thread"

/**
 * The identity and transport values the editor carries from one URL to the next.
 *
 * One list, read by both sides of the same question: the browser's canonical rewrite copies exactly
 * these forward, and [designUrlPath] writes none of them. Two lists would eventually disagree, and
 * the way they would disagree is a token riding along in a link somebody pastes into a chat.
 * `token` is in it because the *page's own* URL keeps its credential across the rewrite.
 */
val DESIGN_URL_IDENTITY_KEYS: List<String> =
  listOf("token", "actor", "clientId", "displayName", "color", "endpoint", "updatesEndpoint")

/** The longest node or thread id a URL is allowed to name; ids here are short and generated. */
private const val SELECTOR_VALUE_MAX = 512

/**
 * Reads the three selectors out of one URL's query and fragment.
 *
 * Tolerant by construction, because every input is somebody else's link: an unknown key is ignored
 * rather than refused, a `revision` that is not a positive number is dropped rather than failing
 * the page, and a value that is blank or absurdly long is treated as absent. A selector that names
 * something this design does not have is **not** this function's problem — an unknown node id is
 * still returned here and answered by the editor with a notice, because "there is no such layer" is
 * a sentence a reader needs and a parse failure is not.
 *
 * Both arguments take the browser's own spelling, with or without their leading `?` and `#`, so a
 * caller can hand over `location.search` and `location.hash` unmodified.
 *
 * The fragment is read for [DESIGN_URL_THREAD_KEY] only. A `revision` or `node` written into the
 * fragment is ignored rather than honoured: those two are query keys, and a URL that worked by
 * accident in one spelling is one that breaks when the server learns to read them.
 */
fun parseDesignUrlSelectors(query: String?, fragment: String?): DesignUrlSelectors {
  val queryValues = parseUrlPairs(query?.removePrefix("?"))
  val fragmentValues = parseUrlPairs(fragment?.removePrefix("#"))
  return DesignUrlSelectors(
    revision = queryValues[DESIGN_URL_REVISION_KEY]?.toRevisionOrNull(),
    nodeId = queryValues[DESIGN_URL_NODE_KEY]?.toSelectorIdOrNull(),
    threadId = fragmentValues[DESIGN_URL_THREAD_KEY]?.toSelectorIdOrNull(),
  )
}

/**
 * The canonical URL for one design, carrying only what a reader needs to see the same thing.
 *
 * Path form rather than the legacy `?designId=` query, absolute-from-root rather than fully
 * qualified — the origin is the page's own, and a caller that needs an absolute URL resolves this
 * against it. The order is fixed (`revision` then `node`, then the fragment) so the same selection
 * always produces the same string: a link copied twice is the same link, which is what makes it
 * safe to paste into a pull request and compare.
 */
fun designUrlPath(
  catalogSystemId: String,
  designId: String,
  selectors: DesignUrlSelectors = DesignUrlSelectors(),
): String {
  require(catalogSystemId.isNotBlank()) { "a design link needs a catalog" }
  require(designId.isNotBlank()) { "a design link needs a design id" }
  val path = "/ui-builder/${encodeUrlComponent(catalogSystemId)}/${encodeUrlComponent(designId)}"
  val query = buildList {
    selectors.revision?.let { add("$DESIGN_URL_REVISION_KEY=$it") }
    selectors.nodeId?.let { add("$DESIGN_URL_NODE_KEY=${encodeUrlComponent(it)}") }
  }
  val fragment =
    selectors.threadId?.let { "#$DESIGN_URL_THREAD_KEY=${encodeUrlComponent(it)}" }.orEmpty()
  return path + (if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")) + fragment
}

/**
 * `a=1&b=2` as a map, last value wins, tolerant of everything a real URL contains.
 *
 * Empty segments, a bare key with no `=`, and a value that will not percent-decode are all read as
 * far as they can be and otherwise skipped: this parses links people paste, not a wire format.
 */
private fun parseUrlPairs(raw: String?): Map<String, String> {
  if (raw.isNullOrEmpty()) return emptyMap()
  val values = mutableMapOf<String, String>()
  raw.split('&').forEach { pair ->
    if (pair.isEmpty()) return@forEach
    val separator = pair.indexOf('=')
    if (separator <= 0) return@forEach
    val key = decodeUrlComponent(pair.substring(0, separator))
    values[key] = decodeUrlComponent(pair.substring(separator + 1))
  }
  return values
}

/** A revision is a positive whole number or it is not a revision. */
private fun String.toRevisionOrNull(): Long? = trim().toLongOrNull()?.takeIf { it > 0 }

/** A node or thread id, or null where the URL named nothing usable. */
private fun String.toSelectorIdOrNull(): String? =
  trim().takeIf { it.isNotEmpty() && it.length <= SELECTOR_VALUE_MAX }

/**
 * Percent-encoding for one URL component, matching JavaScript's `encodeURIComponent`.
 *
 * Written here rather than taken from a platform, because this function's output is compared
 * against a fixed expectation in a common test and read by a browser: the same input has to make
 * the same bytes on the JVM the test runs on and in the Wasm the editor ships as.
 */
internal fun encodeUrlComponent(value: String): String {
  val out = StringBuilder(value.length)
  value.encodeToByteArray().forEach { byte ->
    val code = byte.toInt() and 0xFF
    val char = code.toChar()
    if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in UNRESERVED) {
      out.append(char)
    } else {
      out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
    }
  }
  return out.toString()
}

/**
 * The inverse of [encodeUrlComponent], plus `+` as a space.
 *
 * `+` is decoded because a form-encoded query is what a browser produces from a submitted form and
 * this parser reads whatever arrives, not only what this code wrote. A malformed escape is left as
 * written rather than throwing: a link with a stray `%` in it should still open the design.
 */
internal fun decodeUrlComponent(value: String): String {
  if ('%' !in value && '+' !in value) return value
  val bytes = ArrayList<Byte>(value.length)
  var index = 0
  while (index < value.length) {
    val char = value[index]
    when {
      char == '+' -> {
        bytes.add(' '.code.toByte())
        index += 1
      }
      char == '%' && index + 2 < value.length -> {
        val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
        if (decoded == null) {
          bytes.add(char.code.toByte())
          index += 1
        } else {
          bytes.add(decoded.toByte())
          index += 3
        }
      }
      else -> {
        char.toString().encodeToByteArray().forEach(bytes::add)
        index += 1
      }
    }
  }
  return bytes.toByteArray().decodeToString()
}

private const val UNRESERVED = "-_.!~*'()"

private const val HEX = "0123456789ABCDEF"

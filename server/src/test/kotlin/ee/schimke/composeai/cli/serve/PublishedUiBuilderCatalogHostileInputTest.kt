package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json

/**
 * A published `ui-builder.json` comes from **another repository**, over the network, and is read at
 * startup. Nothing in this process reviews it first.
 *
 * [PublishedUiBuilderCatalogTest] pins the refusals this reader was written to make — a schema
 * major it does not know, a missing prefix, a policy that expects an inventory it does not have.
 * This pins the weaker and more important property: that **no** document reaches the end of
 * `compose` by throwing. The refusal list is what the author thought of; a hostile document is what
 * nobody did.
 *
 * Why it matters more than a returned `Unusable`: the call site in `ServeRunner` handles both
 * `Result` branches, and an exception is neither. It escapes into `openUiBuilderLane`, whose caller
 * turns any failure into `uiBuilderDisabledWarning` and a null lane — so one catalog's malformed
 * file would take the UI builder away from **every** catalog on the host, which is exactly the
 * blast radius the per-catalog fallback exists to prevent. The server itself survives either way;
 * this is about the builder surviving with it.
 */
class PublishedUiBuilderCatalogHostileInputTest {

  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  private val record = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<ComponentRecordFile>(
      """
      {
        "schemaVersion": 1,
        "module": ":hostile",
        "variant": "debug",
        "components": [
          {
            "canonicalId": ":hostile/com.example.AKt.Widget",
            "componentIds": ["Widgets/Widget"],
            "symbol": {
              "name": "Widget",
              "callable": "com.example.Widget",
              "jvmOwner": "com.example.AKt",
              "origin": "PROJECT"
            },
            "parameters": [
              { "name": "label", "type": "String", "typeFqn": "kotlin.String", "hasDefault": false }
            ],
            "slots": [],
            "code": { "imports": ["com.example.Widget"] }
          },
          {
            "canonicalId": ":hostile/com.example.AKt.Nameless",
            "componentIds": [],
            "symbol": {
              "name": "",
              "callable": "com.example.",
              "jvmOwner": "com.example.AKt",
              "origin": "PROJECT"
            },
            "parameters": [],
            "slots": [],
            "code": { "imports": [] }
          }
        ]
      }
      """
        .trimIndent()
    )

  /**
   * Each case is a document a generator would never write and a mistake — or an attacker —
   * plausibly could. None is expected to compose; every one is expected to *return*.
   */
  private val hostile: List<Pair<String, String>> =
    listOf(
      "empty string" to "",
      "whitespace" to "   \n  ",
      "not JSON at all" to "<!doctype html><title>404</title>",
      "a JSON array" to "[]",
      "a JSON string" to "\"ui-builder\"",
      "a JSON number" to "17",
      "null" to "null",
      "an empty object" to "{}",
      "the right schema and nothing else" to """{"schema":"compose-ui-builder-catalog/v1"}""",
      "statusSemantics is a string" to
        """{"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},"statusSemantics":"no"}""",
      "componentIdPrefix is a number" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":4}}
        """
          .trimIndent(),
      "componentIdPrefix is only whitespace" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"   "}}
        """
          .trimIndent(),
      "a prefix that is a path traversal" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"../../../etc/"}}
        """
          .trimIndent(),
      // The two collections this reader iterates. A policy naming a record id that is not in the
      // record, and a builtin colliding with a component, are the two shapes that decide what
      // `taken` and `skipped` end up holding.
      "a policy for a record component that does not exist" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2},
         "statusSemantics":{"componentIdPrefix":"h/",
           "components":{"h/ghost":{"record":":hostile/com.example.AKt.Missing"}}}}
        """
          .trimIndent(),
      "a builtin that collides with a derived component id" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2},
         "statusSemantics":{"componentIdPrefix":"h/",
           "builtins":{"h/widget":{"role":"screen-root"}}}}
        """
          .trimIndent(),
      "two policies claiming one component id" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2},
         "statusSemantics":{"componentIdPrefix":"h/",
           "components":{"h/same":{"record":":hostile/com.example.AKt.Widget"},
                         "h/same ":{"record":":hostile/com.example.AKt.Nameless"}}}}
        """
          .trimIndent(),
      "a record count far larger than the record" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2147483647},
         "statusSemantics":{"componentIdPrefix":"h/"}}
        """
          .trimIndent(),
      "a negative record count" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":-1},
         "statusSemantics":{"componentIdPrefix":"h/"}}
        """
          .trimIndent(),
      "a builtin whose role is unknown" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"h/",
           "builtins":{"h/x":{"role":"a-role-invented-two-releases-from-now"}}}}
        """
          .trimIndent(),
      "deeply nested statusSemantics" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"h/","frame":${nest(200)}}}
        """
          .trimIndent(),
    )

  @Test
  fun `no published document makes the reader throw`() {
    // Both with and without a record: the record is the other half of the composition, and a
    // document that survives one may not survive the other.
    hostile.forEach { (label, document) ->
      listOf("with a record" to record, "with no record" to null).forEach { (arm, inventory) ->
        val result =
          try {
            PublishedUiBuilderCatalog.compose(document, inventory, exports)
          } catch (failure: Throwable) {
            fail("$label, $arm: threw ${failure::class.simpleName}: ${failure.message}")
          }
        // Composing is allowed — several of these are merely unusual, not malformed. What is not
        // allowed is escaping the two branches the caller knows how to handle.
        assertTrue(
          result is PublishedUiBuilderCatalog.Result.Composed ||
            result is PublishedUiBuilderCatalog.Result.Unusable,
          "$label, $arm returned neither Composed nor Unusable",
        )
      }
    }
  }

  private companion object {
    /** A `frame` object nested [depth] deep — the shape a recursive-descent parser dies on. */
    fun nest(depth: Int): String = buildString {
      repeat(depth) { append("""{"a":""") }
      append("1")
      repeat(depth) { append("}") }
    }
  }
}

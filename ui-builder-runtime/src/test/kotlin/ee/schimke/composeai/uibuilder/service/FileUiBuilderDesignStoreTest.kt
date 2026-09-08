package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the per-design store promises, stated as tests rather than as a cost model.
 *
 * The claim in yschimke/compose-preview-server#578 is not "this is faster" — it is that an edit
 * writes what the edit changed and touches no other design. Both halves are observable in the file
 * tree, so they are asserted there.
 */
class FileUiBuilderDesignStoreTest {
  @Test
  fun `a design round trips through its parts`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    assertEquals(emptyMap(), store.load().designs)

    val design = design("checkout")
    store.commit("checkout", null, design)

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(mapOf("checkout" to design), reopened.designs)
    assertEquals(emptyMap(), reopened.quarantined)
  }

  @Test
  fun `every collection the journal carries survives a reopen`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val second =
      first.copy(
        history = first.history + committed("op-2"),
        operationOutcomes = first.operationOutcomes + ("op-2" to outcome("op-2")),
        acceptedOperations = first.acceptedOperations + ("op-2" to accepted("op-2")),
        tombstones = first.tombstones + ("node-2" to tombstone("node-2")),
        audit = first.audit + audit("op-2"),
      )
    store.commit("checkout", first, second)
    val third =
      second.copy(
        acceptedOperations = second.acceptedOperations - "op-2",
        tombstones = emptyMap(),
      )
    store.commit("checkout", second, third)

    assertEquals(third, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `an edit to one design writes nothing belonging to another`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val checkout = design("checkout")
    val settings = design("settings")
    store.commit("checkout", null, checkout)
    store.commit("settings", null, settings)

    val untouched = fingerprint(root.resolve("designs/${slugOf("settings")}"))
    store.commit("checkout", checkout, checkout.copy(audit = checkout.audit + audit("op-2")))

    assertEquals(
      untouched,
      fingerprint(root.resolve("designs/${slugOf("settings")}")),
      "committing one design must not rewrite another design's files",
    )
  }

  @Test
  fun `a commit that changes only the journal reuses the document it did not touch`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val documentBefore =
      fingerprint(root.resolve("designs/${slugOf("checkout")}")).filterKeys {
        it.startsWith("document-")
      }

    store.commit("checkout", first, first.copy(audit = first.audit + audit("op-2")))

    val documentAfter =
      fingerprint(root.resolve("designs/${slugOf("checkout")}")).filterKeys {
        it.startsWith("document-")
      }
    assertEquals(
      documentBefore,
      documentAfter,
      "the document is named by its digest, so an\n" +
        "unchanged document is neither renamed nor rewritten",
    )
    assertEquals(1, documentAfter.size, "and the previous generation is swept, not accumulated")
  }

  @Test
  fun `a changed document is written under a new name and the old one is swept`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val before = documentFiles(root, "checkout")

    val second =
      first.copy(
        document = first.document.copy(revision = 1, title = "Checkout, reworked"),
        lastSequence = 1,
      )
    store.commit("checkout", first, second)

    val after = documentFiles(root, "checkout")
    assertEquals(1, after.size)
    assertNotEquals(before, after)
    assertEquals(second, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `retained revisions are written once and unlinked when they fall out`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    var previous: PersistedDesignV1? = null
    var current = design("checkout")
    store.commit("checkout", null, current)
    previous = current
    repeat(4) { step ->
      val revision = (step + 1).toLong()
      current =
        current.copy(
          document = current.document.copy(revision = revision),
          lastSequence = revision,
          revisionSnapshots =
            (current.revisionSnapshots +
                RevisionStateV1(current.document.copy(revision = revision), revision))
              .takeLast(2),
          positionSnapshots =
            (current.positionSnapshots + PositionStateV1(revision, emptyMap())).takeLast(2),
        )
      store.commit("checkout", previous, current)
      previous = current
    }

    val revisions =
      Files.list(root.resolve("designs/${slugOf("checkout")}/revisions")).use { it.toList() }
    assertEquals(2, revisions.size, "retention is enforced by unlinking, not by rewriting")
    assertEquals(current, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `a design whose parts cannot be read is quarantined and the rest still load`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.commit("settings", null, design("settings"))

    val document = documentFiles(root, "checkout").single()
    Files.writeString(document, "{\"checksumSha256\":\"nope\",\"payload\":{}}")

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(setOf("settings"), reopened.designs.keys)
    assertContains(reopened.quarantined.keys, "checkout")
    assertTrue(
      Files.exists(root.resolve("designs/${slugOf("checkout")}/quarantine.json")),
      "the reason is recorded beside the design that could not be read",
    )
  }

  @Test
  fun `a quarantined design is repaired by committing it again`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).commit("checkout", null, design("checkout"))
    Files.writeString(documentFiles(root, "checkout").single(), "not json")
    val broken = FileUiBuilderDesignStore(root)
    assertContains(broken.load().quarantined.keys, "checkout")

    broken.commit("checkout", null, design("checkout"))

    val reopened = FileUiBuilderDesignStore(root).load()
    assertEquals(setOf("checkout"), reopened.designs.keys)
    assertEquals(emptyMap(), reopened.quarantined)
  }

  @Test
  fun `a journal tail no header committed to is ignored and then truncated`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    val first = design("checkout")
    store.commit("checkout", null, first)
    val journal = journalFile(root, "checkout")
    // A commit that appended and then died before its header landed.
    Files.writeString(
      journal,
      Files.readString(journal) + "{\"historySet\":[]}\n",
      java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
      java.nio.file.StandardOpenOption.WRITE,
    )

    val reopened = FileUiBuilderDesignStore(root)
    assertEquals(first, reopened.load().designs.getValue("checkout"))

    val second = first.copy(audit = first.audit + audit("op-2"))
    reopened.commit("checkout", first, second)
    assertEquals(second, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
  }

  @Test
  fun `the journal is rewritten once it costs more than what it holds`() {
    val root = createTempDirectory("ui-builder-store")
    val store =
      FileUiBuilderDesignStore(
        root,
        UiBuilderStoreLimits(journalCompactionBytes = 2_048, journalCompactionRatio = 2),
      )
    var previous = design("checkout")
    store.commit("checkout", null, previous)
    repeat(60) { step ->
      val next =
        previous.copy(
          operationOutcomes = mapOf("op-$step" to outcome("op-$step")),
          audit = listOf(audit("op-$step")),
        )
      store.commit("checkout", previous, next)
      previous = next
    }

    val journal = journalFile(root, "checkout")
    assertTrue(
      journal.fileName.toString() != "journal-1.jsonl",
      "a journal that outgrew what it describes is replaced, not appended to forever",
    )
    assertTrue(Files.size(journal) < 8_192, "and the replacement holds only the live state")
    assertEquals(previous, FileUiBuilderDesignStore(root).load().designs.getValue("checkout"))
    assertEquals(
      1,
      Files.list(root.resolve("designs/${slugOf("checkout")}")).use { paths ->
        paths.filter { it.fileName.toString().startsWith("journal-") }.count()
      },
      "the journal it replaced is unlinked",
    )
  }

  @Test
  fun `removing a design removes its directory`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    store.remove("checkout")

    assertFalse(Files.exists(root.resolve("designs/${slugOf("checkout")}")))
    assertEquals(emptyMap(), FileUiBuilderDesignStore(root).load().designs)
  }

  @Test
  fun `one design over its own budget is the only design that cannot be saved`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root, UiBuilderStoreLimits(maximumDesignBytes = 8_192))
    val small = design("settings")
    store.commit("settings", null, small)

    val large =
      design("checkout").let { base ->
        base.copy(
          document =
            base.document.copy(nodes = (0 until 400).associate { "node-$it" to node("node-$it") })
        )
      }
    assertFailsWith<UiBuilderPersistenceException> { store.commit("checkout", null, large) }

    assertEquals(small, FileUiBuilderDesignStore(root).load().designs.getValue("settings"))
  }

  @Test
  fun `a v2 state file is migrated once and kept as the rollback`() {
    val root = createTempDirectory("ui-builder-store")
    val legacy =
      LegacyUiBuilderState.encode(
        PersistedServiceV1(
          mapOf("checkout" to design("checkout"), "settings" to design("settings"))
        ),
        LegacyUiBuilderState.Format.V2,
      )
    Files.write(root.resolve(FileUiBuilderStateStorage.STATE_FILE), legacy)

    val migrated = FileUiBuilderDesignStore(root).load()

    assertEquals(setOf("checkout", "settings"), migrated.designs.keys)
    assertTrue(Files.exists(root.resolve("store.json")))
    assertFalse(
      Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE)),
      "the migrated file is renamed, so a second open does not migrate again",
    )
    assertTrue(
      Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE + ".migrated")),
      "and it is kept, because it is the rollback",
    )
    assertEquals(migrated.designs, FileUiBuilderDesignStore(root).load().designs)
  }

  @Test
  fun `a v2 file beside an existing store is ignored`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.commit("checkout", null, design("checkout"))
    Files.write(
      root.resolve(FileUiBuilderStateStorage.STATE_FILE),
      LegacyUiBuilderState.encode(
        PersistedServiceV1(mapOf("settings" to design("settings"))),
        LegacyUiBuilderState.Format.V2,
      ),
    )

    assertEquals(setOf("checkout"), FileUiBuilderDesignStore(root).load().designs.keys)
    assertTrue(Files.exists(root.resolve(FileUiBuilderStateStorage.STATE_FILE)))
  }

  @Test
  fun `usage counts what is stored`() {
    val root = createTempDirectory("ui-builder-store")
    val store = FileUiBuilderDesignStore(root)
    store.load()
    assertEquals(0, store.usage().bytes)

    store.commit("checkout", null, design("checkout"))
    val stored = store.usage().bytes
    assertTrue(stored > 0)

    store.remove("checkout")
    assertEquals(0, store.usage().bytes)
  }

  @Test
  fun `a store marker this build cannot read is refused rather than guessed`() {
    val root = createTempDirectory("ui-builder-store")
    FileUiBuilderDesignStore(root).load()
    Files.writeString(root.resolve("store.json"), "{\"format\":\"ui-builder-store-v9\"}")

    val failure =
      assertFailsWith<UiBuilderPersistenceException> { FileUiBuilderDesignStore(root).load() }
    assertContains(failure.message.orEmpty(), "ui-builder-store-v9")
  }

  private fun slugOf(designId: String): String = FileUiBuilderDesignStore.slug(designId)

  private fun documentFiles(root: Path, designId: String): List<Path> =
    Files.list(root.resolve("designs/${slugOf(designId)}")).use { paths ->
      paths.filter { it.fileName.toString().startsWith("document-") }.toList()
    }

  private fun journalFile(root: Path, designId: String): Path =
    Files.list(root.resolve("designs/${slugOf(designId)}"))
      .use { paths -> paths.filter { it.fileName.toString().startsWith("journal-") }.toList() }
      .single()

  /** Every file under a design, by name and content, so "was it written" is answerable. */
  private fun fingerprint(directory: Path): Map<String, String> {
    if (!Files.isDirectory(directory)) return emptyMap()
    val entries = mutableMapOf<String, String>()
    Files.walk(directory).use { stream ->
      stream.forEach { path ->
        if (Files.isRegularFile(path)) {
          entries[directory.relativize(path).toString()] = Files.readString(path)
        }
      }
    }
    return entries
  }

  private fun design(designId: String): PersistedDesignV1 {
    val document =
      DesignDocumentV1(
        schema = "compose-ui-builder/v1",
        id = designId,
        title = designId,
        revision = 0,
        catalogPin = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime"),
        environment =
          DesignEnvironmentV1(
            widthDp = 1280,
            heightDp = 800,
            density = 1.0,
            theme = ThemeV1.DARK,
            locale = "en-GB",
            fontScale = 1.0,
            layoutDirection = LayoutDirectionV1.LTR,
          ),
        roots = listOf("node-1"),
        nodes = mapOf("node-1" to node("node-1")),
      )
    return PersistedDesignV1(
      document = document,
      lastSequence = 0,
      access = DesignAccessControlV1(accessRevision = 0, ownerActorId = "owner"),
      history = listOf(committed("op-1")),
      revisionSnapshots = listOf(RevisionStateV1(document, 0)),
      operationOutcomes = mapOf("op-1" to outcome("op-1")),
      acceptedOperations = mapOf("op-1" to accepted("op-1")),
      tombstones = emptyMap(),
      positions = emptyMap(),
      positionSnapshots = listOf(PositionStateV1(0, emptyMap())),
      createdAtEpochMillis = 1_000,
      updatedAtEpochMillis = 1_000,
      audit = listOf(audit("op-1")),
    )
  }

  private fun node(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private fun tombstone(nodeId: String): NodeTreeSnapshotV1 =
    NodeTreeSnapshotV1(
      rootNodeId = nodeId,
      nodes = mapOf(nodeId to node(nodeId)),
      location = NodeLocationV1(null, null, null),
      positions = emptyMap(),
    )

  private fun committed(operationId: String): CommittedOperationV1 =
    CommittedOperationV1(
      DesignCommandV1("checkout", operationId, "owner", "browser", 0, emptyList()),
      outcomeV1(operationId),
    )

  private fun outcomeV1(operationId: String): AcceptedOutcomeV1 =
    AcceptedOutcomeV1(
      operationId,
      0,
      0,
      "hash",
      idempotentReplay = false,
      documentUpdatedAtEpochMillis = 1_000,
    )

  private fun outcome(operationId: String): OperationOutcomeRecordV1 =
    OperationOutcomeRecordV1("fingerprint-$operationId", outcomeV1(operationId))

  private fun accepted(operationId: String): AcceptedOperationRecordV1 =
    AcceptedOperationRecordV1(
      operationId = operationId,
      actorId = "owner",
      kind = AcceptedKindV1.BATCH,
      committedRevision = 0,
      activeRevision = 0,
      changes = emptyList(),
    )

  private fun audit(operationId: String): AuditRecordV1 =
    AuditRecordV1(
      AuditKindV1.COMMIT,
      "owner",
      "checkout",
      0,
      0,
      operationId,
      null,
      1_000,
    )
}

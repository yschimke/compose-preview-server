package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRecord
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Each design's latest guidelines result (`compose-ui-builder/guidelines-result/v1`), whoever ran
 * it: a person on their own OpenRouter key in the editor, `ui_builder_check_design` on this host's
 * key, or an agent that judged `ui_builder_guidelines_prompt` with its own model and recorded the
 * verdicts. One record per design, replaced on every write — it answers "what does the latest check
 * say", not "what did every check say".
 *
 * Beside the design rather than in it for the reasons [ServeUiBuilderReviewStore] gives: a result
 * is a fact about a revision, and recording it must not move the revision it is about.
 *
 * Who ran it ([DesignGuidelineRecord.ranBy]), when, and which design are stamped by [record] from
 * the caller's credential and the path, never taken from the body.
 */
class ServeUiBuilderGuidelineStore(private val root: Path) {
  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder guidelines root is not a directory: $root" }
  }

  private val lock = Any()

  /** [designId]'s latest record, or null when none has been recorded. */
  fun read(designId: String): DesignGuidelineRecord? {
    val file = fileFor(designId)
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > MAX_FILE_BYTES) null
      else
        GUIDELINE_STORE_JSON.decodeFromString(
          DesignGuidelineRecord.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  /**
   * Validates [record] and stores it as [designId]'s latest, stamped with [ranBy] and now. Verdicts
   * may only answer rules the record says were asked, and every asked rule must be one this host's
   * rule set knows, so a record cannot claim a guideline nobody wrote.
   */
  fun record(
    designId: String,
    ranBy: String,
    record: DesignGuidelineRecord,
    rules: DesignGuidelineRuleSet = DesignGuidelineRuleSet.Bundled,
    /**
     * The design's current revision. A record for a later one would read as current through every
     * edit up to it, so it is refused; null when the caller has already checked the revision.
     */
    currentRevision: Long? = null,
  ): GuidelineWriteResult {
    if (record.revision < 0) return GuidelineWriteResult.Refused("`revision` must not be negative")
    if (currentRevision != null && record.revision > currentRevision) {
      return GuidelineWriteResult.Refused(
        "revision ${record.revision} does not exist yet; the design is at revision $currentRevision"
      )
    }
    val model = record.model.trim()
    if (model.isEmpty() || model.length > MAX_ID_CHARS) {
      return GuidelineWriteResult.Refused("`model` must be 1 to $MAX_ID_CHARS characters")
    }
    if (record.asked.isEmpty()) return GuidelineWriteResult.Refused("`asked` must name a rule")
    if (record.asked.size > MAX_RULES || record.verdicts.size > MAX_RULES) {
      return GuidelineWriteResult.Refused("at most $MAX_RULES rules may be asked and answered")
    }
    val known = rules.rules.mapTo(mutableSetOf()) { it.id }
    val unknown = record.asked.filterNot { it in known }
    if (unknown.isNotEmpty()) {
      return GuidelineWriteResult.Refused(
        "`asked` names rules this host does not know: ${unknown.joinToString(", ")}"
      )
    }
    val asked = record.asked.toSet()
    val stray = record.verdicts.map { it.ruleId }.filterNot { it in asked }
    if (stray.isNotEmpty()) {
      return GuidelineWriteResult.Refused(
        "verdicts answer rules that were not asked: ${stray.distinct().joinToString(", ")}"
      )
    }
    val badVerdict = record.verdicts.firstOrNull { it.verdict !in VERDICTS }
    if (badVerdict != null) {
      return GuidelineWriteResult.Refused(
        "verdict `${badVerdict.verdict}` on ${badVerdict.ruleId} is not one of " +
          VERDICTS.joinToString(", ")
      )
    }
    val stored =
      record.copy(
        designId = designId,
        model = model,
        asked = record.asked.distinct(),
        verdicts =
          record.verdicts
            .distinctBy { it.ruleId }
            .map {
              it.copy(
                confidence = it.confidence.coerceIn(0.0, 1.0),
                nodeIds = it.nodeIds.take(MAX_NODES),
                reason = it.reason.take(MAX_REASON_CHARS),
              )
            },
        ranBy = ranBy,
        recordedAtEpochMillis = System.currentTimeMillis(),
      )
    val encoded = GUIDELINE_STORE_JSON.encodeToString(DesignGuidelineRecord.serializer(), stored)
    if (encoded.length > MAX_FILE_BYTES) {
      return GuidelineWriteResult.Refused("the record is too large to keep")
    }
    synchronized(lock) {
      try {
        val temporary = Files.createTempFile(root, "guidelines", ".tmp")
        try {
          Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
          Files.move(temporary, fileFor(designId), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
          Files.deleteIfExists(temporary)
          throw failure
        }
      } catch (_: IOException) {
        return GuidelineWriteResult.Failed("the guidelines record could not be written to disk")
      }
    }
    return GuidelineWriteResult.Stored(stored)
  }

  /** Forgets [designId]'s record, when the design itself is deleted. */
  fun delete(designId: String): Boolean =
    try {
      Files.deleteIfExists(fileFor(designId))
      true
    } catch (_: IOException) {
      false
    }

  private fun fileFor(designId: String): Path =
    root.resolve(
      MessageDigest.getInstance("SHA-256")
        .digest(designId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".json"
    )

  sealed interface GuidelineWriteResult {
    data class Stored(val record: DesignGuidelineRecord) : GuidelineWriteResult

    data class Refused(val reason: String) : GuidelineWriteResult

    data class Failed(val reason: String) : GuidelineWriteResult
  }

  companion object {
    val VERDICTS = listOf("pass", "fail", "not_applicable")
    const val MAX_RULES = 200
    const val MAX_ID_CHARS = 128
    private const val MAX_NODES = 20
    private const val MAX_REASON_CHARS = 1_000
    private const val MAX_FILE_BYTES: Long = 256L * 1024

    private val GUIDELINE_STORE_JSON = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }
  }
}

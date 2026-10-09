import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Stage the TypeScript Remote Compose player from rc-players' published
 * `remote-compose-player-js-dist` zip as a classpath resource, with
 * `server/src/rc-player/inert-custom-host.js` appended.
 *
 * Both `:server` (`/rc-player/bundle.js`) and `:mcp` (the `.rc` viewer MCP App) play documents
 * they did not write, so both ship the bundle with its custom host made inert; see that file and
 * `server/src/rc-player/README.md`.
 */
abstract class StageRcPlayerJs : DefaultTask() {
  @get:InputFile abstract val archiveFile: RegularFileProperty

  @get:InputFile abstract val shimFile: RegularFileProperty

  /** Where the staged bundle lands under [outputDirectory], e.g. `rc-player/bundle.js`. */
  @get:Input abstract val resourcePath: Property<String>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @get:Inject abstract val archiveOperations: ArchiveOperations

  @TaskAction
  fun stage() {
    val bundle =
      archiveOperations.zipTree(archiveFile).matching { include("bundle.js") }.singleOrNull()
        ?: error("remote-compose-player-js-dist has no bundle.js")
    val out = outputDirectory.get().asFile
    out.deleteRecursively()
    val target = out.resolve(resourcePath.get())
    target.parentFile.mkdirs()
    target.writeBytes(bundle.readBytes() + "\n".toByteArray() + shimFile.get().asFile.readBytes())
  }
}

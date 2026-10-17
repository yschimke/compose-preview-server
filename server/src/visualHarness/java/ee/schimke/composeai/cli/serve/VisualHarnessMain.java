package ee.schimke.composeai.cli.serve;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import kotlin.Unit;

/** Runs the production server against checked-in catalog publications, without GitHub reads. */
public final class VisualHarnessMain {
  public static void main(String[] rawArgs) {
    var fixtures = Path.of(System.getenv("UI_BUILDER_HARNESS_FIXTURES"));
    var args = new ArrayList<>(Arrays.asList(rawArgs));
    var catalogIndex = args.indexOf("--ui-builder-catalogs");
    var catalogs = catalogIndex < 0 ? "m3-catalog" : args.get(catalogIndex + 1);
    args.addAll(Arrays.asList("--catalogs", catalogs, "--catalog-repo", "harness/catalogs"));
    var options = new ServeCommandOptions(args, false, 120L,
        StandaloneServerMainKt::previewIdMatchesStandaloneRequest);
    new ServeRunner(options, StandaloneBuildHost.INSTANCE, discovery -> Unit.INSTANCE, url -> {
      var prefix = "https://raw.githubusercontent.com/harness/catalogs/design-artifacts/";
      if (!url.startsWith(prefix)) return null;
      var parts = url.substring(prefix.length()).split("/", 2);
      if (parts.length != 2 || !Arrays.asList(catalogs.split(",")).contains(parts[0])) return null;
      var system = parts[0];
      if (parts[1].equals("catalog.json")) {
        return ("{\"schema\":\"design-parity-catalog/v1\",\"system\":\"" + system
            + "\",\"uiBuilderFile\":\"ui-builder.json\",\"components\":[]}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      }
      var file = switch (parts[1]) {
        case "ui-builder.json" -> system + ".json";
        default -> null;
      };
      if (file == null) return null;
      try {
        return Files.readAllBytes(fixtures.resolve(file));
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    }).run();
  }
}

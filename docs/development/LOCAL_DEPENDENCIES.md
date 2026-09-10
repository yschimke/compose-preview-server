# Compile the builder against local dependencies

Use this when a change to `compose-preview-contracts` or `compose-ai-tools` needs to run in the
existing UI builder before an upstream release. The upstream checkout builds its own modules;
this repository consumes their normal Maven publications, including JVM and Wasm variants.
Start from the dependency revision currently pinned in `gradle/libs.versions.toml` when prototyping
an extension. Using newer upstream APIs can require consumer changes, just as a release-pin bump
does; the override deliberately leaves those compiler checks intact.

From the server checkout:

```shell
python3 scripts/stage-local-dependency.py \
  --checkout ../compose-preview-contracts --module :ui-builder-protocol
python3 scripts/stage-local-dependency.py \
  --checkout ../compose-ai-tools --module :screen-model

./gradlew -PlocalDependencies=build/local-dependencies/local-dependencies.properties \
  :ui-builder:jvmTest :ui-builder:compileKotlinWasmJs \
  :ui-builder-export:jvmTest :server:test
```

Pass the same `-PlocalDependencies=…` option when building the existing server distribution or
running its development tasks. No alternate frontend is involved. The script does not modify the
upstream checkout's source or publish anything to a remote repository, and no commit is needed.
It runs the upstream Gradle wrapper, so that project's normal build prerequisites still apply.

Repeat staging after editing upstream source. Each run gets a fresh snapshot version, even for
uncommitted edits; failed builds leave the previously selected manifest intact. Success adds the
actual published module identities to the manifest, retaining modules staged by earlier runs.
The selected modules resolve exclusively from the local repository, so missing local artifacts fail
instead of falling back to a release. Omit the property to return to the pinned release versions.

`--output /path/to/staging` chooses a different staging directory. Its `repository/` contains the
publications, `local-dependencies.properties` selects versions, and `builds/` records the source
checkout, Git revision, worktree status and module list for each build. These are build outputs;
keep source changes in their owning repositories.

For a module with upstream project dependencies, repeat `--module :dependency` for every project
that must also be published. The script stages all publications of the selected projects, so do
not choose Android or native modules unless their required toolchains are installed. Gradle plugin
resolution stays on the release pin; this option overrides library dependencies only.

Selecting `:screen-model` in a compose-ai-tools checkout also stages
`:gradle-plugin:preview-discovery`. Both publications contain the shared generator classes, and
the server links both, so their versions must stay together. The consumer rejects a manifest that
selects only one or mixes their builds.

This development path uses Gradle's
[exclusive repository filtering](https://docs.gradle.org/current/userguide/filtering_repository_content.html)
and [dependency resolution rules](https://docs.gradle.org/current/userguide/resolution_rules.html).
It does not change the published module boundaries or introduce a global `mavenLocal()` repository.

# Java version policy

Use the maintained Java releases within the lanes already declared by this
repository. A runtime upgrade is a separate compatibility decision from a
dependency update or a newer LTS becoming available.

| Contract | Selected Java | Reason and enforcement |
| --- | --- | --- |
| Distribution and server/toolchain compatibility | 17 | `java-server` in `gradle/libs.versions.toml`; `:server:checkServerJvmFloor` rejects dependencies requiring a newer class-file version. CI retains Java 17 as `JAVA_HOME` in the compatibility/build lanes. |
| UI-builder render bundle and visual-harness runtime | 21 | `java-ui-builder` records the render bundle's requirement. The daemon rendering that bundle uses the server JVM; Java 17 startup remains supported with PNG/SVG export unavailable. |
| Shipped host container | Temurin 21 | `deploy/image/Dockerfile` provides a runtime satisfying both the distribution and renderer requirements. |

`renovate.json` keeps JDK/JRE dependencies currently on 17 or 21 on that same
major, and keeps the `eclipse-temurin` image on 21. Maintenance releases remain
eligible, including build metadata and Docker tag/digest updates. These rules
also cover Renovate's `java` alias and platform-qualified JDK/JRE package names.
They do not constrain `actions/setup-java` action releases or ordinary Java libraries.
The shared preset also suppresses JDK/JRE major proposals; the local constraints
state the repository's selected lanes independently of that default.

## What the Java 25 proposals showed

[PR #1738](https://github.com/yschimke/compose-preview-server/pull/1738) changed
the visual-harness JDK from `21.0.12+101.0.LTS` to `25.0.4+101.0.LTS`. Its CI
passed, and the PR was closed without merging. This is evidence that the tested
harness lane worked on that JDK; it does not demonstrate a migration of the
distribution floor, all lower-runtime behavior or the production image. There
is no observed JDK 25 failure in that PR to justify calling Java 25 inherently
unsafe. Keeping 21 is the conservative supported-lane decision.

[PR #1741](https://github.com/yschimke/compose-preview-server/pull/1741) is a
different problem: ClassGraph 6 requires Java 25 and removes a scanning API this
server uses. It failed compilation and the Java 17 dependency-floor check.
Its existing Maven ceiling below 6 remains necessary while the distribution
supports 17; raising only the CI JDK does not make those dependencies runnable
on Java 17.

Gradle's [compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)
supports running on Java 25 from Gradle 9.1.0; this repository's wrapper is newer
than that. Therefore the wrapper itself is not a demonstrated blocker. Check
Kotlin/plugins, instrumentation, native rendering and loaded bytecode as well.

## Adopting a newer major

1. Name the lane being migrated and decide explicitly whether the Java 17
   distribution floor changes. Keep lower-runtime coverage while it remains
   supported; do not remove or weaken `checkServerJvmFloor` to pass an update.
2. Confirm Gradle, Kotlin, plugins, render daemon/native libraries and agents
   support the intended JVM. Record versions and representative render evidence.
3. Run the repository checks and visual harness, and verify packaged server
   startup plus UI-builder PNG/SVG export on the selected runtime. If retaining
   Java 17 support, verify its startup/degraded-export behavior too.
4. Align the relevant workflow JDKs, render-bundle requirement and image, or
   document why they differ. Change `java-server` only as an intentional runtime
   contract migration, accounting for the downstream CLI and launched processes.
5. Update the appropriate local Renovate ceiling. If a major should again be
   proposed automatically, append a narrow override to the shared Java rule
   with `enabled: true` and `automerge: false`. Port and verify ClassGraph's API
   separately before removing its ceiling; a JVM change alone is insufficient.

Run the lightweight Renovate pattern regression fixtures with:

```sh
python3 .github/scripts/test_java_version_policy.py
```

These fixtures check the actual Java-specific selectors and allowed-version
patterns. They do not replace Renovate's config validation, runtime-floor checks
or integration/render tests.

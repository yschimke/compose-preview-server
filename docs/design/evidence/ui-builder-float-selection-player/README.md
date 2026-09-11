# Decimal selection: real-player feasibility and correction

The isolated `FloatSelectionJsonTest` uses creation-compose's Float equality operation sequence:
Float comparison → integer expression reading the comparison ID → StateLayout case ordinal.
The test's temporary `floatEquals` parser registration is not an advertised compiler profile.
At this baseline, the production JSON exporter refused decimal selectors. The subsequent
[production integration](../ui-builder-decimal-selection/README.md) uses the shared compiler.

Before the correction, the CMP player's Float writes did not populate the integer view of the
same ID. Every matching decimal case therefore selected the fallback. AndroidX alpha19's
`RemoteComposeState.updateFloat` writes both numeric views. The local player now follows that rule
for constants, expressions, actions, named host updates, data-map results and measured values.
Integer writes continue retaining their original 32-bit value.

[Before: incorrect blue fallback](before.png) · [After: correct red initial case](ordinary-0.png) ·
[After host update: green second case](ordinary-1.png) · [Unknown value: blue fallback](ordinary-2.png).
These are actual player captures of the same fixture. The baseline run uses player main `7b25f43`;
it fails all four render scenarios and the direct AndroidX numeric-storage comparison.

The corrected build passes all five tests. The rendering scenarios cover ordinary decimals,
adjacent Floats, subnormal values and opposite finite extremes, with updates through both cases,
fallback, and back to the first case without recompilation. The reference comparison additionally
checks truncation boundaries and non-finite runtime values against the actual AndroidX class.
The owning player passes 120 runtime tests, 216 Compose tests, its runtime ABI gate and WASM
compilation. The committed player is staged through the normal local dependency manifest; all
953 existing editor tests pass and the WASM/server distribution builds against it. The existing
browser/MCP padding-edit PNG proof also passes with byte-identical output digests and no page
errors; [its verification](browser-regression.json) records that regression check, not decimal
selection in the production UI. This establishes the player prerequisite; the subsequent production integration above closes
compiler-profile support, JSON lowering and browser/MCP decimal export.

The [JSON fixture](ordinary.json), [compiled document](ordinary.rc) and
[verification record](verification.json) accompany the captures. Reproduce from the repository root:

```shell
./gradlew -p experiments/remote-compose-poc \
  -PlocalRcPlayers=/path/to/local/rc-players \
  jvmTest --tests '*FloatSelectionJsonTest'
```

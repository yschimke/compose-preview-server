# Changelog

## [2.3.1](https://github.com/yschimke/compose-preview-server/compare/v2.3.0...v2.3.1) (2026-08-30)


### Bug Fixes

* **serve:** do not read a catalog registry through raw's stale HEAD alias ([#44](https://github.com/yschimke/compose-preview-server/issues/44)) ([1fb3fb0](https://github.com/yschimke/compose-preview-server/commit/1fb3fb03736390b3985ed02204e94dec539f81c0))
* **serve:** read a catalog registry from HEAD first, and correct why ([#46](https://github.com/yschimke/compose-preview-server/issues/46)) ([7daee12](https://github.com/yschimke/compose-preview-server/commit/7daee1243839c125ff3b38228e0c5d02a6397d83))

## [2.3.0](https://github.com/yschimke/compose-preview-server/compare/v2.2.0...v2.3.0) (2026-08-30)


### Features

* **serve:** diff the derived layers across a cross-catalog pair ([#40](https://github.com/yschimke/compose-preview-server/issues/40)) ([e544e22](https://github.com/yschimke/compose-preview-server/commit/e544e22dfb2d017c765bc3584cf829c5257b5c7b))
* **serve:** discover catalogs from a nominated GitHub project ([#43](https://github.com/yschimke/compose-preview-server/issues/43)) ([d32c0e4](https://github.com/yschimke/compose-preview-server/commit/d32c0e4a4b578bbf467cdb23c4011819dbe687d6))


### Bug Fixes

* **wasm-ui:** skip a malformed font family instead of dropping every family ([#42](https://github.com/yschimke/compose-preview-server/issues/42)) ([f8c398b](https://github.com/yschimke/compose-preview-server/commit/f8c398b28147af5a1b4d4f0dad8936799a19348d))

## [2.2.0](https://github.com/yschimke/compose-preview-server/compare/v2.1.0...v2.2.0) (2026-08-30)


### Features

* extract the render host and preview history into :render-host ([#38](https://github.com/yschimke/compose-preview-server/issues/38)) ([78220db](https://github.com/yschimke/compose-preview-server/commit/78220db9317aea09d785f4d8ac895c326386eaed))
* **serve:** publish the design-pages join as JSON ([#36](https://github.com/yschimke/compose-preview-server/issues/36)) ([5cb4976](https://github.com/yschimke/compose-preview-server/commit/5cb497647614adcc2bca541a2c142f1b8be507ab))


### Bug Fixes

* **serve:** pair a cross-catalog sibling cell by cell ([#37](https://github.com/yschimke/compose-preview-server/issues/37)) ([2b3604f](https://github.com/yschimke/compose-preview-server/commit/2b3604f4e4844728d5f6e0dec634bca1ac78364a))

## [2.1.0](https://github.com/yschimke/compose-preview-server/compare/v2.0.0...v2.1.0) (2026-08-30)


### Features

* **serve:** onboard a GitHub project from its URL ([#10](https://github.com/yschimke/compose-preview-server/issues/10)) ([d010680](https://github.com/yschimke/compose-preview-server/commit/d010680892016547e35490ce24920811018140f6))
* **serve:** report the Compose previews in a pasted repository ([#34](https://github.com/yschimke/compose-preview-server/issues/34)) ([9c9c620](https://github.com/yschimke/compose-preview-server/commit/9c9c6202890e5b1083c6a2cebff42b2e154b88ab))


### Bug Fixes

* close the serve-web and native-catalog gaps re-homed from compose-ai-tools ([#32](https://github.com/yschimke/compose-preview-server/issues/32)) ([f2390d9](https://github.com/yschimke/compose-preview-server/commit/f2390d95dad5b20b9177c3a07aeb15a2b8f33ef2))
* **deps:** pin dependencies ([#19](https://github.com/yschimke/compose-preview-server/issues/19)) ([a523cd9](https://github.com/yschimke/compose-preview-server/commit/a523cd940043f626d655579276cfd746965277f6))
* **deps:** update composeai.tools to v1.53.1 ([#23](https://github.com/yschimke/compose-preview-server/issues/23)) ([4bce49c](https://github.com/yschimke/compose-preview-server/commit/4bce49c60fcedce712a6a1f06fe116f31161137d))
* publish the test fixtures under the artifactId's capability ([#22](https://github.com/yschimke/compose-preview-server/issues/22)) ([ddf65ef](https://github.com/yschimke/compose-preview-server/commit/ddf65efaf1609d258404037bd088c0cca216bfe5))

## [2.0.0](https://github.com/yschimke/compose-preview-server/compare/v1.51.0...v2.0.0) (2026-08-29)


### ⚠ BREAKING CHANGES

* version the standalone server from 2.0.0 ([#6](https://github.com/yschimke/compose-preview-server/issues/6))

### Features

* complete preview server release and deployment handoff ([#4](https://github.com/yschimke/compose-preview-server/issues/4)) ([cdbed4a](https://github.com/yschimke/compose-preview-server/commit/cdbed4ad259b32c981bdf2bdb0e8750aca3d8dca))
* establish standalone preview server repository ([be167f4](https://github.com/yschimke/compose-preview-server/commit/be167f4a98ab2fc928df6fbf5b8143c3ee59b776))
* establish standalone preview server repository ([09148d7](https://github.com/yschimke/compose-preview-server/commit/09148d77977bf7fed4f1df116a7e8b81423efb73))
* **figma:** extract native slot manifests ([31b0b80](https://github.com/yschimke/compose-preview-server/commit/31b0b80714f41dcadd2dd7e92cab9b9d4f5daa3a))
* **serve:** add native Wasm catalog and UI composer ([e5fc386](https://github.com/yschimke/compose-preview-server/commit/e5fc386bd0ed4672ea546db30b4f24e70375c0e2))
* sweep recent preview server changes ([7b8042f](https://github.com/yschimke/compose-preview-server/commit/7b8042f6af4877d1b708d60051a239f9f1a79050))
* version the standalone server from 2.0.0 ([#6](https://github.com/yschimke/compose-preview-server/issues/6)) ([3c9f3a9](https://github.com/yschimke/compose-preview-server/commit/3c9f3a9073e5b23de2059dea6427459647e796a8))


### Bug Fixes

* **deps:** consume compose-ai-tools 1.53.0 ([#5](https://github.com/yschimke/compose-preview-server/issues/5)) ([eddcddc](https://github.com/yschimke/compose-preview-server/commit/eddcddc6346ad05f89232558ef9ae61a173133ee))
* **deps:** consume compose-preview-contracts 2.1.0 ([#4798](https://github.com/yschimke/compose-preview-server/issues/4798)) ([6d56e63](https://github.com/yschimke/compose-preview-server/commit/6d56e637b7af57e7ae490db3b2c42062c3a48940))
* resolve visual harness assets from standalone server ([f271ae8](https://github.com/yschimke/compose-preview-server/commit/f271ae8e25dacfac9fbd6e4a3278fdf4abef7998))
* **serve:** snap the bug-report and colour-override fixes from compose-ai-tools ([#8](https://github.com/yschimke/compose-preview-server/issues/8)) ([2238017](https://github.com/yschimke/compose-preview-server/commit/2238017f43e0730f0a733cecdb72efe22d0dd68b))


### Performance Improvements

* split Vue bundles by web surface ([1b32a00](https://github.com/yschimke/compose-preview-server/commit/1b32a00234617364dec2df328c6fb020e04474a1))

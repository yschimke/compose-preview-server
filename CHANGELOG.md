# Changelog

## [2.20.0](https://github.com/yschimke/compose-preview-server/compare/v2.19.1...v2.20.0) (2026-09-04)


### Features

* **serve:** generate the Compose export from the discovered component record ([#236](https://github.com/yschimke/compose-preview-server/issues/236)) ([db56ba6](https://github.com/yschimke/compose-preview-server/commit/db56ba60e42cab169e1f021fe1d456e9566af5b1))
* **spec:** pick a colour off both sides of the spec lane ([#247](https://github.com/yschimke/compose-preview-server/issues/247)) ([20ab165](https://github.com/yschimke/compose-preview-server/commit/20ab1657ba6263639bd838b8966d6b7a0381b2f2))
* **ui-builder:** declare a screen's state when the design is created ([#245](https://github.com/yschimke/compose-preview-server/issues/245)) ([a605548](https://github.com/yschimke/compose-preview-server/commit/a6055489965a34e32a2a9584302b25562f27c8e0))
* **ui-builder:** filter the layers panel, and take every match at once ([#242](https://github.com/yschimke/compose-preview-server/issues/242)) ([9304fd8](https://github.com/yschimke/compose-preview-server/commit/9304fd8a30d79aa19cc575dbddb72f54f5cce36b))
* **ui-builder:** let the canvas run the screen you just wired up ([#243](https://github.com/yschimke/compose-preview-server/issues/243)) ([7d79d69](https://github.com/yschimke/compose-preview-server/commit/7d79d69ef6723b1397d7f2b6b83473f13bf89ddc))
* **ui-builder:** let the inspector see a state binding ([#244](https://github.com/yschimke/compose-preview-server/issues/244)) ([5b98d50](https://github.com/yschimke/compose-preview-server/commit/5b98d5048bbb0ec3fed0001056c3e52f6a804db2))
* **ui-builder:** make the builder usable for designing an interactive screen ([#238](https://github.com/yschimke/compose-preview-server/issues/238)) ([9f5cec2](https://github.com/yschimke/compose-preview-server/commit/9f5cec217e7107bc3e364347900aedff4771333b))
* **ui-builder:** make the catalog's layout properties editable ([#240](https://github.com/yschimke/compose-preview-server/issues/240)) ([7a000ac](https://github.com/yschimke/compose-preview-server/commit/7a000aca9c2566fa283930431b839b1821b37e07))
* **ui-builder:** make the editor's shortcuts findable, from one table ([#239](https://github.com/yschimke/compose-preview-server/issues/239)) ([92b67d0](https://github.com/yschimke/compose-preview-server/commit/92b67d0e0c6e6345e43660c63e26d5c84e66abee))


### Bug Fixes

* **deps:** update compose-ai-tools to v1.71.0 ([#258](https://github.com/yschimke/compose-preview-server/issues/258)) ([b802e4c](https://github.com/yschimke/compose-preview-server/commit/b802e4c134e09d5c6a1db0e1d06dd8680024f5c7))
* **deps:** update dependency ee.schimke.composeai:rc-player-compose to v1.56.1 ([#253](https://github.com/yschimke/compose-preview-server/issues/253)) ([badfabe](https://github.com/yschimke/compose-preview-server/commit/badfabe8865c825a715247ec61a0c59a38a71f15))
* **serve:** combine the preview menu and the catalog menu ([#256](https://github.com/yschimke/compose-preview-server/issues/256)) ([cd2c5b3](https://github.com/yschimke/compose-preview-server/commit/cd2c5b3a1c66901e6ddc487a146f89d1e01a0042))
* **serve:** let a preview grant replay a published player capture ([#246](https://github.com/yschimke/compose-preview-server/issues/246)) ([fec95d5](https://github.com/yschimke/compose-preview-server/commit/fec95d549a1d3e768d60a19b744c0e623aa2d9e6))
* **serve:** refresh the serve-web goldens for the spec lane's colour picker ([#250](https://github.com/yschimke/compose-preview-server/issues/250)) ([5725ff6](https://github.com/yschimke/compose-preview-server/commit/5725ff695673814f5168384fa406f0fcfd14ae0a))
* **serve:** resolve a call's agent grant once, not per gate ([#249](https://github.com/yschimke/compose-preview-server/issues/249)) ([a6951a3](https://github.com/yschimke/compose-preview-server/commit/a6951a3d46016c809a7f7f3f4057d5ae4655abe4))

## [2.19.1](https://github.com/yschimke/compose-preview-server/compare/v2.19.0...v2.19.1) (2026-09-03)


### Bug Fixes

* **serve:** answer unknown when no manifest records the capture player ([#233](https://github.com/yschimke/compose-preview-server/issues/233)) ([cf56799](https://github.com/yschimke/compose-preview-server/commit/cf56799618171d5b1ff68442b63cdd1ab08e2ddd))
* **serve:** make the theme choice per-tab, and apply it uniformly ([#234](https://github.com/yschimke/compose-preview-server/issues/234)) ([9e52e62](https://github.com/yschimke/compose-preview-server/commit/9e52e62f62a19f151f9c31bd05924ba16264b495))


### Performance Improvements

* **serve:** warm a catalog's thumbnails from the page build that missed ([#232](https://github.com/yschimke/compose-preview-server/issues/232)) ([2488c3d](https://github.com/yschimke/compose-preview-server/commit/2488c3d9cf5a9ca59314acfef15fbb9890c00ee3))

## [2.19.0](https://github.com/yschimke/compose-preview-server/compare/v2.18.2...v2.19.0) (2026-09-03)


### Features

* **serve:** band small front-page sections onto a shared row ([#228](https://github.com/yschimke/compose-preview-server/issues/228)) ([8154d86](https://github.com/yschimke/compose-preview-server/commit/8154d864792291d66309e74a484d6277d20f71e0))


### Bug Fixes

* **serve:** make the bare render the CMP Android one, and drop the param ([#226](https://github.com/yschimke/compose-preview-server/issues/226)) ([b5118e6](https://github.com/yschimke/compose-preview-server/commit/b5118e690b5fafe13ed80b45537f6890c35eaa55))


### Performance Improvements

* **serve:** draw the viewer's component drawer from the thumbnail lane ([#229](https://github.com/yschimke/compose-preview-server/issues/229)) ([2a0de4d](https://github.com/yschimke/compose-preview-server/commit/2a0de4dc2e55f167460a0c39f8e3e4e3fc367da3))

## [2.18.2](https://github.com/yschimke/compose-preview-server/compare/v2.18.1...v2.18.2) (2026-09-03)


### Bug Fixes

* **serve:** emit a parity locator from the viewer's report form ([#220](https://github.com/yschimke/compose-preview-server/issues/220)) ([995ee83](https://github.com/yschimke/compose-preview-server/commit/995ee83294bd3716cba3854997287ec25a53d931))
* **serve:** file no parity locator from the viewer's interactive lanes ([#225](https://github.com/yschimke/compose-preview-server/issues/225)) ([cf1606e](https://github.com/yschimke/compose-preview-server/commit/cf1606ee60431557db78e4f4fb0e6501dc385099))
* **serve:** keep a catalog's Remote Compose family on one line ([#219](https://github.com/yschimke/compose-preview-server/issues/219)) ([c263cc5](https://github.com/yschimke/compose-preview-server/commit/c263cc538ae529b71bd42dc914c50a858ce15b55))
* **serve:** keep the daemon's inspect layers and the hero crop's retry ([#224](https://github.com/yschimke/compose-preview-server/issues/224)) ([ab0602f](https://github.com/yschimke/compose-preview-server/commit/ab0602fd23c49528424f542bcd510ee94429c14a))
* **serve:** stop stamping the default player onto every preview url ([#218](https://github.com/yschimke/compose-preview-server/issues/218)) ([22ac32b](https://github.com/yschimke/compose-preview-server/commit/22ac32b5f44e4c04e6e9d3d6511de425ca6cbaea))


### Performance Improvements

* **serve:** let a .annotations request name the layers it will draw ([#227](https://github.com/yschimke/compose-preview-server/issues/227)) ([49cec02](https://github.com/yschimke/compose-preview-server/commit/49cec027cac8b9fce1a8c82724a5d8ff64cbb654))
* stop re-deriving catalog facts and waking daemons for published data ([#221](https://github.com/yschimke/compose-preview-server/issues/221)) ([544cfa1](https://github.com/yschimke/compose-preview-server/commit/544cfa164d6d94466c04e8870b732294e7117a5b))


### Reverts

* "fix(serve): stop stamping the default player onto every preview url" ([#223](https://github.com/yschimke/compose-preview-server/issues/223)) ([6a9e118](https://github.com/yschimke/compose-preview-server/commit/6a9e118a20a005e11806d25094e6743248e5179a))

## [2.18.1](https://github.com/yschimke/compose-preview-server/compare/v2.18.0...v2.18.1) (2026-09-03)


### Bug Fixes

* **deps:** take the compose-ai-tools release that names a curved run's font ([#210](https://github.com/yschimke/compose-preview-server/issues/210)) ([c10b966](https://github.com/yschimke/compose-preview-server/commit/c10b966cf84d11685102fcf24f482c223f506b0c))

## [2.18.0](https://github.com/yschimke/compose-preview-server/compare/v2.17.0...v2.18.0) (2026-09-03)


### Features

* **serve:** bake the cmp-jvm sidecar and give the java player a column ([#206](https://github.com/yschimke/compose-preview-server/issues/206)) ([6f8735d](https://github.com/yschimke/compose-preview-server/commit/6f8735d9a37373e6122324f96b28c0f2a28c5df4))


### Bug Fixes

* **serve:** cache a bare player selection like the published bytes it is ([#209](https://github.com/yschimke/compose-preview-server/issues/209)) ([eaf9f19](https://github.com/yschimke/compose-preview-server/commit/eaf9f192668195042e0b9376ec18021c60ec043b))

## [2.17.0](https://github.com/yschimke/compose-preview-server/compare/v2.16.1...v2.17.0) (2026-09-02)


### Features

* **serve:** fill the player wall from the document, not only the run ([#199](https://github.com/yschimke/compose-preview-server/issues/199)) ([dad03e1](https://github.com/yschimke/compose-preview-server/commit/dad03e1f7f3f7e24c53761c08b64a3001a57f0ed))


### Bug Fixes

* **serve:** attribute an import by the project it declares ([#203](https://github.com/yschimke/compose-preview-server/issues/203)) ([0132362](https://github.com/yschimke/compose-preview-server/commit/01323627647c7a6b87b7a7d70f2d2a9a8aa0649e))
* **serve:** follow the baked twin card instead of re-rendering a theme ([#192](https://github.com/yschimke/compose-preview-server/issues/192)) ([3839173](https://github.com/yschimke/compose-preview-server/commit/3839173679474d147a016d3a561ec0187c9d7164))
* **serve:** name the coordinate that resolved to the wrong bytes when a lane trips ([#193](https://github.com/yschimke/compose-preview-server/issues/193)) ([188f0be](https://github.com/yschimke/compose-preview-server/commit/188f0be306ef6b706046f17406467eb40fdad678))
* **serve:** never fill a live column that duplicates the baked one ([#200](https://github.com/yschimke/compose-preview-server/issues/200)) ([45857d9](https://github.com/yschimke/compose-preview-server/commit/45857d937ad12a6af76bb3087962df74db35f34e))
* **serve:** say which of the two reasons a player has no column ([#202](https://github.com/yschimke/compose-preview-server/issues/202)) ([fa8f898](https://github.com/yschimke/compose-preview-server/commit/fa8f898b0ef17879b43cd49471060f12d7d1184d))

## [2.16.1](https://github.com/yschimke/compose-preview-server/compare/v2.16.0...v2.16.1) (2026-09-02)


### Bug Fixes

* **deps:** update compose-ai-tools ([#190](https://github.com/yschimke/compose-preview-server/issues/190)) ([38da259](https://github.com/yschimke/compose-preview-server/commit/38da2593038500a5b4ce2e9017c8124451a344bb))
* **reporting:** file server bugs against compose-preview-server ([#183](https://github.com/yschimke/compose-preview-server/issues/183)) ([efa41c7](https://github.com/yschimke/compose-preview-server/commit/efa41c7f1b3a0132ea4098d6c00788b673f9dee6))
* **serve:** keep the design page's lane controls on screen ([#186](https://github.com/yschimke/compose-preview-server/issues/186)) ([80daf82](https://github.com/yschimke/compose-preview-server/commit/80daf821a8d171f98768bb9fe2c0e663cb796524))
* **serve:** name the Remote Compose players a run left out ([#188](https://github.com/yschimke/compose-preview-server/issues/188)) ([f8a88a0](https://github.com/yschimke/compose-preview-server/commit/f8a88a0a71c53950cc11b1cc7e6618ab56cf40cc))
* **serve:** replay the baked sticker for an untagged variant's own theme ([#185](https://github.com/yschimke/compose-preview-server/issues/185)) ([ca3539f](https://github.com/yschimke/compose-preview-server/commit/ca3539f83590bde6afff305369653505534c35d1))

## [2.16.0](https://github.com/yschimke/compose-preview-server/compare/v2.15.0...v2.16.0) (2026-09-02)


### Features

* **serve:** add authenticated catalog MCP ([#179](https://github.com/yschimke/compose-preview-server/issues/179)) ([363f8d2](https://github.com/yschimke/compose-preview-server/commit/363f8d221f6d4498ff41d16156f9baff71054e3c))


### Bug Fixes

* **reporting:** scope preview issue metadata ([#174](https://github.com/yschimke/compose-preview-server/issues/174)) ([9f5f295](https://github.com/yschimke/compose-preview-server/commit/9f5f2954fc7500a7c0ae140da5311dec553e7ad5))

## [2.15.0](https://github.com/yschimke/compose-preview-server/compare/v2.14.0...v2.15.0) (2026-09-02)


### Features

* **ui-builder:** add website design creation flow ([#167](https://github.com/yschimke/compose-preview-server/issues/167)) ([addb694](https://github.com/yschimke/compose-preview-server/commit/addb694482b3beadae052e8bbbb2968f6f73a692))


### Bug Fixes

* **deps:** update compose-ai-tools ([#169](https://github.com/yschimke/compose-preview-server/issues/169)) ([15026f4](https://github.com/yschimke/compose-preview-server/commit/15026f458246f1406ef1d4bc99114839837d4265))
* **deps:** update compose-ai-tools ([#176](https://github.com/yschimke/compose-preview-server/issues/176)) ([d0cd7e3](https://github.com/yschimke/compose-preview-server/commit/d0cd7e3dfe401c8ca01b05f0f0f54a590b7bdf1d))
* **serve:** upload catalog report captures ([#172](https://github.com/yschimke/compose-preview-server/issues/172)) ([d6407e0](https://github.com/yschimke/compose-preview-server/commit/d6407e072cb1784db71ce7addb72edb7872349b4))
* **ui-builder:** select an enabled creation catalog ([#171](https://github.com/yschimke/compose-preview-server/issues/171)) ([4f81ce3](https://github.com/yschimke/compose-preview-server/commit/4f81ce3666581f150250bb8e01f8f9437ebbd0c4))

## [2.14.0](https://github.com/yschimke/compose-preview-server/compare/v2.13.0...v2.14.0) (2026-09-01)


### Features

* **serve:** inspect component slots and properties ([#166](https://github.com/yschimke/compose-preview-server/issues/166)) ([e01e641](https://github.com/yschimke/compose-preview-server/commit/e01e64112567369153887e47252113172626b170))
* **ui-builder:** add catalog-scoped instances and Wear scaffolds ([#164](https://github.com/yschimke/compose-preview-server/issues/164)) ([83bc80e](https://github.com/yschimke/compose-preview-server/commit/83bc80e4c7be65b86473687a8c059aea121c0061))
* **ui-builder:** compose Remote Compose documents ([#163](https://github.com/yschimke/compose-preview-server/issues/163)) ([ddcc46e](https://github.com/yschimke/compose-preview-server/commit/ddcc46e97969f2203fe8679ec76a000f62c4d976))


### Bug Fixes

* **ui-builder:** hide theme metadata from properties ([#162](https://github.com/yschimke/compose-preview-server/issues/162)) ([3b1ceef](https://github.com/yschimke/compose-preview-server/commit/3b1ceef634871411368d0510d15f50f203ba36b7))
* **wasm:** gate native catalog by published version ([#165](https://github.com/yschimke/compose-preview-server/issues/165)) ([7562698](https://github.com/yschimke/compose-preview-server/commit/7562698754b6e4b186572f297f2d743b205c3807))

## [2.13.0](https://github.com/yschimke/compose-preview-server/compare/v2.12.0...v2.13.0) (2026-09-01)


### Features

* **ui-builder:** add Google icon property editing ([#153](https://github.com/yschimke/compose-preview-server/issues/153)) ([c05a79c](https://github.com/yschimke/compose-preview-server/commit/c05a79cb5b1152d0e051aadf3fe8302fedfe1457))
* **ui-builder:** add top-level theme builder ([#160](https://github.com/yschimke/compose-preview-server/issues/160)) ([369ea03](https://github.com/yschimke/compose-preview-server/commit/369ea03cd0cf4f9faee8a88f92f16622404af949))


### Bug Fixes

* **ui-builder:** make editor usable on mobile ([#158](https://github.com/yschimke/compose-preview-server/issues/158)) ([6153a90](https://github.com/yschimke/compose-preview-server/commit/6153a90f4219523792ea1134f7ca6076fe2689b2))

## [2.12.0](https://github.com/yschimke/compose-preview-server/compare/v2.11.0...v2.12.0) (2026-09-01)


### Features

* **ui-builder:** add screen environment controls ([#152](https://github.com/yschimke/compose-preview-server/issues/152)) ([b6d200b](https://github.com/yschimke/compose-preview-server/commit/b6d200bb9ae756306d8c57efcc2981a5aa0ed11b))
* **ui-builder:** add typed authoring inspector ([#150](https://github.com/yschimke/compose-preview-server/issues/150)) ([2362ba3](https://github.com/yschimke/compose-preview-server/commit/2362ba3c34cc23f1a470fe684becf30405a50161))


### Bug Fixes

* publish maximum-node design pages ([#149](https://github.com/yschimke/compose-preview-server/issues/149)) ([cd2d420](https://github.com/yschimke/compose-preview-server/commit/cd2d4208887c4317882fdf9e7fbec6d9af18ab47))
* **ui-builder:** replace renderer input shortcuts ([#151](https://github.com/yschimke/compose-preview-server/issues/151)) ([fdf7f02](https://github.com/yschimke/compose-preview-server/commit/fdf7f024af656375708556d0c797bb2fee7b78b5))

## [2.11.0](https://github.com/yschimke/compose-preview-server/compare/v2.10.0...v2.11.0) (2026-09-01)


### Features

* **serve:** compare paired catalogs in bulk ([#138](https://github.com/yschimke/compose-preview-server/issues/138)) ([ba58c1c](https://github.com/yschimke/compose-preview-server/commit/ba58c1cc0a4a1c6e792ad8e4fb3096fdc4e0e4ba))
* **ui-builder:** forward sandbox renderer input ([#140](https://github.com/yschimke/compose-preview-server/issues/140)) ([dbdbee5](https://github.com/yschimke/compose-preview-server/commit/dbdbee5e05b039bb35b39fb8800bc954d744e0d9))


### Bug Fixes

* **deploy:** align preview host daemon sidecars ([#139](https://github.com/yschimke/compose-preview-server/issues/139)) ([93b08b2](https://github.com/yschimke/compose-preview-server/commit/93b08b224089467902c105f0cd82c52e825f90cc))
* **deps:** update compose-ai-tools ([#144](https://github.com/yschimke/compose-preview-server/issues/144)) ([e6f102b](https://github.com/yschimke/compose-preview-server/commit/e6f102b8c4730b303c75b4ff40209f430fd09e8c))
* **deps:** update compose-preview-contracts to v2.3.0 ([#145](https://github.com/yschimke/compose-preview-server/issues/145)) ([4620b72](https://github.com/yschimke/compose-preview-server/commit/4620b72d6bed9d6f04b529e67142dacdfe259315))
* **deps:** update design-parity packages to v1.0.4 ([#142](https://github.com/yschimke/compose-preview-server/issues/142)) ([c25920f](https://github.com/yschimke/compose-preview-server/commit/c25920fdc815b148db9236ec1549e68f74471a2b))

## [2.10.0](https://github.com/yschimke/compose-preview-server/compare/v2.9.0...v2.10.0) (2026-09-01)


### Features

* **serve:** compare paired catalog references ([#137](https://github.com/yschimke/compose-preview-server/issues/137)) ([5d70daa](https://github.com/yschimke/compose-preview-server/commit/5d70daa07504cc13fedaf7302ca17f3102f97684))


### Bug Fixes

* **ui-builder:** consolidate mutable test clock ([2b7da62](https://github.com/yschimke/compose-preview-server/commit/2b7da6278a487759d088e97ef76581baaa149e82))

## [2.9.0](https://github.com/yschimke/compose-preview-server/compare/v2.8.0...v2.9.0) (2026-09-01)


### Features

* **ui-builder:** add recoverable persistence migration ([4bacc28](https://github.com/yschimke/compose-preview-server/commit/4bacc28b104e4fb45324b002d60f01b9d2d4db88))
* **ui-builder:** add sandboxed renderer runtime ([d0da863](https://github.com/yschimke/compose-preview-server/commit/d0da863958a8603996fe57ec2f29aa40a4f423c8))
* **ui-builder:** bound runtime pressure ([fd68850](https://github.com/yschimke/compose-preview-server/commit/fd688501132e6afd32e12c6a8d5119ff4d563d65))
* **ui-builder:** compile generated previews ([fd7ebfb](https://github.com/yschimke/compose-preview-server/commit/fd7ebfbef617dec4a06b398d80a5ea7b4c923042))
* **ui-builder:** enforce runtime quotas ([a99b124](https://github.com/yschimke/compose-preview-server/commit/a99b124de04480081c917e6373405b8e4b2e322f))
* **ui-builder:** host pinned renderer runtimes ([04f9492](https://github.com/yschimke/compose-preview-server/commit/04f9492b631b05df8e41e03c6f64642615f62677))
* **ui-builder:** show live collaborator presence ([a37cf07](https://github.com/yschimke/compose-preview-server/commit/a37cf07e37c176daba8320e4afb853f7d2a80d64))


### Bug Fixes

* reject truncated catalog inventories ([#134](https://github.com/yschimke/compose-preview-server/issues/134)) ([b34d2f9](https://github.com/yschimke/compose-preview-server/commit/b34d2f9a1be9e0cb16866ad92db9b1614c90c459))
* **server:** package desktop render sidecars ([92acd7e](https://github.com/yschimke/compose-preview-server/commit/92acd7e194157146cc8a8d5a059d255be4664f69))
* **ui-builder:** align compact Jetcaster fidelity ([80af87d](https://github.com/yschimke/compose-preview-server/commit/80af87d6cd8e7ed988d82bbab3a511c1c950c571))
* **ui-builder:** authenticate live browser sessions ([c2b1b60](https://github.com/yschimke/compose-preview-server/commit/c2b1b608fdd6076e33b3f909edb50061a55bab51))
* **ui-builder:** improve Jetcaster detail fidelity ([c16c96c](https://github.com/yschimke/compose-preview-server/commit/c16c96cc4b05c7c09e3bb6134f51f63f06f13a1e))

## [2.8.0](https://github.com/yschimke/compose-preview-server/compare/v2.7.0...v2.8.0) (2026-08-31)


### Features

* **ui-builder:** add authenticated design transport ([#103](https://github.com/yschimke/compose-preview-server/issues/103)) ([cdb93f0](https://github.com/yschimke/compose-preview-server/commit/cdb93f0c6ce662c34f08c8ec033b36a4bf002c32))
* **ui-builder:** add editor operation controls ([#100](https://github.com/yschimke/compose-preview-server/issues/100)) ([c043e65](https://github.com/yschimke/compose-preview-server/commit/c043e65720d15fd0b2383aa925c45bebb43ee6c1))
* **ui-builder:** add persistent collaboration service ([#106](https://github.com/yschimke/compose-preview-server/issues/106)) ([c4b844a](https://github.com/yschimke/compose-preview-server/commit/c4b844aaa2dfa8d483e86b4c7c039e43ed81e14d))
* **ui-builder:** add protocol client adapter ([#107](https://github.com/yschimke/compose-preview-server/issues/107)) ([f4092fd](https://github.com/yschimke/compose-preview-server/commit/f4092fdb2bcbebfd1286a097c904a37814ebc41f))
* **ui-builder:** add protocol service foundation ([#101](https://github.com/yschimke/compose-preview-server/issues/101)) ([cacf436](https://github.com/yschimke/compose-preview-server/commit/cacf436d5b82921b380a81dfb8fbd67bd9fc4b64))
* **ui-builder:** connect live browser sessions ([#109](https://github.com/yschimke/compose-preview-server/issues/109)) ([9075b2e](https://github.com/yschimke/compose-preview-server/commit/9075b2eaf281e524334f10c676a8025b57795763))
* **ui-builder:** share project-owned offline artwork ([#98](https://github.com/yschimke/compose-preview-server/issues/98)) ([d754472](https://github.com/yschimke/compose-preview-server/commit/d75447287c8a9522f36fb5d1c094a142b21ec13c))
* **ui-builder:** wire production persistence and exports ([#111](https://github.com/yschimke/compose-preview-server/issues/111)) ([bfe2099](https://github.com/yschimke/compose-preview-server/commit/bfe2099ad4afce708cec7d857fbf539085c1ec21))


### Bug Fixes

* **serve:** recover indexed image revision history ([#110](https://github.com/yschimke/compose-preview-server/issues/110)) ([0dfa8f4](https://github.com/yschimke/compose-preview-server/commit/0dfa8f41b35ba2f13a16a2bc4938bcf6587245f0))
* **ui-builder:** improve Jetcaster render fidelity ([#112](https://github.com/yschimke/compose-preview-server/issues/112)) ([174ed63](https://github.com/yschimke/compose-preview-server/commit/174ed63219765742102805a16552be3ba9336ab5))
* **ui-builder:** make restart exports deterministic ([#99](https://github.com/yschimke/compose-preview-server/issues/99)) ([d8757bb](https://github.com/yschimke/compose-preview-server/commit/d8757bb534258131e5e37f8accd45ac9b3093f37))
* **ui-builder:** preserve SVG typography provenance ([#105](https://github.com/yschimke/compose-preview-server/issues/105)) ([ae4da3c](https://github.com/yschimke/compose-preview-server/commit/ae4da3c4efa0c61b2c1fe519b38dcb7aa8545888))

## [2.7.0](https://github.com/yschimke/compose-preview-server/compare/v2.6.0...v2.7.0) (2026-08-31)


### Features

* **ui-builder:** add interactive Wasm editor ([#96](https://github.com/yschimke/compose-preview-server/issues/96)) ([8ddc7e2](https://github.com/yschimke/compose-preview-server/commit/8ddc7e2a13f25883658aa3f8dd150c56cd13f863))
* **ui-builder:** advance gate zero ([#88](https://github.com/yschimke/compose-preview-server/issues/88)) ([52c3e60](https://github.com/yschimke/compose-preview-server/commit/52c3e6078c1a33ba71a0aad3d233edff41964e43))
* **ui-builder:** harden SVG raster provenance ([#92](https://github.com/yschimke/compose-preview-server/issues/92)) ([28e7aee](https://github.com/yschimke/compose-preview-server/commit/28e7aee456de20f0e52324fbf024a91afaf250f2))
* **ui-builder:** host standalone preview ([#89](https://github.com/yschimke/compose-preview-server/issues/89)) ([ad59438](https://github.com/yschimke/compose-preview-server/commit/ad594385bbb973d510184b91b982a78a705a77d6))
* **ui-builder:** persist collaborative designs ([#91](https://github.com/yschimke/compose-preview-server/issues/91)) ([9e3f232](https://github.com/yschimke/compose-preview-server/commit/9e3f2327fea7d8798540caf901a2f23bfa1e8824))
* **ui-builder:** verify generated Compose export ([#93](https://github.com/yschimke/compose-preview-server/issues/93)) ([d563182](https://github.com/yschimke/compose-preview-server/commit/d563182c3d2d50457caace7d262c7298302aceb0))


### Bug Fixes

* **ui-builder:** preserve generated Compose fields ([#95](https://github.com/yschimke/compose-preview-server/issues/95)) ([7edb80b](https://github.com/yschimke/compose-preview-server/commit/7edb80be9adccf059af8f5f8bcb79c2b3e6b0f2c))
* **ui-builder:** serialize subscriber delivery ([#97](https://github.com/yschimke/compose-preview-server/issues/97)) ([c9a6a38](https://github.com/yschimke/compose-preview-server/commit/c9a6a385882e1838932a8e43ca6d1de2d89dbe84))

## [2.6.0](https://github.com/yschimke/compose-preview-server/compare/v2.5.0...v2.6.0) (2026-08-31)


### Features

* **serve:** add WebXR spatial preview viewer ([#81](https://github.com/yschimke/compose-preview-server/issues/81)) ([e4b7fdd](https://github.com/yschimke/compose-preview-server/commit/e4b7fdd9849ed2bd268d12b33f578b39025ce8f6))
* **ui-builder:** add native Confetti render slice ([#80](https://github.com/yschimke/compose-preview-server/issues/80)) ([9d0bae8](https://github.com/yschimke/compose-preview-server/commit/9d0bae8c128a506d10b1c280791aaee903a3b6da))
* **ui-builder:** add wave one foundations ([#86](https://github.com/yschimke/compose-preview-server/issues/86)) ([aa3c50d](https://github.com/yschimke/compose-preview-server/commit/aa3c50d5aa8773b79fcc407e6d48c6655320c1b7))
* **ui-builder:** adopt Jetcaster benchmark ([#82](https://github.com/yschimke/compose-preview-server/issues/82)) ([cf6468f](https://github.com/yschimke/compose-preview-server/commit/cf6468fbcd531a28574b964ef49f2e08e3202a47))
* **ui-builder:** render Jetcaster benchmark ([#84](https://github.com/yschimke/compose-preview-server/issues/84)) ([f79652f](https://github.com/yschimke/compose-preview-server/commit/f79652f00bc4375ebbc364820689f3a4ebb43d72))


### Bug Fixes

* **serve:** clear Kotlin compiler warnings ([#87](https://github.com/yschimke/compose-preview-server/issues/87)) ([fe2e59b](https://github.com/yschimke/compose-preview-server/commit/fe2e59bd3955804ea5ee62843778bcba9e9d03fe))
* **serve:** re-publish a registry catalog whose entry changed ([#78](https://github.com/yschimke/compose-preview-server/issues/78)) ([099661e](https://github.com/yschimke/compose-preview-server/commit/099661e522d1ff875acfd4438ec8cdb8fa00f9d5))
* **serve:** reserve spatial route ([#85](https://github.com/yschimke/compose-preview-server/issues/85)) ([0377b28](https://github.com/yschimke/compose-preview-server/commit/0377b28890a7faa4acd201a5613ad3a9ea174415))

## [2.5.0](https://github.com/yschimke/compose-preview-server/compare/v2.4.0...v2.5.0) (2026-08-31)


### Features

* **publish-config:** retire what the committed config no longer declares ([#75](https://github.com/yschimke/compose-preview-server/issues/75)) ([c3b5697](https://github.com/yschimke/compose-preview-server/commit/c3b5697394492e35f70f60bacc4343840119f78c))
* **wasm-ui:** add permalink navigation history ([#73](https://github.com/yschimke/compose-preview-server/issues/73)) ([274a105](https://github.com/yschimke/compose-preview-server/commit/274a105c7dee4cceb942d8eea7fc9b9205aae0c3))
* **wasm-ui:** preserve preview controls in permalinks ([#76](https://github.com/yschimke/compose-preview-server/issues/76)) ([75e54b2](https://github.com/yschimke/compose-preview-server/commit/75e54b2080f8929d1fdb9295b07b7023dce452d9))
* **wasm-ui:** scope browser routes to catalogs ([#71](https://github.com/yschimke/compose-preview-server/issues/71)) ([f7484e8](https://github.com/yschimke/compose-preview-server/commit/f7484e81d301d857a9e386f145ea6b4538093504))


### Bug Fixes

* **ci:** re-publish config when the reconcile script itself changes ([#77](https://github.com/yschimke/compose-preview-server/issues/77)) ([27d8df7](https://github.com/yschimke/compose-preview-server/commit/27d8df7f94ee0c9d9ea5096ba4509b42befc4f98))

## [2.4.0](https://github.com/yschimke/compose-preview-server/compare/v2.3.4...v2.4.0) (2026-08-31)


### Features

* **serve:** attribute an imported catalog to the project it came from ([#67](https://github.com/yschimke/compose-preview-server/issues/67)) ([c57bccf](https://github.com/yschimke/compose-preview-server/commit/c57bccf9cfee4b8f28945aa29a8fda2d4afa50ff))
* **serve:** report the catalog-registry nomination on /status ([#63](https://github.com/yschimke/compose-preview-server/issues/63)) ([194a151](https://github.com/yschimke/compose-preview-server/commit/194a1511a122854095817fff45226ba4d2697163))


### Bug Fixes

* **deploy:** restore the daemon sidecar flags a replaced JAVA_TOOL_OPTIONS drops ([#62](https://github.com/yschimke/compose-preview-server/issues/62)) ([64461fc](https://github.com/yschimke/compose-preview-server/commit/64461fc98ed276462a949de28f0cda7ac5c75868))
* **deploy:** serve the imported catalogs preview.coo.ee is missing ([#64](https://github.com/yschimke/compose-preview-server/issues/64)) ([904344d](https://github.com/yschimke/compose-preview-server/commit/904344d6af62b631bcdca7749f01f95db74d54c0))
* **viewer:** move the spec lane's pair when the compare source changes ([#66](https://github.com/yschimke/compose-preview-server/issues/66)) ([39efe43](https://github.com/yschimke/compose-preview-server/commit/39efe4377528dff5d795df8938de4ef135ba776a))

## [2.3.4](https://github.com/yschimke/compose-preview-server/compare/v2.3.3...v2.3.4) (2026-08-31)


### Bug Fixes

* **deps:** update design-parity packages to v1 ([#59](https://github.com/yschimke/compose-preview-server/issues/59)) ([ce3a611](https://github.com/yschimke/compose-preview-server/commit/ce3a611fe04fd7768c94b2054896a3ae348265c9))
* **serve:** say why a catalog's live lane could not start ([#61](https://github.com/yschimke/compose-preview-server/issues/61)) ([b048dbe](https://github.com/yschimke/compose-preview-server/commit/b048dbefb26402b7903b6ec4d92c5ef6b9e63e3a))

## [2.3.3](https://github.com/yschimke/compose-preview-server/compare/v2.3.2...v2.3.3) (2026-08-31)


### Bug Fixes

* **deploy:** bake the desktop live-daemon sidecar into the preview-host image ([#55](https://github.com/yschimke/compose-preview-server/issues/55)) ([9446061](https://github.com/yschimke/compose-preview-server/commit/9446061e8a6f8f91dcf500ef5b59624469bbb239))

## [2.3.2](https://github.com/yschimke/compose-preview-server/compare/v2.3.1...v2.3.2) (2026-08-31)


### Bug Fixes

* **deps:** update composeai.tools to v1.54.0 ([#51](https://github.com/yschimke/compose-preview-server/issues/51)) ([6f8613c](https://github.com/yschimke/compose-preview-server/commit/6f8613c096b5b70e7f5415fcb27545945b999f05))
* **deps:** update design-parity packages to v0.1.76 ([#50](https://github.com/yschimke/compose-preview-server/issues/50)) ([ff50d0b](https://github.com/yschimke/compose-preview-server/commit/ff50d0bd480bd394bfaba7b0ca4b6acf57cbd4eb))
* **serve:** re-verify a catalog when its producer becomes trusted ([#48](https://github.com/yschimke/compose-preview-server/issues/48)) ([4bf0809](https://github.com/yschimke/compose-preview-server/commit/4bf08094f7c84398465108df89b27e351361d4b2))
* **serve:** say why a catalog registry contributed nothing ([#49](https://github.com/yschimke/compose-preview-server/issues/49)) ([6d85209](https://github.com/yschimke/compose-preview-server/commit/6d852091ba591f78dca0fd024e5220d1bc97cad3))

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

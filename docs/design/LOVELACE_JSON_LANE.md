# Lovelace host integration

The server delegates a `lovelace` catalog's JSON export to UI Builder's
`LovelaceConfigExporter`. It returns an application/json UTF-8 artifact with the normal revision
and content digest evidence. Other catalogs/formats continue through the existing executor chain.
The shared builder validates lossless config projection; server tests assert delegation, refusal
propagation and the host artifact invariants rather than restating Lovelace output.

`deploy/preview.coo.ee/catalogs.json` serves harc's declaration-only Lovelace catalog from
`yschimke/homeassistant-remotecompose-out`, branch `design-artifacts/lovelace`. It is a UI Builder
catalog, not a renderable component inventory. Harc remains the owner/publisher. Its actual card
preview entry lives in the separate `homeassistant-remotecompose` catalog and is rendered through
the existing POST render endpoint with host-supplied override knobs. No HA credentials or save
logic belongs to this server or to UI Builder.

Release the builder implementation before resolving the new pin in a default server build. After
the server release, update harc's bundled preview-server archive version and deploy through its
established design-server image workflow. Catalog configuration is published through this
repository's existing preview configuration workflow. Verify the harc dashboard host using its
fixture; no real user's dashboard needs to be modified for deployment verification.

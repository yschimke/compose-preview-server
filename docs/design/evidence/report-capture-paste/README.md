# `/report-bug` on a host that cannot host captures — issue #556

`GET /report-bug`, rendered from the committed page fixture
(`preview-harness/fixtures/pages/serve-report-bug.html`) with one capture seeded into
`sessionStorage` for the page the report names, and `GET /images/capability` answering
403 — which is what an open host answers every anonymous visitor, because the image
lane admits only a browser session naming a login with access to the image repository.

- `before.png` — the page promises the capture is "embedded in the report
  automatically", and the pile says nothing. Neither is true here: the report opens on
  GitHub with an empty Screenshot section.
- `after.png` — the prose says what this host will actually do, and the pile carries the
  standing note. The third half of the change is not visible here: the hand-off also
  marks the paste spot inside the issue body it hands to GitHub.

Regenerate by serving the fixture with the real serve assets and a 403 on
`/images/capability`; the page is captured by the harness's `pages-snapshot` spec, so
any later change to it is diffed automatically.

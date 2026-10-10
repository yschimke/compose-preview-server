# Compact audit controls fit the MCP frame

Chromium captures from the production MCP viewer in the page harness's real
`openLiveViewer` protocol fixture, with a synthetic tall PNG and no advertised
`containerDimensions`. The host's initial iframe is deliberately tall so the
viewer must request its own default 480px budget. This is layout evidence,
not a live Compose render or a guidelines review.

Before, an automatically opened copy prompt enlarged the card, and review
controls were omitted from the image's fitting budget. After, the copy prompt
starts collapsed, empty action rows are hidden and compact review controls
share the budget with the image. Result-location help lives inside the copy
fallback, including its initial copy instruction, and an empty status row takes no space. Expanding a prompt or reading saved findings
can still add content below the fold; failed messaging opens the copy fallback.

The existing default-height and variant-switch/no-scroll contracts reproduce
both CI failures before the change and pass after it. The audit-action tests
also verify that the collapsed fallback opens and its full request is selectable.

Validation: the full 288-case page run passed 287 cases before the last narrow-card
spacing correction. All 72 affected viewer cases and all 11 audit-action cases
pass after that correction, including the unchanged minimum-image-height check.

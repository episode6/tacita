---
name: update-algorithm-doc-skill
description: >-
  Policy requiring docs/ALGORITHM.md (the algorithm research & validation history) to be
  updated whenever the download/ad-cutting process is explored or changed. Use when
  modifying AdCutter, Mp3SegmentParser, Id3ChapterShifter, Downloader or the
  Tacita.downloadPodcast orchestration (matching/alignment logic, guards, reference
  strategy, UA/tier handling, frame parsing), when running experiments against real
  podcast feeds/servings, or when evaluating a new detection/classification idea — even
  if the exploration fails or no code lands.
---

# Keep the Algorithm Doc Current

`docs/ALGORITHM.md` is the empirical record behind tacita's ad-cutting design: the serving
model, the approaches that failed (and how they were disproven), the shipped design's
invariants, measured results, and the validation playbook. Its target audience is a future
agent trying to improve the library without re-losing knowledge that was expensive to gain
(two earlier designs were reverted only after a human ear-verified they cut real show
content).

## The policy

Any time the download/ad-cut *process* is explored or changed, update `docs/ALGORITHM.md`
in the same branch/PR:

- **Algorithm changes** (alignment, matching, snapping, guards, chapter shifting,
  reference strategy, UA/tier behavior): update the shipped-design section and, if
  thresholds or invariants moved, say what evidence justified the move.
- **Explorations and experiments** — including failed ones and ideas rejected without
  implementation: record what was tried, against which feeds/servings, what the outcome
  was, and why it did or didn't hold. Negative results are the most valuable entries in
  the doc; dead ends that aren't written down get re-implemented.
- **New empirical facts** about hosts/serving behavior (new tier observed, fill rotation
  timing, a host changing its stitching): update the serving-model section with the
  evidence.
- **New measured results** (real-pair verifications, decode checks): append to the
  results table.

## Non-negotiables when editing the doc

- Never delete the dead-ends section or weaken the under-cut invariant description; if
  the invariant itself is deliberately changed, document the old one, the new one, and
  the validation that justified the change.
- Keep entries dated and tied to evidence (episode/feed, byte counts, durations) — the
  doc's value is that its claims are checkable.
- The ear-check rule stands: no new ad map or classifier is "verified" by byte analysis
  alone. Record who/what confirmed cut points.

## Relationship to other checks

`scripts/verify-docs-updated.sh` (and the verify-docs CI workflow) only require *some*
docs change alongside code changes — they will not notice that an algorithm change skipped
ALGORITHM.md specifically. Meeting this skill's policy is the agent's responsibility.

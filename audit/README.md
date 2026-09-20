# Documentation audit

[Architecture map](../SOTA-BEST-MIXED-AGENT.md) · [Review](../REVIEW.md) · [Change record](../CHANGELOG.md)

## Reproduce

From the extracted package root, with Python 3.10 or newer:

```sh
python audit/validate.py
```

The validator uses only the Python standard library. It writes `audit/validation-report.json` and exits nonzero on an integrity failure. It performs no network request and does not execute any example command in the architecture.

## What is checked

The validator verifies the four archived upload hashes; replays every declared content edit against the original baseline; checks that every original section appears exactly once at its mapped destination; compares each rendered section with the deterministically transformed source; verifies document sizes and hashes; checks active-document local links, explicit anchors and code fences; and confirms that the twelve original design commitments are unchanged. The current report records measured counts and results.

`semantic-changes.patch` is the **content-only** diff before file relocation, link expansion and navigation wrappers. Its target name `corrected-logical-baseline.md` identifies a reconstructible audit representation, not a second shipped monolith. Replaying `edits.json` produces that representation in memory. Original-section hashes and destinations in `section-map.json` make changes independently traceable.

## Files

| File | Purpose |
|---|---|
| [SECTION-MAP.md](SECTION-MAP.md) / [section-map.json](section-map.json) | Human and machine mapping of all original section blocks |
| [source-manifest.json](source-manifest.json) | Byte-exact original source hashes |
| [document-manifest.json](document-manifest.json) | Scoped document ownership, sections, corrections and sizes |
| [edits.json](edits.json) / [semantic-changes.patch](semantic-changes.patch) | Exact replayable content changes, grouped F01–F12 |
| [change-stats.json](change-stats.json) | Content-only text comparison, excluding relocation/navigation |
| [web-sources.json](web-sources.json) | Targeted primary-source verification register |
| [build-metadata.json](build-metadata.json) | Version, scope and subsystem-size metadata |
| [validation-report.json](validation-report.json) | Results of the documentation checks actually executed |

## Limits

These checks establish package integrity and traceable coverage, **not semantic proof of every architectural rule**. The section map includes metadata and empty parent headings. Preserved original-source links to unavailable corpus files are excluded from active-link validation; the original files must remain unchanged.

No agent runtime, crash-recovery implementation, test parser, sandbox, provider adapter, benchmark or proposed runtime fixture was executed. Added fixture rows in the specification are future acceptance requirements. “Passed” in this audit applies only to the documented static checks. Word retention is a textual similarity measure, not a measured conceptual-change percentage or token-saving result.

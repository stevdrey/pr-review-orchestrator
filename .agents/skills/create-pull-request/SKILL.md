---
name: create-pull-request
description: Create evidence-based GitHub pull requests connected to an implementation issue, modeled after Dokene's structured PR style.
---

# Create pull request

Use only when a PR is requested or appropriate; respect explicit direction to commit directly.

- Inspect actual base/head diff, issue criteria and tests. Do not infer implementation from issue title.
- Title: `[Issue-N] Concise imperative outcome`, using a real primary issue.
- Body sections in order: `## What`, `## Why`, `## Changes`, `## How to test / verify`, `## Risk & rollout`, `## Notes for reviewers`.
- Cite exact checked commands and outcomes; mark non-run checks as **Not run**, with reason.
- Link `Closes #N` only if the PR fully resolves the issue.
- Describe security, data migration, agent permissions, budget and rollback impact.
- Never include secrets, private diffs or customer data; no auto-merge.

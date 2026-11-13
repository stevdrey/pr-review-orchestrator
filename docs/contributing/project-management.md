# GitHub project management

The roadmap names **eight EPICs**. Each EPIC is a GitHub issue titled `[EPIC N][P0|P1|P2] ...`; implementation issues have concise action-oriented titles. Prefer **native sub-issue links** when available; otherwise EPIC checklist links (`- [ ] #123`) provide traceable parent/child relationships.

## Labels

Recommended taxonomy (create in GitHub repo settings if needed):

- Type: `epic`, `feature`, `bug`, `task`, `documentation`, `security`, `spike`, `testing`
- Priority: `priority:P0`, `priority:P1`, `priority:P2`
- Area: `area:github`, `area:backend`, `area:database`, `area:review`, `area:policy`, `area:mcp`, `area:ai`, `area:security`, `area:observability`, `area:devex`, `area:ui`
- Workflow: `status:blocked`, `status:needs-design`, `good first issue`

Use existing GitHub default labels `enhancement`, `bug` and `documentation` where a custom label is unavailable. Issue bodies should always state EPIC reference, scope/non-goals, security/cost impact, acceptance criteria and dependencies.

## Issue lifecycle

`Backlog` → `Ready` → `In progress` → `Review` → `Done`. Avoid closing until criteria are satisfied with actual evidence. Maintain parent EPIC checkbox completion. Do not publish secret material in issues.

## Priority and ordering

- **P0:** verified GitHub events and bounded Codex review feedback loop.
- **P1:** MCP/Dot bridge, policy dashboard, billable deep reviews.
- **P2:** auto-fix and operations UI.

For EPIC/checklist references, keep links to this repository so downstream integrations can identify exact issue numbers. Milestones or native issue type may be added later; do not conflate an `epic` label with a native organization issue type.

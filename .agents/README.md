# Canonical agent guidance

`AGENTS.md` is the entry point; `.agents/skills/` holds provider-independent, task-scoped procedures. No provider-specific duplicate skill trees.

Load only relevant skills; apply security and accepted ADRs first. Review each action's GitHub authorization and side effects. All model outputs are untrusted. Keep a record of the exact commit SHA, findings, review rounds, CI evidence, provider calls and cost where applicable.

For a task, establish acceptance criteria and risks, implement focused changes, verify once with the most relevant tests, and report any checks not executed. Stop a review loop at configured iteration/budget thresholds or on missing evidence instead of inventing success.

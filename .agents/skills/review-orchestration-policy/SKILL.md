---
name: review-orchestration-policy
description: Maintain deterministic PR review workflow, review budgets, iteration caps, model escalation, and stopping rules.
---

# Review orchestration policy

- Model actions as an explicit state transition over an immutable PR head SHA with persisted attempts.
- Block duplicate review request for same provider, stage and SHA; defend against self-triggered webhook loops.
- Consider general comments as findings even without resolvable GitHub threads.
- Never take thread-resolution as sufficient evidence of correctness; require fresh code/test evidence or a documented disposition.
- Start with deterministic policy: author/repo permissions, open/draft state, CI, pending feedback, per-PR count and budget.
- Optional AI classification may suggest a decision but cannot override policy.
- Proposed default ceiling: six automatic review iterations per PR, configurable per installation; cap dollar and token spend independently.
- Escalate on budget exhaustion, ambiguity, stale head, repeated no-progress rounds, API failures or new security risks. Never auto-merge.

---
name: review-pr
description: Review code changes for correctness, security, concurrency, idempotency, spending controls and SHA freshness; produce grounded inline or general feedback.
---

# Review PR

1. Inspect PR base/head, changed files, relevant tests, ADRs and policy; bind findings to reviewed head SHA.
2. Prioritize security, logic, authorization, data integrity, event replay, cost guard bypass, and CI failures.
3. Reproduce or ground each claim. Include severity, file/line **only when the line is in diff**, expected/actual behavior and suggested change.
4. Inline comments only when GitHub supports the target line; otherwise publish a general review finding with a durable finding ID.
5. Distinguish resolved thread, addressed comment and verified fix. Never auto-dismiss substantive feedback.
6. On no findings, report a **clean review for the inspected commit**, not a global correctness guarantee.
7. Avoid unbounded review loops; obey iteration/budget constraints and human escalations.

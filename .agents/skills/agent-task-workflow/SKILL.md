---
name: agent-task-workflow
description: Plan and implement GitHub issues for PR Review Orchestrator with secure evidence-based verification.
---

# Agent task workflow

1. Read linked issue, scope, acceptance criteria, ADRs, and applicable security guidance.
2. Distinguish documentation/architecture proposal from production implementation.
3. Enumerate changes, effects on trust boundaries, idempotency, budgets, credentials and SHA consistency.
4. Implement the smallest complete increment with tests. Do not bypass guards in order to pass tests.
5. Verify with targeted tests; run full build/check once at the end when available. Record exact commands and results.
6. Update public docs, ADRs, issue traceability and rollout notes.
7. Report what changed, what was proved, what could not be verified and follow-up issues; never invent results.

**Stop:** if required authorization or security is absent, halt privileged actions, preserve state, escalate to human.

# Security policy

**Status:** planning only; no deployed service or released versions currently exist.

## Report a vulnerability

Use GitHub's private vulnerability reporting feature if enabled for this repository. Otherwise contact the repository owner privately through GitHub before publishing details. Do not open public issues with working exploits, tokens or confidential payloads.

## Security invariants

- Verify GitHub webhook signatures with raw request bytes before JSON parsing or queueing.
- Authenticate GitHub App installations; authorize repository and operation separately; least-privilege token scope and rotation.
- Treat PR titles, comments, patches, CI logs, repository files and model outputs as untrusted input.
- Do not execute arbitrary PR code on the main service host. Use isolated, limited workers with no production credentials.
- Deduplicate delivery and commands; persist action/audit evidence with correct SHA and bounded retention.
- Enforce quotas and review-loop limits deterministically even if an AI provider requests an exception.
- Never auto-merge or approve as another human.
- Avoid logging tokens, raw source, CI secret values or sensitive model prompts.

See [threat model](docs/security/threat-model.md) for planned controls and test scenarios.

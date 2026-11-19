# PR author notifications

[Issue #22](https://github.com/stevdrey/pr-review-orchestrator/issues/22) adds GitHub PR conversation comments that mention the verified author. The PostgreSQL outbox records entries into `READY_FOR_HUMAN` or `ACTION_REQUIRED` in the same transaction as the state change, including when new feedback invalidates readiness. V16 starts with an empty outbox.

The worker rechecks the installation/repository allowlist and enabled membership. It reads the PR from GitHub and checks the base repository ID, PR number, open/non-draft status, author identity and exact head SHA. It then verifies the local state and head visit under the existing PR lock. Another observation of the same state and head can update the displayed CI status without creating another notice. Clearing readiness, holding the PR or visiting another head invalidates a pending notice from the earlier state entry.

The message includes the PR link, full head SHA, recorded CI status and next action. Ready notices describe each stage's recorded provider evidence from the current head visit. The message names Codex from its known provider ID and uses generic descriptions for other or unavailable provenance. An action-required notice points to the PR conversation and review threads for the recorded feedback. The message uses fixed text and validated metadata. Source code, diffs, paths, feedback bodies and credentials are excluded. A ready notice requests human review and reserves approval and merge decisions for humans.

## Enable delivery

Keep delivery disabled until the operator has approved the App permission upgrade for the intended repositories. GitHub's [Create an issue comment](https://docs.github.com/en/rest/issues/comments#create-an-issue-comment) endpoint accepts a GitHub App installation token with **Pull requests: write** for PR conversation comments. The notification token is scoped to one repository with this permission and implicit Metadata read. Existing read operations still request their individual read scopes. Follow the [App setup runbook](../github/app-setup.md) for credentials, allowlisted pairs and enabled memberships.

```yaml
orchestrator:
  github:
    enabled: true
  notifications:
    enabled: true
    bot-login: example-review-app[bot]
```

Set `bot-login` to the REST login of the same GitHub App, including `[bot]`. GraphQL evidence fetchers must select `author { __typename login }` on PR conversation comments and use the configured `GitHubReviewNormalizer`. The normalizer recognizes matching `Bot` actors with or without the suffix, retains comment provenance and classifies the App's own marked notices as system messages. A marker copied into a user comment or another bot's comment carries no authority. Keep the configured bot identity available to normalization while delivery is disabled. Event planners must continue excluding bot-generated comment events from review triggers.

GitHub controls delivery of the mention under the author's notification settings. This feature uses the GitHub channel. Email requires a separate integration with verified addresses and explicit opt-in.

## Delivery and recovery

The worker attempts preflight checks at most three times per outbox row, one minute apart. The database fences older claims and commits a send permit before the single comment POST. The permit is unique by GitHub repository, PR, state, recipient user ID and SHA. Replaying a transition, resuming into the same state or revisiting a SHA cannot produce another POST for an already reserved key. A new state or SHA can be notified independently.

- `PENDING` is awaiting preflight. A row with `attempts = 3` and `available_at` in the past has exhausted its preflight attempts.
- `SKIPPED` is ineligible, stale, denied or covered by an existing permit.
- `ATTEMPTED` holds an irrevocable send permit with an unconfirmed outcome. A crash after reservation can leave the comment unsent. Inspect the target PR before any manual communication. Keep the permit to preserve deduplication.
- `SENT` records an acknowledged GitHub comment ID.

GitHub comment creation lacks a conditional write tied to the PR head. A change between the final checks and comment creation can leave a notice for an earlier state. The notice carries the exact SHA it describes. A timeout, ambiguous response or lost receipt after reservation uses the same fail-closed policy and never triggers an automatic resend.

Disabling delivery stops scheduled sends. The outbox retains transition intents and send permits. Reenabling delivery still checks pending intents against the current local state and live PR. No review or CI provider is enabled by this switch. Live workflow verification belongs to [issue #23](https://github.com/stevdrey/pr-review-orchestrator/issues/23).

## Verification

`./gradlew --offline clean build` passed all 297 tests on Java 27 with Testcontainers PostgreSQL and a local GitHub API fixture. Coverage includes transition capture, CI invalidation, head changes, concurrent reservations, uncertain POST outcomes, metadata-only messages and bot notice classification. `git diff --check` passed. Live App permission and author mention delivery remain unverified.

In the PR #66 follow-up, `./gradlew --offline test --tests '*.GitHubReviewNormalizerTest.onlyRecognizedAppNotificationIsExcludedFromFeedback'` reproduced the misclassification on `124599f` with the GraphQL login shape. After the fix, `./gradlew --offline test --tests '*.GitHubReviewNormalizerTest'` passed, including checks for a human with the same login, an unrelated bot and a comment without the notice marker.

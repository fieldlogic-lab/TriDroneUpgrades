# SolutionsHQ AI Development Team runner

This opt-in workflow turns a SolutionsHQ request into a real Codex PM plan, a separately approved Developer run, a draft PR, independent Android checks, and a fresh independent Codex QA review. It never merges, deploys, approves a GitHub PR, or publishes an APK.

## One-time activation

1. Review and merge the runner installation PR to `main` with explicit owner approval. GitHub requires the workflow on the default branch before dispatch. The runner rejects dispatches on other refs.
2. Add repository Actions secret `OPENAI_API_KEY` with funded API access. Use a dedicated OpenAI project with a spending budget; GitHub job timeouts bound runtime but do not impose a dollar cap. Never put the key in the application or a request.
3. Under repository **Settings → Actions → General → Workflow permissions**, enable **Allow GitHub Actions to create and approve pull requests**. This setting permits draft PR creation; the runner does not approve PRs. Organization policy may also need to permit this setting and `openai/codex-action`.
4. Configure the authenticated SolutionsHQ backend to target this repository and `.github/workflows/ai-development.yml`, dispatching `ref: main`. Its server-only GitHub credential needs access to this repository's metadata, contents (read), issues (write), pull requests (read), checks (read), and Actions (write). Repository workflow tokens are separate from the backend credential.
5. Preserve branch protection and user-controlled manual publishing. Existing `android-pr.yml` is still required for ordinary PRs. PR workflows created through `GITHUB_TOKEN` can require manual approval; this runner explicitly performs Android validation against the created full commit SHA without relying on an implicit downstream trigger. Its dispatch workflow checks belong to the main workflow SHA; inspect the linked run and QA event for the tested PR SHA. If branch protection requires the ordinary PR event check, a maintainer must approve/arrange that check before merging; do not bypass protection.

## Protocol

Create an open issue containing the exact request marker:

```html
<!-- solutionshq-request:{"version":1,"requestId":"UUID","project":"hydrographic-usv"} -->
```

Dispatch inputs `phase: plan`, `request_issue`, and `request_id`. A bot-authored comment contains `<!-- solutionshq-event:{JSON} -->` with `version`, `requestId`, `type`, `phase`, `status`, `runId`, and `summary`. The PM event additionally includes `plan: {summary, acceptanceCriteria, allowedPaths}` and human-readable scope. No implementation runs before a second dispatch.

After the owner reviews that exact comment, dispatch `phase: develop`, the same issue/request ID, `plan_comment_id`, and `plan_sha256` (lowercase SHA-256 of the entire exact comment body encoded UTF-8). Intake verifies bot authorship, issue ownership, request identity, status, content hash, and single-use phase claims. The backend authenticates the owner; GitHub restricts direct manual dispatch to repository writers. The runner cannot prove a human clicked an external UI, so keep backend credentials scoped and access controlled.

The Developer runs in a fresh process. A separate trusted job validates a bounded file manifest against immutable intake context, writes a new `codex/hq-UUID` branch, and opens a draft PR. Generated changes are limited to exact PM-approved filenames under `app/src/` or `docs/`; changes to workflows, build scripts, agent policy, credentials, symlinks, or other files are rejected. Up to 30 changed files and 2 MB of manifest data are accepted. Broader requests need a separately reviewed runner policy change.

Independent validation uses JDK 17, Android SDK 35/build-tools 35.0.0, and Gradle 8.10.2:

```sh
gradle --no-daemon --continue :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Build execution has no API key or write token. A different fresh runner reviews the exact PR SHA with a read-only QA agent, approved criteria, diff, and independent build evidence. Trusted reporting rejects stale PR heads, forces failed builds to failed QA, records `pass`, `fail`, or `blocked`, and links `headSha`, `prNumber`, and `buildStatus`. New commits invalidate that QA. Hardware accuracy, transport/protocol, and survey-grade behavior still need real hardware verification per `AGENTS.md`.

## Limits and recovery

Each request phase is single-use, including failed runs. Concurrency serializes dispatches by request ID. Trusted intake atomically creates an annotated `solutionshq-requests/UUID` Git tag binding that UUID to one canonical issue and a `solutionshq-phases/UUID/develop` phase tag; duplicate issue submissions cannot execute the same request twice. Intake needs `contents: write` only for these durable tag claims. Model and QA jobs never receive write access. Do not delete claim tags to retry; submit a new request referencing failure evidence and the existing draft PR when further work is needed. There is no autonomous retry or fix loop. Actions cancellation or service outages can prevent a final bot comment; SolutionsHQ must also display the real Actions conclusion.

Artifacts retain context, generated manifest, and check evidence for seven days. Issue comments and draft PRs are durable records. Job timeouts bound PM/Developer to 25 minutes, Android validation to 30 minutes, and QA to 20 minutes. The Codex action is pinned to verified official commit `52fe01ec70a42f454c9d2ebd47598f9fd6893d56`; review and update this pin deliberately. Its CLI installation uses the upstream action default; pin a reviewed CLI version before requiring fully reproducible model runtime.

No live execution is proven until configuration is complete and a real request has run. Suggested first smoke request: improve a single existing UI label and its test under `app/src/`, with no hardware behavior changes.

Run orchestration tests locally:

```sh
python3 -m unittest discover -s scripts/ai-team -p test_runner.py -v
```

For another project, copy the workflow and runner, change the issue project identity, static path policy, independent check toolchain/commands, and QA prompt together, then review and activate in that repository. Do not accept arbitrary repository names or build commands from frontend request text.

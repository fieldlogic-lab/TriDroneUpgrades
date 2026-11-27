# HydroField coding agent instructions

## Development operating standard — proposed FieldLogic v1.1 adoption

Source: [FieldLogic usage-governed development standard at reviewed policy revision](https://github.com/fieldlogic-lab/FieldLogic-Agent-Templates/blob/1bc3b85ef7205753ddbc88512fc5237cfdfee833/policies/usage-governed-development.md). Apply this local operating summary after owner acceptance of the policy and this change; stricter technical, runtime and release safeguards still apply.

- **Tier 1 (routine):** documentation, labels, isolated styling and low-risk reversible fixes. One developer; targeted deterministic checks and a concise self-check report (not independent approval).
- **Tier 2 (standard):** defined features, ordinary logic, UI workflows and integrations. One developer, then one distinct QA reviewer after a coherent tested change. Activate PM only if scope/criteria are unclear or coordination is needed.
- **Tier 3 (critical):** survey math, coordinates/datums/offsets, acquisition, source data/provenance, security, destructive changes/migration, material architecture and release-critical work. PM defines approved scope and acceptance criteria, developer implements, independent QA verifies the final artifact and critical evidence. Escalate when risk increases; never downgrade to save usage.
- Default to one active execution agent at a time: primary agent may hand off to one specialist and wait; no concurrent teams, recursive delegation, continuous PM/QA or duplicate analysis without explicit owner authorization. Use economical settings for routine tasks; escalate reasoning on demonstrated need.
- Read task-relevant source-of-truth sections and changed files, not every document or unrelated repository. Share a concise handoff with criteria, diff/commit and reproducible check evidence.
- Prefer deterministic tests/build/lint and CI. QA reviews the exact final commit/artifact and existing evidence, independently checks critical behavior and selectively reruns meaningful tests. Changes invalidate prior acceptance: review the delta and rerun affected checks. Preserve all required branch-protection and release gates.
- Work one authorized, independently verifiable slice. Default to at most one repair/review cycle; checkpoint and request direction if further work is required. Stop when acceptance criteria pass, scope changes, a blocker arises or usage is exhausted. Do not automatically start another slice.
- If usage is measurable, reserve at least 20% of the observable allowance at intake for QA/recovery; record start/end usage and settings. Otherwise report unknown. Do not infer exact usage, poll or retry quota errors.
- Persist task/tier, branch and exact commit or dirty state, criteria/evidence, checks and limitations, blockers, next bounded step and observed reset time when available. Preserve work and resume only when authorized.
- Work on feature branches; keep PRs draft until tier-appropriate verification is complete. No direct push to main, self-approval, unauthorized merge/release/deployment or consequential actions. Owner approval is action-specific. No policy document can itself enforce a quota or pause existing goals.

## Build and verification
Use JDK 17, Android SDK platform 35/build-tools 35.0.0, and Gradle 8.10.2. This repository has no Gradle wrapper.
Run from the repository root:
```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
PR validation runs without signing secrets and uploads test and lint reports. Require the `Android PR validation` check and resolved review conversations in main branch protection. AI QA is a process gate, not a human GitHub approval.
The APK workflow is manual-only on main; dispatch it only after explicit user publishing approval.

## Product safeguards
Follow `docs/FIELD_APP_PRODUCT_SPEC.md` and existing coordinate/datum contracts.
- Preserve raw GNSS/depth observations, timestamps, configuration snapshots, and processing provenance; never rewrite old session meaning.
- Never silently substitute phone GPS, fabricate NAVD88 elevations, or apply horizontal offsets without valid heading.
- Keep connection, fix, and survey readiness distinct, with explicit missing/stale/unverified states.
- CI cannot prove RS2+, HydroLite transport/protocol, RTK accuracy, datum correctness, or survey-grade soundings. Record actual hardware verification separately and disclose what remains unverified.

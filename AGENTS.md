# Codex development process

## Roles and approval
- Project Manager: define scope and acceptance criteria, obtain the user's scope approval, delegate implementation and independent QA, and report readiness and limitations.
- Developer: implement approved scope on a feature branch/worktree, add appropriate regression coverage, run checks, and prepare a draft pull request with evidence.
- QA Engineer: a separate agent independently reviews the diff and acceptance criteria, reruns relevant checks, and reports pass/fail/blocked with findings.
- Use separate Developer and QA agents under the PM. Developer fixes return to QA. Tie QA conclusions to the full reviewed commit SHA; new commits require renewed QA.
- Only the user authorizes merging and publishing. Scope approval does not authorize merge, release workflow dispatch, deployment, or publishing. Do not push directly to main.
- Keep PRs draft until checks and independent QA are complete. Report changes, checks, unresolved findings, and hardware verification limits to the user before requesting merge approval.

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

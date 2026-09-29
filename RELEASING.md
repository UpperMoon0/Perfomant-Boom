# Releasing Perfomant Boom

## Normal release

1. Increase `mod_version` in `gradle.properties` using a stable `major.minor.patch` version.
2. Add a nonempty `changelog/<version>.md` and open a pull request.
3. Review the exact-head validation results and merge to `main` only when the runtime work is ready.

The main-branch push runs release preflight, the same reusable checks as PR validation, and publication. It creates `v<version>` at the tested commit and uploads exactly the Fabric and Forge Minecraft 1.20.1 runnable JARs, `SHA256SUMS`, and `manifest.json`. It never releases on a PR/feature branch or from a tag-only workflow.

The current automated release gate covers JVM regressions, both loader builds, release-tooling tests, workflow syntax, loader metadata, checksums, and commit provenance. The dedicated-server/real-client work is not yet part of this gate. Do not interpret green CI as proof of client convergence or vanilla end-to-end equivalence; keep PR #1 in draft until that separate runtime work is integrated and reviewed.

## Publication destinations

GitHub releases use the workflow's standard `GITHUB_TOKEN`; no personal access token is needed. Only the publication job receives `contents: write` permission. Test jobs do not receive publishing secrets.

CurseForge publication is optional until the project is configured. Set repository variable `CURSEFORGE_PROJECT_ID` to the actual project ID and secret `CURSEFORGE_API_TOKEN` to its publishing token. No project ID or token is guessed or copied from another mod. When the ID is set, both CurseForge uploads must succeed before the GitHub release is published. Fabric declares Fabric API and Architectury API as required dependencies; Forge declares Architectury API.

## Retry and collision rules

Use **Actions -> Release -> Run workflow -> main** to retry. An untagged version also retries when a release workflow, release helper, or changelog repair reaches main. An unchanged already-tagged version is a no-op on ordinary pushes.

Existing tags must point at the exact release commit. A tag pointing elsewhere is an error, never force-moved. After a tag has been created, rerun the original workflow run at its original SHA; publishing a corrected new commit requires a version bump. Downgrades, snapshots, malformed versions, empty changelogs, missing/extra loader artifacts and mismatched JAR versions are rejected.

Publication stages a GitHub draft, uploads the complete artifact set, and only then makes it public. A retry may finish that draft. An already-public GitHub release is not overwritten: its recorded checksums must match the rebuilt same-commit artifacts. Failed or partial CurseForge uploads are subject to the service's duplicate-upload behavior; inspect those uploads before retrying.

No publication has been performed merely by opening the PR. Do not merge the draft to force a release while runtime verification is outstanding.

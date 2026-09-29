# Releasing Perfomant Boom

## Normal release

1. Increase `mod_version` in `gradle.properties` using a stable `major.minor.patch` version.
2. Add a nonempty `changelog/<version>.md` and open a pull request.
3. Review the exact-head validation results and merge to `main` only when the runtime work is ready.

The main-branch push runs release preflight, the same reusable checks as PR validation, and publication. It creates `v<version>` at the tested commit and uploads exactly five runnable JARs: Fabric/Forge 1.20.1, Fabric/NeoForge 1.21.1, and NeoForge 26.1.2, `SHA256SUMS`, and `manifest.json`. It never releases on a PR/feature branch or from a tag-only workflow.

The automated release gate includes JVM regressions, all five target builds, release/harness tests, workflow syntax, Forge GameTest, and frozen dedicated-server/real-client and persistence tests on Fabric/Forge 1.20.1. Green results prove the documented fixtures, not universal vanilla end-to-end equivalence or live-client verification of the modern targets; see `TESTING.md` for semantic and measurement limitations.

## Publication destinations

GitHub releases use the workflow's standard `GITHUB_TOKEN`; no personal access token is needed. Only the publication job receives `contents: write` permission. Test jobs do not receive publishing secrets.

CurseForge publication is optional until the project is configured. Set repository variable `CURSEFORGE_PROJECT_ID` to the actual project ID and secret `CURSEFORGE_API_TOKEN` to its publishing token. No project ID or token is guessed or copied from another mod. When the ID is set, all five CurseForge uploads must succeed before the GitHub release is published. Fabric 1.20.1 declares Fabric API and Architectury API; Forge declares Architectury API. Fabric 1.21.1 declares Fabric API; NeoForge targets need no Architectury dependency.

## Retry and collision rules

Use **Actions -> Release -> Run workflow -> main** to retry. An untagged version also retries when a release workflow, release helper, or changelog repair reaches main. An unchanged already-tagged version is a no-op on ordinary pushes.

Existing tags must point at the exact release commit. A tag pointing elsewhere is an error, never force-moved. After a tag has been created, rerun the original workflow run at its original SHA; publishing a corrected new commit requires a version bump. All publication paths compare stable versions against the highest fetched `vMAJOR.MINOR.PATCH` tag (numeric ordering, including tags outside HEAD ancestry), not only the previous push. A shallow history is rejected. Manual and repair retries cannot publish a lower version; same-commit retries of the latest version remain valid. Downgrades, snapshots, malformed versions, empty changelogs, missing/extra loader artifacts and mismatched JAR versions are rejected.

Publication stages a GitHub draft, uploads the complete artifact set, and only then makes it public. A retry may finish that draft. An already-public GitHub release is not overwritten: its recorded checksums must match the rebuilt same-commit artifacts. Failed or partial CurseForge uploads are subject to the service's duplicate-upload behavior; inspect those uploads before retrying.

No publication has been performed merely by opening the PR. Do not merge to force a release while runtime verification is outstanding.

## Mandatory runtime validation

The shared checks now include Forge GameTest and frozen real-client/persistence jobs for both Fabric and Forge. A version bump cannot publish if either loader fails these jobs. Keep runtime evidence with the workflow. No release has been executed merely by opening or updating this PR.

## Project-page documentation

Use [CURSEFORGE.md](CURSEFORGE.md) as the player-facing description and [icon.png](icon.png) as the project-page icon. Keep the description aligned with [COMPATIBILITY.md](docs/COMPATIBILITY.md), and link version notes from [CHANGELOG.md](CHANGELOG.md). The upload workflow publishes files; it does not synchronize the project description or icon. Update those manually in the project editor when needed.

Set the public project license to MIT, matching [LICENSE](LICENSE) and every loader target.

## API consumers

`publishToMavenLocal` publishes the matching loader JARs, mapped common artifacts and the
pure core for local consumers. Celestial Nail requires API version 1.1.0. Merge this API
before merging the Nail dependency change. Cross-repository CI can override its Boom ref
using `PERFOMANT_BOOM_REF`. Local consumers may need `--refresh-dependencies` after rebuilding
the same version because Loom caches remapped mod dependencies.

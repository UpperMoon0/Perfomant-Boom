# Verification

## Current committed test lanes

`Validate` runs on pull requests, pushes to main, and manual dispatch. The release workflow calls the same `checks.yml` workflow, so publication cannot silently use a weaker build/test command.

- Release-tooling regression tests use temporary Git repositories and synthetic JARs. They do not make network calls, upload files, or use publishing credentials.
- JVM regression tests cover deterministic vanilla ray selection for non-air blocks, resistant center blocks, empty-air work, and stable results across time slices. The repository also contains a synthetic performance benchmark; it is not a complete vanilla server/client comparison.
- `test build` compiles and tests the shared implementation and builds Minecraft 1.20.1 Fabric and Forge artifacts. Packaging verifies the shared implementation is present, loader metadata contains the exact version, and precisely two runnable artifacts are selected. Sources/dev JARs are excluded.

CI retains test reports and the verified release candidate with its source commit and checksums. Publishing downloads those artifacts rather than rebuilding after validation.

## Local commands

Use Python 3.11+ and JDK 21. On Windows use `gradlew.bat` instead of `./gradlew`.

```sh
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/release.py check
./gradlew test build -I .github/reproducible.gradle
python tools/release.py package
python tools/release.py verify
```

`package` requires an empty `build/release` staging directory. It intentionally rejects stale runnable JARs from another version; clean that isolated build before packaging a new version.

## Dedicated-server and real-client follow-up

The ongoing integration/benchmark implementation is not committed by this CI-only change. Its local work must be preserved and reconciled separately, not reset or overwritten by this worktree. Do not run builds that rewrite its development JAR while its server/client JVMs are alive.

Before calling that lane a release gate, require fresh frozen-source runs, server-authoritative state comparison and acknowledgement, preserved per-run evidence, explicit clean-shutdown status, and both Fabric and Forge results. Similar destroyed-block counts alone are not parity. Report equivalent-work timings separately from default-drop behavior, and measured work duration separately from actual CPU time and client-visible completion.

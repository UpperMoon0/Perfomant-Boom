# Verification

## Current committed test lanes

`Validate` runs on pull requests, pushes to main, and manual dispatch. The release workflow calls the same `checks.yml` workflow, so publication cannot silently use a weaker build/test command.

- Release-tooling regression tests use temporary Git repositories and synthetic JARs. They do not make network calls, upload files, or use publishing credentials.
- JVM regression tests cover deterministic vanilla ray selection for non-air blocks, resistant center blocks, empty-air work, stable results across time slices, and an independent Minecraft 1.20.1 vanilla-ray comparison benchmark.
- Forge GameTest exercises the production scheduler in a real world and checks block-entity cleanup, an unbreakable control, crater mutation and lighting settlement.
- `test build` compiles and tests the shared implementation and builds Minecraft 1.20.1 Fabric and Forge artifacts. Packaging verifies the shared implementation is present, loader metadata contains the exact version, and precisely two runnable artifacts are selected. Sources/dev JARs are excluded.

CI retains test reports and the verified release candidate with its source commit and checksums. Publishing downloads those artifacts rather than rebuilding after validation.

## Local commands

Use Python 3.11+ and JDK 21. On Windows use `gradlew.bat` instead of `./gradlew`.

```sh
python -m unittest discover -s tools -p 'test_*.py' -v
python tools/release.py check
./gradlew test build -I .github/reproducible.gradle
./gradlew :forge:runBoomGameTestServer
python tools/release.py package
python tools/release.py verify
```

The focused vanilla calculation benchmark can be run with:

```sh
./gradlew :common:test --tests com.nstut.explosion.VanillaComparisonBenchmarkTest --rerun-tasks
```

`package` requires an empty `build/release` staging directory. It intentionally rejects stale runnable JARs from another version; clean that isolated build before packaging a new version.

## Dedicated-server and real-client benchmark

The repository includes a self-driving dedicated-server + graphical-client benchmark modeled after the live verification used by the other mod projects:

```sh
python tools/live_boom_test.py --loader fabric --timeout 360
python tools/live_boom_test.py --loader forge --timeout 360
```

Each run uses an isolated run directory and port, waits for the real client to join, executes vanilla and Perfomant-Boom explosions in independent bedrock-contained fixtures, performs warmup pairs, then records five measured trials. The client verifies authoritative crater updates, surviving bedrock controls and block-light convergence. Evidence and parsed results are written under `build/live-boom-evidence/<loader>/`.

Report the no-drop scenario as the closest equivalent-work comparison. The default-drop scenario is also useful for real gameplay cost, but vanilla creates loot while the fast path intentionally does not reproduce general vanilla explosion loot/callback behavior.

Keep three performance concepts separate:

- calculation/CPU time: how much processor work the explosion implementation consumes;
- affected-tick latency: how large the server tick becomes while explosion work is running;
- wall-clock completion: how long until the time-sliced fast explosion has fully finished.

The scheduler uses a cooperative per-tick budget, not a hard real-time deadline: one chunk/light/physics operation can overrun the target. The real-client lane is committed and repeatable, but it is intentionally not part of the normal release workflow because it requires a graphical client and is substantially more expensive than the deterministic CI lanes.
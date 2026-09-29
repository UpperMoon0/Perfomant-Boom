# Testing Perfomant Boom

## Required CI and release gates

`validate.yml` and `release.yml` both call `checks.yml`. Every release requires release/harness tests, workflow lint, JVM regressions, Fabric and Forge builds, Forge GameTest, and BOTH real-client integration jobs. The live jobs run on disposable GitHub-hosted Ubuntu VMs with Xvfb/Mesa; untrusted fork code never uses the private fleet. No publishing credentials enter these jobs.

```sh
python -m unittest discover -s tools -p 'test_*.py' -v
./gradlew test build -I .github/reproducible.gradle
./gradlew :forge:runBoomGameTestServer
python tools/live_boom_test.py --loader fabric --timeout 900 --require-clean
python tools/live_boom_test.py --loader forge --timeout 900 --require-clean
```

Use JDK 21 and Python 3.11+; use `gradlew.bat` on Windows. Windows service launches reuse the active interactive desktop. Run one loader at a time in any one checkout. Development runs may omit `--require-clean`, but their evidence explicitly records the dirty state.

## What the live gate proves

Both dedicated server and real graphical client are prepared completely before either starts. A pinned Loom exporter writes direct Java commands; no Gradle process can recompile or hot-reload common classes during measurement. Source/classes/development JARs are fingerprinted and monitored. A checkout lock rejects concurrent runs. Each run owns a unique world, loopback-only offline server, port and evidence directory; old runs are retained.

The isolated test world uses three warmup pairs, five measured pairs for each of no-drops and default-loot scenarios, plus a larger power-24 stress pair. Runtime totals: 34 explosions in 17 pairs. Fixtures start as netherrack with glowstone centers and bedrock shells and cross chunk/section boundaries. The comparison calls actual `ServerLevel.explode` and the production scheduler. Seeds match within each pair and first-run order alternates. Exact relative block-state SHA-256 digests, not approximately similar counts, must match between vanilla and fast craters.

The server publishes per-trial authoritative block/light digests through an out-of-band local evidence file. The client hashes its actual network-populated `ClientLevel`; it NEVER modifies its world from the oracle. Every block state, block/sky light value, air count and block-entity count in the fixture is compared, with full bedrock-shell checks. Only after exact convergence and at least two rendered frames does the real client send a run-token/index/digest command acknowledgement. The server verifies each receipt. This is separate from vanilla-versus-fast semantic parity.

Coordination commands are paced outside measured work to respect vanilla spam limits without granting operator permissions. All clients/servers must exit normally with code zero, with no late fatal logs after a PASS marker. The test server joins its complete Minecraft server thread (including save/close) before a normal JVM exit, because Architectury's development file watcher otherwise remains alive. A fresh server process then loads the saved world and verifies every authoritative block/light snapshot again. Forced cleanup, source drift, absent evidence or failed save/reload means failure. A PASS log alone is insufficient.

## Evidence and performance interpretation

Evidence is retained in `build/live-boom-evidence/<loader>/<run-id>/`: logs, frozen input hashes, launch descriptions, authoritative/client snapshots, every raw timing sample, save/reload proof and `result.json`. CI uploads failed evidence too. A stale checkout lock is deliberately not auto-stolen: verify its owner and remaining processes before removing it manually.

Report these metrics separately:

- **Active work (`activeWorkMs`, scheduler `workMs`)** is monotonic elapsed time inside explosion/scheduler calls, not actual thread/process CPU time. It excludes deferred work outside those calls.
- **Observed server-tick duration** covers the start/end hooks, including explosion work and ten aftermath ticks. Oracle hashing/file I/O after the end hook is excluded. It is not a claim that every engine tail or OS cost is included.
- **Client-acknowledged wall time** runs from explosion invocation through light settlement, oracle exchange, client convergence, two rendered frames and the server receipt. It includes harness synchronization overhead; it is not packet latency or pure engine completion time.
- **Observed frame gaps** use actual world-render callbacks from trial readiness through convergence. They include rendering and test-observer costs; they are not GPU fence timestamps or percentile FPS. Render callbacks do not prove every chunk mesh is uploaded.

Only the no-drops scenario is an equivalent-loot comparison. Default-loot vanilla creates normal explosion loot while the fast engine omits general vanilla explosion loot/custom callbacks. That comparison must never be advertised as an equivalent-functionality speedup. Raw samples, medians and quartiles are retained; the single stress pair is a regression check, not a statistical performance claim. The four-millisecond scheduler target is cooperative and may be exceeded by one expensive operation. There is no universal multiplier.

## Scope

These fixtures cover seeded static vanilla blocks, chunk/section mutation, lighting, real network convergence and persistence. Forge GameTest additionally covers chest block-entity cleanup. They do not certify every modded explosion hook, entity behavior, fluid simulation, custom protection mod or arbitrary dynamic world change. Development runtime gates validate the exact source commit; release packaging separately verifies remapped artifact metadata and provenance rather than claiming the development launch used the release JAR bytes.

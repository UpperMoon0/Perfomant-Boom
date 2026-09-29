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

## Yield, unload and interrupted-save regressions

Each frozen live job also starts two additional player-free dedicated-server processes using the same frozen classes. The first uses a far-away, open-top single-chunk fixture and the production scheduler driver with an expired deadline to deterministically suspend inside a slice; ordinary server ticks are allowed to unload chunks, and tests require a genuinely different `LevelChunk` instance on reload. No test removes a holder with reflection or substitutes mock chunks.

The ray test replaces the newly loaded fixture with bedrock and proves no new positions are selected from the detached netherrack chunk. The mutation test saves/unloads after eight removals, verifies the exact saved prefix after reload, resumes, checks that the detached palette stays unchanged while the replacement changes, and then completes normally.

A separate fixture is saved clean, partially mutated, and halted **in the same tick** while the task remains incomplete. There is no manual save, extra scheduler slice or lighting wait between those mutations and the normal stop. A control receives exactly the same removals through vanilla `Level#setBlock`. A fresh process verifies exact saved block state, air count, four heightmaps, skylight source metadata, extinguished glowstone and full block/sky-light digest equality with that vanilla control. Evidence includes `lifecycle-*.json` and both lifecycle process logs; absent, stale-token, completed-task or failed-clean-shutdown evidence fails the job.

The focused JVM regressions separately cover chunk replacement at a calculator yield, dirty marking before `finish()`, redirtying after an intervening save clears the flag, per-write skylight/light checks, empty-section transitions and no-op removals. Those unit tests use mock infrastructure and are not substitutes for the two real-server lifecycle processes.

Committed removals survive a normal save; pending explosion work is deliberately not serialized or resumed after restart. Abrupt process termination/power loss and arbitrary mod callbacks are not certified by these tests.

### Source basis

Implementation was checked against the decompiled Minecraft **1.20.1** files in `MC-Modding-Src/1.20.1`, with Mojang-mapped vanilla bytecode used to resolve the partially obfuscated method names:

- `server/level/ServerChunkCache.java:141–162, 179–202, 225–244`: synchronous chunk acquisition, non-loading `getChunkNow`, and the temporary UNKNOWN ticket. `server/level/TicketType.java:23` gives UNKNOWN a one-tick timeout. These are not task-lifetime chunk ownership.
- `server/level/ChunkMap.java:437–460, 508–534, 741–765`: save-all, holder unload/save and `save(ChunkAccess)`'s unsaved check/reset. Successful writes must become dirty again after any intervening save.
- `world/level/chunk/LevelChunk.java:222–279`: four heightmaps, empty-section transitions, `LightEngine.hasDifferentLightProperties`, `ChunkSkyLightSources.update`, queued `checkBlock`, removal hooks and dirty state. `server/level/ThreadedLevelLightEngine.java:69–72` copies mutable positions before queuing work.

The supported target is 1.20.1 Fabric/Forge; behavior from 1.21.1 or 26.1.2 was not substituted for this implementation.

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

# Shared terrain API

This is the consumer contract for the `com.nstut.explosion.terrain` API introduced in Boom 1.1.0. It describes administrative no-drop operations, separate from the ordinary explosion scheduler.

## Dependency setup

Build with `./gradlew buildAll publishToMavenLocal`. Consumers use Maven group `com.nstut` and version `1.1.0` with the matching artifact:

| Target | Runtime artifact ID |
| --- | --- |
| Fabric 1.20.1 | `perfomant_boom-fabric` |
| Forge 1.20.1 | `perfomant_boom-forge` |
| Fabric 1.21.1 | `perfomant_boom-fabric-1.21.1` |
| NeoForge 1.21.1 | `perfomant_boom-neoforge-1.21.1` |
| NeoForge 26.1.2 | `perfomant_boom-neoforge-26.1.2` |

Loom common consumers compile against `perfomant_boom-common` or `perfomant_boom-common-1.21.1`; platform modules still need their matching runtime artifact. Follow Nail's `modCompileOnly`/`modImplementation` setup for Loom or `implementation` setup for ModDevGradle. This repository currently documents local publication, not a hosted Maven endpoint.

Declare a real loader dependency too. Do not shade the API or mixins into a consumer: duplicate classes split the shared scopes and work allowances. Refresh consumer caches after republishing an unchanged version.

## Operations

| API | Contract |
| --- | --- |
| `SphereShellCursor.start(radius)` | Start resumable shell traversal; persist radius and x/y/z to reconstruct the cursor |
| `TerrainPasses.clearSphere(level, center, targetRadius, scan, ready, cancelled)` | Mutates the supplied cursor in place; target radius must be 0–128; completion is represented by the cursor advancing beyond the target |
| `TerrainPasses.purgeFluids(level, center, radius, index, ready, cancelled)` | Returns `Progress(nextIndex, done, changed)` for a bounded fluid pass |
| `TerrainPasses.reconcileBoundary(level, center, radius, index, ready, cancelled)` | Returns progress for the immediate six-neighbor boundary, not arbitrary recursive world settlement |
| `ImpactWorkBudget.forTick(level, gameTime)` | Shares the current tick's allowance across operations in the same level |
| `TerrainOperations` | Target-version replacement, boundary adaptation and nonblocking persistent chunk-request helpers |

See the signatures and readiness requirements in [TerrainPasses.java](../terrain-common/src/main/java/com/nstut/explosion/terrain/TerrainPasses.java). Run all these operations on the server thread. Keep the center/radius consistent with the saved cursor.

## Consumer responsibilities

1. Own the strike/task state and call passes from the server tick lifecycle.
2. Supply a readiness predicate that does not synchronously load missing chunks. Boundary reads require all six adjacent positions' chunks to be ready. Blocked positions remain pending.
3. Supply cancellation and stop requesting further work when the owner is removed or complete.
4. Save the mutated sphere cursor or returned `nextIndex` after each slice. A yield caused by an exhausted budget, unavailable chunk or cancellation does not mean the traversal is done.
5. Accumulate the boundary `changed` flag across every slice of a pass. Start another full pass if anything changed; finish after an unchanged complete pass. Persist both index and accumulated change state.
6. Manage chunk ownership, release and transfer. Requesting a chunk does not transfer lifetime management to Boom.
7. Own any settling delays, repeated fluid sweeps, entity damage, visual effects and network synchronization. Nail is the reference consumer for those policies.

The current shared terrain allowance is 3,000 changes, 45,000 scans, one chunk request and an eight-millisecond cooperative window per level/tick. It gates the next operation and cannot preempt a slow callback. These limits are distinct from the ordinary explosion scheduler's allowance.

## Guarantees and limits

The no-drop adapters cover vanilla container cleanup and version-specific boundary behavior. This does not guarantee every modded block's custom side effects or protection hooks. External fluid sources can refill a cleared volume. The consumer's saved progress, not the ordinary scheduler queue, supplies restart continuity.

For the tested fixtures and exact-version source basis, see [TESTING.md](../TESTING.md) and [multiversion-audit.md](multiversion-audit.md).

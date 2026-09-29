# Architecture

Boom contains an explosion scheduler and a separate no-drop terrain API. Both access live Minecraft state on the server thread.

| Location | Responsibility |
| --- | --- |
| `core/` | Pure cursors, shared work budgets, scoped mutation policy and tests |
| `terrain-common/` | Minecraft-dependent resumable sphere, fluid and boundary passes shared as source |
| `common/` | Legacy Minecraft 1.20.1 engine, adapters and regression infrastructure |
| `common-modern/` | Shared modern explosion engine source |
| `common-1.21.1/` | 1.21.1 adapters and Fabric common artifact |
| `common-26.1.2/` | 26.1.2 source adapters consumed by the NeoForge module; not a standalone Gradle project |
| `fabric/`, `forge/` | Minecraft 1.20.1 loader entrypoints and artifacts |
| `fabric-1.21.1/`, `neoforge-1.21.1/`, `neoforge-26.1.2/` | Modern loader entrypoints and artifacts |
| `tools/`, `.github/` | Frozen runtime harnesses, release checks, packaging and CI |

## Scheduled explosion flow

`/boom` queues work at the command source position. Server tick hooks advance ray selection and block mutation in slices. State must remain resumable at a yield, and live chunks must be reacquired rather than retained as detached mutable palettes. Successful removals maintain world bookkeeping and the version's required lifecycle behavior.

The ordinary scheduler does not persist unfinished tasks. Already committed world changes participate in normal vanilla saves. See [TESTING.md](../TESTING.md) for the stronger, precisely scoped legacy RNG, lighting, unload and restart checks.

## Shared terrain flow

`TerrainPasses.clearSphere`, `purgeFluids` and `reconcileBoundary` consume the level's shared budget. Pure cursors encode traversal. `TerrainOperations` supplies the target-version mutation and chunk-request semantics; its mixins cooperate with the shared scoped policy.

Consumers own stage timing, saved progress, readiness/cancellation, chunk ownership and effects. A pass yielding for a budget or unavailable chunk is not equivalent to completion. Boundary settlement may require another pass after changes. [API_REFERENCE.md](API_REFERENCE.md) describes the consumer contract.

## Semantic boundaries

Ordinary explosion processing and administrative no-drop terrain clearing are distinct. Keep their callback/drop policies explicit. Do not infer one version's flags, build-height bounds, damage API or removal lifecycle from another. Record verified source differences in [multiversion-audit.md](multiversion-audit.md).

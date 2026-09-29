# Perfomant Boom

Server-thread explosion scheduling and a reusable bounded terrain API. The operator-only
`/boom <power>` command accepts 1–500; power is not a spherical crater radius. Ordinary
Minecraft explosions are not globally replaced. General explosion loot is not reproduced.
Native callbacks and chunk loading can exceed the cooperative time allowance.

## Documentation

- [CurseForge description](CURSEFORGE.md): ready-to-paste player overview and command quick start.
- [Compatibility and installation](docs/COMPATIBILITY.md): target dependencies and behavioral limits.
- [Contributing](CONTRIBUTING.md), [architecture](docs/ARCHITECTURE.md) and [terrain API reference](docs/API_REFERENCE.md).
- [Testing](TESTING.md), [releasing](RELEASING.md) and [changelog](CHANGELOG.md).

The project-page icon is [icon.png](icon.png) at the repository root. Editing the description or icon does not publish a website change.

| Minecraft | Loaders | Runtime Java | Modules |
| --- | --- | --- | --- |
| 1.20.1 | Fabric, Forge | 17 | `common`, `fabric`, `forge` |
| 1.21.1 | Fabric, NeoForge | 21 | `common-1.21.1`, `fabric-1.21.1`, `neoforge-1.21.1` |
| 26.1.2 | NeoForge | 25 | `common-26.1.2` sources, `neoforge-26.1.2` |

Fabric requires Fabric API. Only the legacy 1.20.1 targets require Architectury API.
The original 1.20.1 module names remain stable for existing tooling.

## Build

Run Gradle with Java 21; toolchains supply Java 17/21/25 for the targets.

```sh
./gradlew buildAll
./gradlew publishToMavenLocal
```

Targeted tasks configure only their required Minecraft projects, for example
`./gradlew :neoforge-26.1.2:build` or `./gradlew :fabric-1.21.1:build`.
Install each target's runnable JAR from its `build/libs`, excluding sources and dev JARs.

## Shared terrain API

Celestial Nail now depends on the matching Boom artifact rather than packaging its own
terrain code. `TerrainPasses.clearSphere`, `purgeFluids` and `reconcileBoundary` perform
resumable, bounded work. The caller supplies readiness/cancellation callbacks and saves
progress. `TerrainOperations` implements version-specific no-drops block replacement and
nonblocking persistent chunk requests. Pure cursors, scoped callback suppression and
per-level budgets live in `core`; Minecraft-dependent traversal lives in `terrain-common`.

These terrain operations deliberately suppress block drops and deferred block cascades.
They are a separate policy from `/boom`, which invokes native explosion callbacks on the
modern versions. The consuming mod owns visual effects, entity damage, stage timing and
chunk ownership; Boom owns terrain execution. Install both mods, never shade Boom into Nail.

See [the source audit](docs/multiversion-audit.md), [TESTING.md](TESTING.md), and
[RELEASING.md](RELEASING.md). Releases validate and package all five target JARs.

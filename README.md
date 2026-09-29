# Perfomant Boom

Large explosions spread across server ticks, plus a Java terrain library for other Minecraft mods. Boom provides the operator command `/boom <power>` and reusable terrain operations for mod integrations.

## What the mod does

- **Scheduled explosions:** queues explosion calculation and block changes, advancing work across server ticks. The scheduler handles effects, entity damage and knockback through version-specific implementations.
- **A library for mod authors:** exposes `ExplosionScheduler.schedule` and `scheduleTracked` for queued explosions, and a separate `TerrainPasses` API for spherical clearing, fluid removal and boundary repair.
- **Resumable terrain work:** supplies cursors and shared per-level work budgets. Integrating mods control when tasks advance and can save their progress across restarts.
- **Five targets:** Fabric and Forge 1.20.1, Fabric and NeoForge 1.21.1, and NeoForge 26.1.2.

Boom does not automatically replace TNT, creepers or explosions from other mods. Integrations must explicitly call its API. Boom's command works as a standalone feature.

## Try it

In a disposable test world with cheats enabled, run:

```mcfunction
/boom 4
```

The explosion is queued at the command source position. Power accepts **1–500**, including decimals. It is explosion strength, not a guaranteed crater radius: block resistance and terrain affect the result. Game-master/operator permission is required (level 2 on legacy targets).

To choose a position explicitly:

```mcfunction
/execute positioned 100 80 100 run boom 4
```

The success message means queued, not finished. Back up worlds before destructive operations. See [PACKMAKER.md](PACKMAKER.md) for command blocks, datapack functions and pack rollout checks.

## Installation

Choose exactly one runnable JAR matching your Minecraft version and loader; exclude sources and development JARs.

| Minecraft | Loader | Required mods | Java |
| --- | --- | --- | --- |
| 1.20.1 | Fabric | Fabric API, Architectury API 9.2.14+ | 17 |
| 1.20.1 | Forge | Architectury API 9.2.14+ | 17 |
| 1.21.1 | Fabric | Fabric API | 21 |
| 1.21.1 | NeoForge | None | 21 |
| 26.1.2 | NeoForge | None | 25 |

Fabric requires Loader **0.18.4+**. When using an integrating mod, follow its client/server installation requirements and include its additional dependencies. Vanilla-client/server-only deployment is not certified by the current integration tests.

Find published assets on [GitHub Releases](https://github.com/UpperMoon0/Perfomant-Boom/releases). The [CurseForge project](https://www.curseforge.com/minecraft/mc-mods/perfomant-boom) has ID **1718134**; availability there depends on publication and moderation. This matrix describes source support, not a claim that every file has been published.

## For mod authors

**Yes, Boom can be used as a library.** Depend on the matching Boom mod at runtime; do not copy or shade its classes into your mod.

| Use case | Entry point | Your mod supplies |
| --- | --- | --- |
| Queue an explosion | `ExplosionScheduler.schedule(...)` | Trigger, position and validated power |
| Receive completion metrics | `ExplosionScheduler.scheduleTracked(...)` | A short server-thread callback |
| Clear a sphere without drops | `TerrainPasses.clearSphere(...)` | Saved shell cursor, readiness and cancellation |
| Remove fluid in a sphere | `TerrainPasses.purgeFluids(...)` | Saved pass index and repeat/settling policy |
| Repair the immediate boundary | `TerrainPasses.reconcileBoundary(...)` | Neighbor readiness, saved index and convergence tracking |

All live-world calls belong on the server thread. Boom's loader hooks drive queued explosions; the consuming mod's tick lifecycle drives terrain passes. Terrain consumers own visuals, damage, networking, chunk lifetime and persistence.

Start with [INTEGRATION.md](INTEGRATION.md) for Gradle dependencies, loader metadata and Java examples, then consult the [API reference](docs/API_REFERENCE.md). Maven coordinates currently require building and publishing locally; this repository does not configure a hosted Maven endpoint.

## Behavior and limits

- Scheduling spreads work out; it does not eliminate its cost or guarantee lag-free explosions. Chunk acquisition, callbacks, lighting and networking can exceed or sit outside a cooperative allowance.
- The explosion scheduler targets **4 ms per server tick**. The separate terrain API shares **3,000 changes, 45,000 scans, one chunk request and an 8 ms cooperative window per level/tick**. These are implementation limits, not pack settings or hard deadlines.
- General vanilla explosion loot is not reproduced. Modern adapters invoke native callbacks; 1.20.1 has its own mutation path. Arbitrary modded hooks are not guaranteed.
- Administrative terrain clearing suppresses drops and selected cascades, including vanilla container contents. It is not a claim-protection integration; outside fluid can refill an area.
- Completed changes survive normal saves. The ordinary explosion queue does **not** resume after restart. Terrain consumers must save and restore their own progress.

See [compatibility](docs/COMPATIBILITY.md) and [testing coverage](TESTING.md) before relying on a particular mod interaction or performance claim.

## Build

Run Gradle with Java 21; toolchains supply Java 17/21/25. On Windows, use `gradlew.bat` in place of `./gradlew`.

```sh
./gradlew buildAll
./gradlew publishToMavenLocal
```

Runnable JARs are in each loader module's `build/libs`. Legacy `fabric` and `forge` module names mean 1.20.1. Targeted builds include `./gradlew :fabric-1.21.1:build` and `./gradlew :neoforge-26.1.2:build`.

## Documentation and support

- [Packmaker and server guide](PACKMAKER.md)
- [Integration guide](INTEGRATION.md) and [API reference](docs/API_REFERENCE.md)
- [Architecture](docs/ARCHITECTURE.md), [source audit](docs/multiversion-audit.md) and [contributing](CONTRIBUTING.md)
- [Testing](TESTING.md), [releasing](RELEASING.md) and [changelog](CHANGELOG.md)
- [CurseForge description](CURSEFORGE.md) and [project icon](icon.png)

Report problems in [GitHub Issues](https://github.com/UpperMoon0/Perfomant-Boom/issues), including game/loader/Boom versions, command or consuming mod, reproduction steps and logs. Distinguish server tick delays from client rendering problems.

Created by **NsTut**. Licensed under the [MIT License](LICENSE).

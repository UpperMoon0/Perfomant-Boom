# Compatibility and installation

This matrix describes the current source targets, not publication status.

| Minecraft | Loader | Runtime Java | Required dependencies |
| --- | --- | --- | --- |
| 1.20.1 | Fabric | 17 | Fabric API, Architectury API 9.2.14+ |
| 1.20.1 | Forge | 17 | Architectury API 9.2.14+ |
| 1.21.1 | Fabric | 21 | Fabric API |
| 1.21.1 | NeoForge | 21 | None |
| 26.1.2 | NeoForge | 25 | None |

Fabric Loader must be at least 0.18.4. Choose exactly one matching runnable Boom JAR, excluding sources/dev artifacts. The legacy `fabric` and `forge` modules target 1.20.1; their short names do not indicate support for other Minecraft versions. Loader metadata ranges do not establish support beyond the matrix.

When using Celestial Nail, install matching Boom and Nail JARs on both clients and server. Nail also requires Architectury on Fabric 1.21.1. Boom does not require Nail. A vanilla-client/server-only deployment is not certified by the current client integration tests.

## Two terrain policies

`/boom` schedules an ordinary explosion through Boom's version-specific engine. It does not globally replace TNT, creepers, or other explosion sources. General vanilla loot and arbitrary modded explosion hooks are not guaranteed; the modern adapters invoke native explosion callbacks, while the legacy implementation has its own mutation path.

The shared no-drop terrain API used by Nail is a distinct policy: direct replacements, bounded fluid cleanup and immediate-boundary repair. It suppresses drops and selected block cascades. It must not be described as ordinary vanilla explosion-event compatibility or a claim-protection integration.

## Persistence and performance

Normal saves retain committed block changes. The ordinary scheduler does not serialize unfinished tasks for restart. A consumer such as Nail must persist its own cursors and stage transitions.

Work limits are cooperative. One expensive callback or chunk acquisition may exceed the allowance, and lighting/network/client work adds costs outside an individual scheduler slice. There is no universal speed multiplier. Default-loot comparisons are not equivalent-loot benchmarks because Boom omits general explosion loot.

## Reporting and validation

Include the Minecraft version, loader version, Boom version, command power or consuming mod, reproduction steps, `latest.log`, and any crash report in [GitHub Issues](https://github.com/UpperMoon0/Perfomant-Boom/issues). For performance reports, identify terrain, loaded/cold chunks, and whether the symptom is server ticks or client frames.

The frozen real-client and restart suites cover Fabric/Forge **1.20.1**. Modern targets have builds and regression tests; 26.1.2 additionally has real ephemeral-server tests. Nail's NeoForge 1.21.1 suite exercises the terrain API. These scopes are distinct; consult [TESTING.md](../TESTING.md) before claiming compatibility or speedups.

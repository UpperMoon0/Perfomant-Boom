# Perfomant Boom

Large explosions, scheduled across server ticks—and a shared terrain engine for mod integrations.

**Perfomant Boom** provides an administrator explosion command and reusable terrain operations. Its scheduler spreads calculation and block changes across ticks to avoid doing the entire scheduled explosion in one burst.

## Features

- Queue explosions with the operator command `/boom <power>`.
- Spread explosion work across server ticks while keeping live-world access on the server thread.
- Support terrain clearing, fluid cleanup, and boundary repair for mods that use the shared API.
- Integrate the Java API into your own mod while retaining control of effects, damage, timing and saved progress.

## Try it

In a disposable test world with cheats enabled, run:

```text
/boom 4
```

The explosion is queued at the command's execution position. Power accepts **1–500**, including decimal values. Power is explosion strength, **not a guaranteed crater radius**; resistant blocks and the surrounding terrain affect the result. Start small.

The command requires game-master/operator permission. Command blocks or `/execute positioned` can supply a different execution position.

## Installation

Choose the file matching your exact Minecraft version and loader.

| Minecraft | Loader | Required mods | Java |
| --- | --- | --- | --- |
| 1.20.1 | Fabric | Fabric API, Architectury API 9.2.14+ | 17 |
| 1.20.1 | Forge | Architectury API 9.2.14+ | 17 |
| 1.21.1 | Fabric | Fabric API | 21 |
| 1.21.1 | NeoForge | None | 21 |
| 26.1.2 | NeoForge | None | 25 |

Fabric targets require Fabric Loader **0.18.4 or newer**. For integrations, follow the consuming mod's client/server requirements and install its additional dependencies. Boom's command works independently.

## What to expect

Ordinary TNT, creeper, and other Minecraft explosions are **not automatically replaced**. Boom processes its command and integrations that explicitly use its engine.

This is not a promise of lag-free explosions or a universal speed multiplier. Chunk loading, lighting, and block callbacks can still take time. General vanilla explosion loot behavior is not reproduced, and custom explosion hooks or protection mods may behave differently. Use backups before destructive commands; Boom is not a protection system.

Queued ordinary Boom explosions are not resumed after a server restart. Completed block changes persist through normal saves. Integrations manage their own saved progress separately.

## Help and feedback

[Source and documentation](https://github.com/UpperMoon0/Perfomant-Boom) · [Report a problem](https://github.com/UpperMoon0/Perfomant-Boom/issues)

Include Minecraft and loader versions, Boom version, command power, installed integrations, and the crash report or latest log. For performance reports, describe the terrain and separate server tick delays from client rendering problems.

Created by **NsTut**. Licensed under the MIT License.

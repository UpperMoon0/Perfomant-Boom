# Usage guide

Boom adds an administrative explosion command and a Java library for integrating mods. This guide covers packs, maps and servers; Java integrations belong in [INTEGRATION.md](INTEGRATION.md).

## Install the correct files

Use the [installation matrix](README.md#installation) to match the exact game version and loader. Include Boom's required mods and the dependencies of each consumer.

Install Boom alongside the consumer. Follow the integrating mod's client/server installation requirements. Do not assume vanilla clients are a tested configuration. Boom's CurseForge project ID is `1718134`; use a real published file for your target when constructing a pack manifest, not a guessed file ID.

Boom is MIT-licensed; retain its copyright and license notice when redistributing it. Other included mods have their own licenses.

## Commands and automation

`/boom <power>` queues an explosion at the command source position. Power is a decimal from **1 to 500**, not a block radius. Begin with `/boom 4` in a disposable world. Game-master/operator permissions are required; the legacy command uses permission level 2.

Player or console command:

```mcfunction
/execute positioned 100 80 100 run boom 4
```

A datapack function uses the command without a leading slash:

```mcfunction
execute positioned 100 80 100 run boom 4
```

Command blocks use their command source position unless `execute positioned` changes it. Server command-block settings and execution-source permissions still apply. Trigger each event once: repeating command blocks or per-tick functions can continuously enqueue destruction. Success means **queued**, not **finished**. There is no public command to inspect, cancel or await the ordinary queue.

## What installing Boom changes

Only `/boom` and mods explicitly calling Boom use its engine. Installing it does not reroute vanilla TNT, creepers or arbitrary modded explosions.

There is no dedicated KubeJS/CraftTweaker integration or pack-facing scheduler configuration in this release. Commands can be invoked through Minecraft's command system; Java terrain calls require a mod integration. Work budgets are implementation constants, not settings in a generated config file.

## World behavior

Scheduled explosions can damage entities and alter terrain over multiple ticks. General vanilla explosion loot is not reproduced; arbitrary modded explosion/protection hooks are not guaranteed. Test the actual claim and block mods in your pack. Boom is not a protection system.

The terrain API provides a separate administrative no-drop path. It clears blocks and vanilla container contents without drops, suppresses selected cascades and repairs the immediate boundary in bounded passes. Outside fluids can flow back. Consumer commands, permissions, visuals and restart handling belong to that consumer.

Normal saves retain committed changes. Unfinished ordinary explosions are not serialized for restart, which may leave a partially completed crater. Removing the mod or restarting is not an undo. Keep world backups for restoration.

## Validate your pack

1. Start the exact client/server versions and check for missing dependencies.
2. Run one small explosion in a disposable world; observe terrain, damage and client updates.
3. Test representative modded blocks, inventories, claims, fluids and chunk boundaries before increasing power.
4. Test each consumer's cancellation and save/reload behavior separately from `/boom`.
5. Measure server ticks and client frames separately. Terrain, cold chunks, lighting and other mods affect results; distributing work is not a universal speed multiplier.

See [compatibility](docs/COMPATIBILITY.md) and [TESTING.md](TESTING.md) for known limits and actual test coverage. Report exact commands, versions, terrain and logs in [GitHub Issues](https://github.com/UpperMoon0/Perfomant-Boom/issues).

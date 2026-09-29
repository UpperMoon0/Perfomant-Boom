# Integration guide

Boom 1.1.1 exposes Java APIs in `com.nstut.explosion` and `com.nstut.explosion.terrain`. Use the explosion scheduler for an explosion with Boom's effects/damage policy, or the terrain passes when your mod owns the effect and needs bounded no-drop world editing. [Celestial Nail](https://github.com/UpperMoon0/Celestial-Nail) is a terrain consumer.

## Add the dependency

Build the matching Boom source with Java 21 (target toolchains supply Java 17/21/25), then publish locally:

```sh
./gradlew buildAll publishToMavenLocal
```

On Windows use `gradlew.bat`. In your consumer, add `mavenLocal()` to the repositories used to resolve dependencies. No hosted Maven endpoint is configured by this project; CI must build/publish the pinned Boom source before building your mod. Pin a source commit and dependency version for reproducibility.

| Target | Coordinate |
| --- | --- |
| Fabric 1.20.1 | `com.nstut:perfomant_boom-fabric:1.1.1` |
| Forge 1.20.1 | `com.nstut:perfomant_boom-forge:1.1.1` |
| Fabric 1.21.1 | `com.nstut:perfomant_boom-fabric-1.21.1:1.1.1` |
| NeoForge 1.21.1 | `com.nstut:perfomant_boom-neoforge-1.21.1:1.1.1` |
| NeoForge 26.1.2 | `com.nstut:perfomant_boom-neoforge-26.1.2:1.1.1` |

For a Fabric 1.21.1 Loom platform module:

```groovy
repositories { mavenLocal() }
dependencies {
    modImplementation "com.nstut:perfomant_boom-fabric-1.21.1:1.1.1"
}
```

For an Architectury/Loom common module targeting 1.21.1, compile against `modCompileOnly "com.nstut:perfomant_boom-common-1.21.1:1.1.1"`; the platform module still needs the runtime dependency above. For 1.20.1 common code use `perfomant_boom-common`, with `modImplementation` on the matching Fabric/Forge platform module.

For a NeoForge 1.21.1 ModDevGradle module:

```groovy
repositories { mavenLocal() }
dependencies {
    implementation "com.nstut:perfomant_boom-neoforge-1.21.1:1.1.1"
}
```

Use the 26.1.2 coordinate for its corresponding module. These are additions to an already configured Minecraft project, not complete build files. Examples use Mojang-mapped names; adapt names to your mappings. Refresh consumer dependency caches if you republish an unchanged local version.

### Declare a loader dependency

Gradle dependencies alone do not tell the user's loader to require Boom. Add the appropriate entry to your existing metadata, replacing `your_mod_id` with your own ID. These examples pin the documented/tested version; widen the range only after testing it.

Fabric: merge into the existing `depends` object in `fabric.mod.json`:

```json
"perfomant_boom": "1.1.1"
```

Forge 1.20.1: add to `META-INF/mods.toml`:

```toml
[[dependencies.your_mod_id]]
modId="perfomant_boom"
mandatory=true
versionRange="[1.1.1]"
ordering="NONE"
side="BOTH"
```

NeoForge: add to `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.your_mod_id]]
modId="perfomant_boom"
type="required"
versionRange="[1.1.1]"
ordering="NONE"
side="BOTH"
```

List Boom as a required dependency on your distribution platform too (CurseForge project `1718134`). Ship it as a separate installed mod. Do not shade or copy Boom classes/mixins: duplicate classes split shared mutation scopes and budgets. Its common artifacts are for compilation, not standalone loader installations.

## Queue an explosion

Call on the server thread, with a live `ServerLevel`. The following method body assumes your caller supplies `level` and a finite `Vec3 center`:

```java
import com.nstut.explosion.ExplosionScheduler;

// In a server-thread command or event handler:
ExplosionScheduler.schedule(level, center, 4.0F);
```

Boom registers the scheduler tick hooks. Do not register a second driver or call `tick` yourself. Validate external inputs: finite coordinates and power in the command-supported range 1–500 are a portable choice. The modern scheduler checks thread/input validity, but do not depend on every legacy entry point enforcing those checks.

For completion metrics, use this instead of the preceding call:

```java
ExplosionScheduler.scheduleTracked(level, center, 4.0F, metrics -> {
    LOGGER.info("Boom changed {} blocks in {} passes ({} ms elapsed)",
            metrics.changedBlocks(), metrics.workPasses(), metrics.wallMs());
});
```

`LOGGER` is your mod's logger. Keep the callback short, nonblocking and exception-safe: it executes on the server thread. Completion means the scheduler finished its task, not that all later fluid, lighting or client work has settled. A task discarded because its world is gone need not notify completion. The API returns no task handle, cancellation token or persistent queue ID.

`ExplosionMetrics` exposes `changedBlocks`, `raySamples`, `workMs`, `maxSliceMs`, `workPasses` and `wallMs`. Work/slice times measure elapsed time within scheduler calls, not CPU usage or full end-to-end rendering cost. The queue does not survive restart. Ordinary explosions include Boom's damage/effects policy and do not guarantee general vanilla loot or all modded explosion hooks.

## Drive a terrain task

The terrain API does not enqueue a complete job for you. Call one bounded slice from your server tick lifecycle, then return and continue on later ticks. It performs administrative no-drop changes; it does not apply entity damage or create effects.

This minimal method-body example clears only currently loaded terrain and waits when a needed chunk is unavailable. It assumes a stable `level`, immutable `center`, integer `targetRadius` in 0–128, and task fields `cursor` and `cancelled`:

```java
import com.nstut.explosion.terrain.SphereShellCursor;
import com.nstut.explosion.terrain.TerrainPasses;

// Task creation: start at shell zero to clear the entire sphere.
SphereShellCursor cursor = SphereShellCursor.start(0);

// Each server tick, reuse the SAME cursor, not a new one:
TerrainPasses.clearSphere(level, center, targetRadius, cursor,
        level::isLoaded, () -> cancelled);
boolean clearingDone = cursor.radius() > targetRadius;
```

`start(targetRadius)` starts at that outer shell; it does not clear the interior. The two code sections above belong in separate creation/tick lifecycle locations. Save cursor `radius()`, `x()`, `y()` and `z()` after each slice; restore with `new SphereShellCursor(savedRadius, savedX, savedY, savedZ)`. Preserve the task's dimension, center, target radius and stage too. Validate saved data before restoration. A blocked chunk or exhausted budget leaves work pending; returning from a call is not completion.

After clearing, a fluid pass can advance from a saved index:

```java
var progress = TerrainPasses.purgeFluids(level, center, targetRadius,
        fluidIndex, level::isLoaded, () -> cancelled);
fluidIndex = progress.nextIndex();
boolean fluidPassDone = progress.done();
```

Start a new fluid pass at index zero. Persist the returned index and move to the next stage only when `done()` is true. Your mod decides settling delays and whether to repeat fluid sweeps. Outside fluid sources can refill the sphere.

### Boundary readiness and convergence

Boundary repair reads neighbors. For vanilla immediate-neighbor behavior, the readiness predicate must include the position and all six adjacent positions, skipping out-of-build-height neighbors. For example, inside your tick method:

```java
java.util.function.Predicate<net.minecraft.core.BlockPos> boundaryReady = pos -> {
    if (!level.isLoaded(pos)) return false;
    for (var direction : net.minecraft.core.Direction.values()) {
        var neighbor = pos.relative(direction);
        if (!level.isOutsideBuildHeight(neighbor) && !level.isLoaded(neighbor)) {
            return false;
        }
    }
    return true;
};
var progress = TerrainPasses.reconcileBoundary(level, center, targetRadius,
        boundaryIndex, boundaryReady, () -> cancelled);
boundaryIndex = progress.nextIndex();
boundaryChanged |= progress.changed();
if (progress.done()) {
    if (boundaryChanged) {
        boundaryIndex = 0; // Begin another complete pass on a later tick.
        boundaryChanged = false;
    } else {
        boundaryDone = true;
    }
}
```

`boundaryIndex`, `boundaryChanged` and `boundaryDone` are your persistent task fields; initialize them to zero/false for a new stage. Accumulate `changed` across **all slices**, not just the last one. Persist both index and accumulated flag after each slice. Custom modded callbacks may access more distant blocks; the six-neighbor contract is not a guarantee for arbitrary modded behavior.

### Chunk lifetime, cancellation and budgets

The examples deliberately do not force-load chunks. A task can remain paused until its chunks load. For owned chunk requests, use `ImpactWorkBudget.forTick(level, level.getGameTime()).tryRequestChunk()` before requesting, and inspect the version adapter's `TerrainOperations.forceChunk`. The helper requests a persistent forced chunk without synchronously retrieving it; success is not proof that the chunk is ready. Track pre-existing forced state and your ownership, and release only requests your integration owns. Handle cancellation, dimension changes, reload and owner removal explicitly.

Never synchronously load chunks in readiness predicates or spin until a pass finishes. All terrain passes share the per-level budget: 3,000 changes, 45,000 scans, one chunk request and an 8 ms cooperative window per tick. Direct low-level `TerrainOperations` calls do not automatically perform the passes' scan/change accounting. Use the high-level passes unless you implement that accounting yourself.

Your mod owns persistence, chunk ownership, stage transitions, damage, visuals and networking. Test cancellation at yields, save/reload, multiple simultaneous consumers, chunk boundaries, modded containers and external fluid refill. [API_REFERENCE.md](docs/API_REFERENCE.md) records the contract; [TESTING.md](TESTING.md) records tested scope. These examples illustrate API usage, not a complete persistent task implementation.

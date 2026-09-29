# Multiversion source audit

Base commits: Perfomant Boom `37ee29d`; Celestial Nail `7c807fb`, fetched from origin/main
before implementation. The author-owned Nail terrain implementation and its behavioral
regressions were transferred to Boom. Vanilla source is not redistributed.

## Inspected behavior

Local decompiled `MC-Modding-Src/{1.20.1,1.21.1,26.1.2}` supplies the version-specific
reference. `vanilla-source-hashes.json` records the inspected inputs for reproducibility.

- `Explosion` (1.21.1), `ServerExplosion` (26.1.2), and `ExplosionDamageCalculator`:
  1,352 boundary rays, float strength/resistance arithmetic, air positions in the affected
  HashSet, selection before entity callbacks, and shuffled affected-position order.
- 1.21.1 entity damage is floating point (no 1.20.1 integer truncation); resistance is the
  `EXPLOSION_KNOCKBACK_RESISTANCE` attribute. Damage callbacks precede knockback exposure.
- 26.1.2 uses `hurtServer`, a normalized eye/TNT-origin vector, exposure supplied to the
  damage calculator, `push`, and redirectable-projectile ownership. Its near-zero-radius
  guard and zero-length direction differ from 1.21.1. These paths have explicit adapters.
- Modern block mutation invokes `BlockState.onExplosionHit`, rather than assuming the
  1.20.1 callback layout. General loot output is discarded, retaining Boom's documented
  policy; this is not a claim of complete vanilla explosion equivalence.
- `Level.setBlock` and `LevelChunk.setBlockState`: retain native heightmap, skylight, block
  entity, POI, dirty-state and client notification maintenance. The no-drops terrain path
  uses scoped callback/tick suppression on 1.20.1/1.21.1; 26.1.2 uses the native skip-block-
  entity-side-effects and skip-on-place flags plus scoped deferred-block-tick suppression.
- `LeavesBlock`, `ScaffoldingBlock` and coral behavior: surviving boundary states require
  bounded repair because suppressing deferred survival ticks alone leaves stale support
  states. External fluid ticks remain permitted. This is deliberate terrain policy.
- `ServerLevel.setChunkForced`: older versions synchronously load on addition. The API
  updates persistent forced-chunk data and tickets without that load. 26.1.2 persists
  forced tickets through `ServerChunkCache.updateChunkForced` instead.
- 26.1.2 renames `ChunkPos.asLong` to `pack`; version adapters isolate the call.

## Ownership after migration

Boom owns all three terrain passes (inside-out clearing, fluid purge, boundary repair),
resumable cursors, per-level budgets, no-drop mutation, suppression mixins, and nonblocking
chunk requests. Nail owns strike timing, visuals, special damage, its entity/NBT progress,
cancellation and forced-chunk ownership transfer. Existing NBT cursor fields are preserved.

## Limits

Cooperative scheduling cannot preempt a slow native/modded callback or synchronous load.
World changes between slices can differ from one atomic vanilla explosion. Loader-specific
explosion events are not claimed to match a direct native invocation. Client rendering and
network behavior require live client validation in addition to builds and server tests.

## Validation recorded 2026-09-29

- Boom `buildAll` passed for all five targets; 58 JVM tests passed (13 core, 28 legacy,
  5 Fabric-common 1.21.1, 5 NeoForge 1.21.1, 7 NeoForge 26.1.2).
- The seven 26.1.2 tests include two real-server regressions: no-drop inventory removal
  and a scheduled explosion completed by the registered server tick listener.
- Forge 1.20.1 production scheduler GameTest passed. Its development runs now explicitly
  disable production refmaps; packaged Forge refmaps were inspected and retain SRG names.
- Nail `buildAll`, 20 remaining shared tests, and all 24 NeoForge 1.21.1 runtime tests passed
  after extracting all three terrain passes and preserving saved cursor fields.
- 36 release-tool tests and 26 live-harness tooling tests passed. Workflow YAML parses.
- All five Boom JARs contain the shared API and matching mixins. All five Nail JARs declare
  Boom as a dependency and contain neither duplicated API classes nor old mutation mixins.
- Live client rendering/performance runs were not performed for the new targets.

ModDevGradle JUnit setup follows its [official documentation](https://github.com/neoforged/ModDevGradle#unit-testing-with-junit).
The 26.1.2 ephemeral server provider intentionally starts without dimensions; the test
fixture invokes vanilla world creation on the server thread before exercising world work.

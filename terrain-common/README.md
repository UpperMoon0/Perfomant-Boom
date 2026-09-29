# Shared terrain API

Extracted from UpperMoon0/Celestial-Nail at 7c807fb. These operations deliberately implement
Nail's bounded, no-drops terrain policy, not ordinary vanilla explosion physics.

`TerrainOperations` supplies version-specific mutation and persistent chunk requests.
`TerrainPasses` supplies resumable fluid-purge and boundary passes; callers own their saved
progress, readiness checks and cancellation. `core` owns scan cursors, mutation scope and
per-level work budgets. Never bundle another copy into a consuming mod: install the matching
Perfomant Boom loader JAR so its mixins and the shared scope/budget have one owner.

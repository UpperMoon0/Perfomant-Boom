# Shared terrain API

These operations implement bounded, no-drop administrative terrain editing,
separate from ordinary vanilla explosion physics.

`TerrainOperations` supplies version-specific mutation and persistent chunk requests.
`TerrainPasses` supplies resumable fluid-purge and boundary passes; callers own their saved
progress, readiness checks and cancellation. `core` owns scan cursors, mutation scope and
per-level work budgets. Never bundle another copy into a consuming mod: install the matching
Perfomant Boom loader JAR so its mixins and the shared scope/budget have one owner.

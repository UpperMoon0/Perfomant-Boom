# Contributing

Report bugs through [GitHub Issues](https://github.com/UpperMoon0/Perfomant-Boom/issues). Include Minecraft/loader/mod versions, command power or consumer, reproduction steps, and the log/crash report. Distinguish server tick delays from client rendering problems in performance reports.

## Build and check

Run Gradle on Java 21; target toolchains select Java 17/21/25. Use `gradlew.bat` on Windows and Python 3.11+ for the tooling tests.

```sh
./gradlew buildAll
python -m unittest discover -s tools -p 'test_*.py' -v
./gradlew :forge:runBoomGameTestServer
```

[TESTING.md](TESTING.md) documents the real-client/restart harness, evidence requirements and modern regression coverage. Run only one live harness per checkout and use its disposable worlds. Do not claim a performance improvement from compilation or a unit test.

To test a consumer, publish locally and build it against the matching version:

```sh
./gradlew publishToMavenLocal
```

After republishing the same version, refresh the consumer's dependencies before launching; stale remapped JARs can omit new API classes while displaying the expected version number.

## Implementation rules

- Keep live-world reads and writes on the server thread, with resumable progress across yields.
- Preserve each target's block lifecycle, lighting, dirty-state and chunk semantics using its actual vanilla/loader sources.
- Keep ordinary explosion behavior separate from the administrative no-drop terrain policy.
- Maintain a single runtime owner of the terrain scopes, budgets and mixins. Consumers depend on Boom rather than shading copies.
- Update [the source audit](docs/multiversion-audit.md), [API reference](docs/API_REFERENCE.md) and tests when those contracts change.

## Documentation and releases

Update [CHANGELOG.md](CHANGELOG.md), the appropriate `changelog/<version>.md`, compatibility notes and [CURSEFORGE.md](CURSEFORGE.md) for user-visible changes. Version changes on `main` can trigger publication; follow [RELEASING.md](RELEASING.md). Routine docs work does not require a version bump.

Public descriptions must keep the limits on loot, hooks, persistence and performance claims. Contributions are distributed under the repository's [MIT License](LICENSE). Keep loader metadata consistent with that license.

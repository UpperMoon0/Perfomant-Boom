# Perfomant Boom

Minecraft 1.20.1 **Fabric and Forge** mod providing an operator-only `/boom <radius>` command. The argument is explosion power, not a guaranteed spherical crater radius. Work is spread across server ticks; ordinary vanilla explosions are not globally replaced.

The command accepts power from 1 to 500 and requires permission level 2. Back up valuable worlds before using destructive commands.

## Requirements

Java 17 or newer at runtime, Architectury API, and the matching loader. Fabric also requires Fabric API. The build runs Gradle on Java 21 while targeting Java 17 bytecode.

## Build and verification

See [TESTING.md](TESTING.md) for the automated checks and their limits.

```sh
./gradlew test build
```

Use the runnable JAR from `fabric/build/libs` or `forge/build/libs`, not a sources or development JAR. This mod does not currently reproduce normal vanilla explosion loot. A cooperative tick budget does not guarantee a maximum tick duration.

## Releases

See [RELEASING.md](RELEASING.md). Change `mod_version` in `gradle.properties` and add the matching `changelog/<version>.md`. After merge to `main`, the release workflow validates the exact commit, builds both loaders, and publishes `v<version>` with those tested JARs and SHA-256 checksums. PRs never publish releases.

# AGENTS.md

## Scope

This repository contains the Better Content-owned Forge mod **Depth Director**.

- Canonical mod ID: `depth_director`
- Canonical artifact: `depth-director-<version>.jar`
- Maven group: `com.bettercontent`
- Java runtime: 17
- Minecraft/Forge baseline: 1.20.1 / 47.4.13

The Director is player-centric. Do not add a dependency on Rail Crawler, Epic Fight,
Tinkers' Construct, or individual mob mods. Optional entity types belong in datapack
ecology definitions and must be skipped safely when absent.

## Validation and commits

Run `./gradlew verifyFast` for deterministic checks and `./gradlew verifyFull` for the
Forge GameTest lane. Commit coherent validated changes and push only when a canonical
remote exists. Do not commit build output, runtime worlds, logs, or IDE state.

# Depth Director

A small server-authoritative underground encounter director for Better Content. It
groups nearby players, builds pressure from time and depth, and sends bounded packets
from unseen, dark, reachable cave routes. Overworld encounter ecologies are reloadable
datapack JSON; other natural dimensions use their native hostile palette. The authored
Overworld territories are selected only from deterministic world-seed noise and player
position. They never inspect or target Minecraft biome IDs.

The runtime has no Rail Crawler, Epic Fight, Tinkers' Construct, or mob-mod API
dependency. The pack includes Downed Player Revival; the Director detects it at runtime
and pauses a surge for rescue without coupling its core logic to that mod.

The built-in catalogue is split into five synthetic cave ecologies: undead, carrion,
spirits, sculk, and end. Director rosters intentionally exclude the base
`minecraft:zombie` and `minecraft:skeleton`. Optional entity IDs are resolved at spawn
time and skipped when their mod is absent. Structure, ritual, progression, summoner,
splitter, duplicator, offspring-generating, and aquatic-only mobs are not adopted.

Each roster entry may override its role's budget `cost` and set
`maximum_per_packet` or `maximum_per_encounter`. Missing or zero values retain the old
role cost and unlimited-cap behavior. Encounter caps scale with participant count;
packet caps remain absolute so every packet preserves its intended composition.

`./gradlew verifyWorld` is an explicit manual qualification lane. It starts a disposable
Forge GameTest world with the pack's exact Born in Chaos, Goety, Deeper and Darker,
Quark, Tinkers' Construct, and Enderman Overhaul catalogue dependencies. It first
resolves and EVENT-spawns every roster entry, then registers 15 real dummy players in
pure natural-noise territories at Y -56, 0, and 32, plus a sea-level sentinel. The
production Director must build pressure for its full unmodified cadence, warn, find a
real hidden route, and spawn only the territory's allocated mobs with provenance and
targeting intact. All cohorts run concurrently and cover about 61 minutes of in-game
time; Forge's GameTest server may advance those ticks faster than wall time. The lane is
intentionally excluded from `check`, `verifyFast`, `verifyFull`, and normal builds.

# Depth Director

A small server-authoritative underground encounter director for Better Content. It
groups nearby players, builds pressure from time and depth, and sends bounded packets
from unseen, dark, reachable cave routes. Overworld encounter ecologies are reloadable
datapack JSON; other natural dimensions use their native hostile palette.

The runtime has no Rail Crawler, Epic Fight, Tinkers' Construct, or mob-mod API
dependency. The pack includes Downed Player Revival; the Director detects it at runtime
and pauses a surge for rescue without coupling its core logic to that mod.

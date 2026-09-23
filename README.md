# TrialRooms

> Build keyed trial dungeons with levelled MythicMobs encounters and rarity-rolled loot for TF-Minecraft.

TrialRooms turns ordinary builds into repeatable combat rooms. Administrators place
keyed entrances, levelled spawners, sealing doors, and loot chests in-game, then
players fight through MythicMobs encounters to earn keys that open the room's
rewards.

## Features

- **Keyed entrances** — lodestone entrances teleport players holding a configured key
  item into the room, and an exit lodestone returns them to the entrance.
- **Levelled encounters** — configured spawner blocks spawn MythicMobs mobs when players
  approach, with health and damage scaled by the spawner's level.
- **Sealing doors** — chosen blocks appear while an encounter is running and disappear
  once it is cleared.
- **Rarity-rolled keys** — cleared spawners, and occasionally their mobs, drop keys whose
  rarity and pre-rolled loot depend on level and configurable loot tables.
- **Loot chests** — keys open chests bound to a spawner's cooldown or independent
  chests that are always available.
- **Dungeon rules** — natural healing is suppressed inside, PvP between players inside is
  blocked, and configured items convert to Denar on exit.
- **In-game editing** — spawners, entrances, doors, and chests are configured with an
  edit tool and inventory menus, and their state survives server restarts.

Originally created by [Drefvelin](https://github.com/Drefvelin).

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/TrialRooms/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and pre-existing
material retain their own licenses.

#!/usr/bin/env python3
"""Build the greybox dungeon room kit: one vanilla structure (.nbt) and one sidecar (.yml) per room.

Usage: python tools/rooms/build_greybox.py [output-dir]
Default output: src/main/resources/dungeons/kits/greybox
Needs nbtlib (pip install nbtlib).

Kit rules (the plugin checks the same ones):
- Cells are CELL x CELL blocks. A room template is a whole number of cells wide and deep.
- Each room has its own walls inside its cells. Doorways are DOOR_W wide and DOOR_H tall, centred
  on a cell edge (blocks 14-17 for a 32 cell), starting one block above the floor (y = 1).
- No jumps: height changes use stairs. Mobs get at least 3 blocks of headroom.
"""
import gzip
import io
import os
import sys

import nbtlib
from nbtlib import Compound, Int, List, String

DATA_VERSION = 4556  # Minecraft 1.21.10
CELL = 32
DOOR_W = 4
DOOR_H = 4
KIT = "greybox"

FLOOR = "minecraft:polished_andesite"
WALL = "minecraft:stone_bricks"
CEIL = "minecraft:stone_bricks"
LIGHT = "minecraft:sea_lantern"
PILLAR = "minecraft:polished_deepslate"
# one wall band colour per room type, so greybox rooms read at a glance
BAND = {
    "start": "minecraft:light_blue_concrete",
    "corridor": "minecraft:gray_concrete",
    "combat": "minecraft:red_concrete",
    "rest": "minecraft:lime_concrete",
    "treasure": "minecraft:yellow_concrete",
    "boss": "minecraft:black_concrete",
    "vault": "minecraft:purple_concrete",
}

SIDES = {"north", "east", "south", "west"}


class Room:
    def __init__(self, rid, rtype, wc, dc, height):
        self.id, self.type, self.wc, self.dc, self.h = rid, rtype, wc, dc, height
        self.w, self.d = wc * CELL, dc * CELL
        self.blocks = {}
        self.doors = []  # (cx, cz, side)
        self.markers = []  # (kind, tag, x, y, z, facing)
        self.weight = 1
        self.max_per_run = 0

    def put(self, x, y, z, name):
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.d:
            self.blocks[(x, y, z)] = name

    def clear(self, x, y, z):
        self.blocks.pop((x, y, z), None)

    def fill(self, x0, y0, z0, x1, y1, z1, name):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    if name is None:
                        self.clear(x, y, z)
                    else:
                        self.put(x, y, z, name)

    def door(self, cx, cz, side):
        assert side in SIDES
        self.doors.append((cx, cz, side))

    def marker(self, kind, x, y, z, tag="", facing=None):
        self.markers.append((kind, tag, x, y, z, facing))

    # ---- building ----
    def shell(self):
        """Floor, outer walls with a colour band, ceiling with lights, and doorway openings."""
        w, d, h = self.w, self.d, self.h
        self.fill(0, 0, 0, w - 1, 0, d - 1, FLOOR)
        self.fill(0, h - 1, 0, w - 1, h - 1, d - 1, CEIL)
        band = BAND[self.type]
        for y in range(1, h - 1):
            mat = band if y == 6 else WALL
            self.fill(0, y, 0, w - 1, y, 0, mat)
            self.fill(0, y, d - 1, w - 1, y, d - 1, mat)
            self.fill(0, y, 0, 0, y, d - 1, mat)
            self.fill(w - 1, y, 0, w - 1, y, d - 1, mat)
        for x in range(4, w - 3, 6):
            for z in range(4, d - 3, 6):
                self.put(x, h - 1, z, LIGHT)
        start = (CELL - DOOR_W) // 2
        for cx, cz, side in self.doors:
            bx, bz = cx * CELL, cz * CELL
            for k in range(start, start + DOOR_W):
                for y in range(1, 1 + DOOR_H):
                    if side == "north":
                        self.clear(bx + k, y, bz)
                    elif side == "south":
                        self.clear(bx + k, y, bz + CELL - 1)
                    elif side == "west":
                        self.clear(bx, y, bz + k)
                    else:
                        self.clear(bx + CELL - 1, y, bz + k)

    def pillar(self, x, z):
        self.fill(x, 1, z, x + 1, self.h - 2, z + 1, PILLAR)

    # ---- output ----
    def write(self, out_dir):
        palette = sorted(set(self.blocks.values()))
        index = {n: i for i, n in enumerate(palette)}
        root = Compound({
            "DataVersion": Int(DATA_VERSION),
            "size": List[Int]([Int(self.w), Int(self.h), Int(self.d)]),
            "palette": List[Compound]([state(n) for n in palette]),
            "blocks": List[Compound]([
                Compound({"state": Int(index[n]), "pos": List[Int]([Int(x), Int(y), Int(z)])})
                for (x, y, z), n in sorted(self.blocks.items())
            ]),
            "entities": List[Compound]([]),
        })
        # gzip with a fixed timestamp, so rebuilding an unchanged room gives the same bytes
        raw = io.BytesIO()
        nbtlib.File(root).write(raw)
        with open(os.path.join(out_dir, self.id + ".nbt"), "wb") as f:
            f.write(gzip.compress(raw.getvalue(), mtime=0))
        lines = [
            "# Generated by tools/rooms/build_greybox.py. Edit the script, not this file.",
            f"type: {self.type}",
            f"footprint: {self.wc}x{self.dc}",
            f"weight: {self.weight}",
            f"max-per-run: {self.max_per_run}",
            f"tags: [{KIT}]",
            "doorways:",
        ]
        for cx, cz, side in self.doors:
            lines.append(f"  - {{cell: '{cx},{cz}', side: {side}, width: {DOOR_W}}}")
        lines.append("markers:" if self.markers else "markers: []")
        for kind, tag, x, y, z, facing in self.markers:
            entry = f"  - {{kind: {kind}, at: '{x},{y},{z}'"
            if tag:
                entry += f", tag: {tag}"
            if facing:
                entry += f", facing: {facing}"
            lines.append(entry + "}")
        with open(os.path.join(out_dir, self.id + ".yml"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines) + "\n")
        return self.id


def state(name):
    """A palette entry from 'minecraft:block' or 'minecraft:block[key=value,...]'."""
    if "[" not in name:
        return Compound({"Name": String(name)})
    base, props = name[:-1].split("[", 1)
    pairs = dict(p.split("=", 1) for p in props.split(","))
    return Compound({"Name": String(base), "Properties": Compound({k: String(v) for k, v in pairs.items()})})


def all_doors(room):
    for side in ("north", "east", "south", "west"):
        room.door(0, 0, side)


def build():
    rooms = []

    r = Room("start_hall", "start", 1, 1, 12)
    all_doors(r)
    r.shell()
    r.marker("entry", 16, 1, 16, facing="north")
    rooms.append(r)

    r = Room("corridor_straight", "corridor", 1, 1, 8)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.shell()
    # an 8-wide hall: fill the sides of the cell
    r.fill(1, 1, 1, 11, 6, 30, WALL)
    r.fill(20, 1, 1, 30, 6, 30, WALL)
    rooms.append(r)

    r = Room("corridor_bend", "corridor", 1, 1, 8)
    r.door(0, 0, "north")
    r.door(0, 0, "east")
    r.shell()
    r.fill(1, 1, 1, 11, 6, 30, WALL)      # west side
    r.fill(12, 1, 20, 30, 6, 30, WALL)    # south side
    r.fill(20, 1, 1, 30, 6, 11, WALL)     # north-east corner
    rooms.append(r)

    r = Room("combat_hall", "combat", 1, 1, 12)
    all_doors(r)
    r.shell()
    for x, z in ((8, 8), (22, 8), (8, 22), (22, 22)):
        r.pillar(x, z)
    for x, z in ((6, 16), (26, 16), (16, 6), (16, 26)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 12, 1, 12, tag="ranged")
    r.marker("spawn", 20, 1, 20, tag="ranged")
    rooms.append(r)

    r = Room("combat_dais", "combat", 1, 1, 12)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.door(0, 0, "east")
    r.shell()
    # a raised centre one block up, ringed by stairs so it is walked onto, never jumped
    r.fill(11, 1, 11, 20, 1, 20, "minecraft:mossy_stone_bricks")
    for k in range(11, 21):
        r.put(k, 1, 10, "minecraft:stone_brick_stairs[facing=south]")
        r.put(k, 1, 21, "minecraft:stone_brick_stairs[facing=north]")
        r.put(10, 1, k, "minecraft:stone_brick_stairs[facing=east]")
        r.put(21, 1, k, "minecraft:stone_brick_stairs[facing=west]")
    for x, z in ((14, 14), (18, 14), (14, 18), (18, 18)):
        r.marker("spawn", x, 2, z, tag="melee")
    r.marker("spawn", 4, 1, 28, tag="ranged")
    r.marker("spawn", 28, 1, 28, tag="ranged")
    rooms.append(r)

    r = Room("combat_long", "combat", 1, 2, 12)
    r.door(0, 0, "north")
    r.door(0, 0, "east")
    r.door(0, 1, "west")
    r.door(0, 1, "south")
    r.shell()
    for z in (12, 24, 40, 52):
        r.pillar(8, z)
        r.pillar(22, z)
    for z in (10, 30, 50):
        r.marker("spawn", 12, 1, z, tag="melee")
        r.marker("spawn", 20, 1, z, tag="melee")
    r.marker("spawn", 16, 1, 32, tag="ranged")
    rooms.append(r)

    r = Room("rest_camp", "rest", 1, 1, 10)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.shell()
    r.put(16, 1, 16, "minecraft:campfire")
    r.marker("rest", 16, 1, 16)
    rooms.append(r)

    r = Room("treasure_nook", "treasure", 1, 1, 8)
    r.door(0, 0, "south")
    r.shell()
    r.fill(1, 1, 1, 9, 6, 30, WALL)
    r.fill(22, 1, 1, 30, 6, 30, WALL)
    r.fill(10, 1, 1, 21, 6, 9, WALL)
    r.marker("chest", 16, 1, 11, facing="south")
    rooms.append(r)

    r = Room("boss_arena", "boss", 1, 2, 16)
    r.door(0, 0, "north")
    r.door(0, 1, "south")
    r.shell()
    for x, z in ((6, 14), (24, 14), (6, 48), (24, 48)):
        r.pillar(x, z)
    r.marker("spawn", 16, 1, 24, tag="boss", facing="south")
    r.max_per_run = 1
    rooms.append(r)

    r = Room("vault_exit", "vault", 1, 1, 10)
    r.door(0, 0, "south")
    r.shell()
    r.marker("chest", 16, 1, 10, facing="south")
    r.marker("exit", 16, 1, 6, facing="south")
    rooms.append(r)
    return rooms


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repo, "src", "main", "resources", "dungeons", "kits", KIT)
    rooms_dir = os.path.join(out, "rooms")
    os.makedirs(rooms_dir, exist_ok=True)
    ids = [r.write(rooms_dir) for r in build()]
    with open(os.path.join(out, "kit.yml"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# Generated by tools/rooms/build_greybox.py.\n"
                f"cell-size: {CELL}\ncell-height: 24\ndoor-height: {DOOR_H}\nseal-block: {WALL}\n"
                "door-block: minecraft:iron_bars\n"
                "# Placeholder enemies until the fights phase: existing dungeon skeletons.\n"
                "mobs:\n  melee: dSkeleton_footman_em\n  ranged: dSkeleton_Archer_em\n"
                "  elite: dSkeleton_Halberdier_em\n  boss: dSkeleton_Tank_em\n")
    files = ["kit.yml"] + [f"rooms/{i}.{ext}" for i in ids for ext in ("yml", "nbt")]
    with open(os.path.join(out, "files.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(files) + "\n")
    print(f"{len(ids)} rooms written to {out}")


if __name__ == "__main__":
    main()

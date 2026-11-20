"""Shared room-building code for the dungeon kits: rooms, structure output, and the walk check.

Kit rules (the plugin checks the same ones):
- Cells are CELL x CELL blocks. A room template is a whole number of cells wide and deep.
- Each room has its own walls inside its cells. Doorways are DOOR_W wide and DOOR_H tall, centred
  on a cell edge (blocks 14-17 for a 32 cell), starting one block above their floor (y = floor + 1).
  A doorway's floor may be above the room's base: joined doorways meet at the same height, so the
  plugin raises or lowers the next room to match.
- A room may fill only some cells of its box (L, T, U shapes). Nothing is written in the other cells,
  not even air, so placing the room never touches a neighbour there.
- No jumps: height changes use stairs or slabs, every doorway has a flat landing at least 4 blocks
  deep, and every doorway and spawn point can be walked to from every doorway (walk_check).
  Mobs get at least 3 blocks of headroom.
"""
import gzip
import io
import os
import random

import nbtlib
from nbtlib import Compound, Int, List, String

DATA_VERSION = 4556  # Minecraft 1.21.10
CELL = 32
DOOR_W = 4
DOOR_H = 4
SIDES = {"north", "east", "south", "west"}

# blocks a player walks through
PASSABLE_PREFIXES = ("minecraft:air", "minecraft:snow[", "minecraft:light[", "minecraft:torch",
                     "minecraft:wall_torch", "minecraft:soul_torch", "minecraft:soul_wall_torch",
                     "minecraft:white_carpet", "minecraft:light_blue_carpet", "minecraft:cyan_carpet")
PASSABLE_EXACT = {"minecraft:snow", "minecraft:air", "minecraft:light"}


def state(name):
    """A palette entry from 'minecraft:block' or 'minecraft:block[key=value,...]'."""
    if "[" not in name:
        return Compound({"Name": String(name)})
    base, props = name[:-1].split("[", 1)
    pairs = dict(p.split("=", 1) for p in props.split(","))
    return Compound({"Name": String(base), "Properties": Compound({k: String(v) for k, v in pairs.items()})})


class Room:
    """One room template: blocks, doorways and markers, written as a structure plus a sidecar."""

    def __init__(self, rid, rtype, wc, dc, height, cells=None, kit="greybox"):
        self.id, self.type, self.wc, self.dc, self.h = rid, rtype, wc, dc, height
        self.kit = kit
        self.w, self.d = wc * CELL, dc * CELL
        # the cells of the box the room fills; None = all of them
        self.cells = None if cells is None else set(cells)
        self.blocks = {}
        self.doors = []  # (cx, cz, side, floor)
        self.markers = []  # (kind, tag, x, y, z, facing)
        self.weight = 1
        self.max_per_run = 0
        self.rng = random.Random(rid)

    def fills(self, cx, cz):
        return 0 <= cx < self.wc and 0 <= cz < self.dc and (self.cells is None or (cx, cz) in self.cells)

    def put(self, x, y, z, name):
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.d and self.fills(x // CELL, z // CELL):
            self.blocks[(x, y, z)] = name

    def get(self, x, y, z):
        return self.blocks.get((x, y, z), "minecraft:air")

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

    def door(self, cx, cz, side, floor=0):
        assert side in SIDES
        self.doors.append((cx, cz, side, floor))

    def marker(self, kind, x, y, z, tag="", facing=None):
        self.markers.append((kind, tag, x, y, z, facing))

    # ---- shared geometry ----
    def outer_edges(self):
        """Every wall run of the room: (cx, cz, side) for each filled cell side that faces out."""
        for cx in range(self.wc):
            for cz in range(self.dc):
                if not self.fills(cx, cz):
                    continue
                for side, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("west", (-1, 0)), ("east", (1, 0))):
                    if not self.fills(cx + dx, cz + dz):
                        yield cx, cz, side

    def edge_blocks(self, cx, cz, side):
        """The (x, z) positions along one outer cell side, in order."""
        x0, z0 = cx * CELL, cz * CELL
        for k in range(CELL):
            if side == "north":
                yield x0 + k, z0
            elif side == "south":
                yield x0 + k, z0 + CELL - 1
            elif side == "west":
                yield x0, z0 + k
            else:
                yield x0 + CELL - 1, z0 + k

    def open_doors(self):
        start = (CELL - DOOR_W) // 2
        for cx, cz, side, floor in self.doors:
            bx, bz = cx * CELL, cz * CELL
            for k in range(start, start + DOOR_W):
                for y in range(floor + 1, floor + 1 + DOOR_H):
                    if side == "north":
                        self.clear(bx + k, y, bz)
                    elif side == "south":
                        self.clear(bx + k, y, bz + CELL - 1)
                    elif side == "west":
                        self.clear(bx, y, bz + k)
                    else:
                        self.clear(bx + CELL - 1, y, bz + k)

    def landing(self, cx, cz, side, floor, depth=5):
        """Spot (x, y, z) a door's landing starts at, and the inward step (dx, dz)."""
        mid = CELL // 2
        bx, bz = cx * CELL, cz * CELL
        if side == "north":
            return (bx + mid, floor + 1, bz + 1), (0, 1)
        if side == "south":
            return (bx + mid, floor + 1, bz + CELL - 2), (0, -1)
        if side == "west":
            return (bx + 1, floor + 1, bz + mid), (1, 0)
        return (bx + CELL - 2, floor + 1, bz + mid), (-1, 0)

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
        script = "build_greybox.py" if self.kit == "greybox" else f"build_{self.kit}.py"
        lines = [
            f"# Generated by tools/rooms/{script}. Edit the script, not this file.",
            f"type: {self.type}",
            f"footprint: {self.wc}x{self.dc}",
        ] + ([] if self.cells is None else
             ["cells: [" + ", ".join(f"'{cx},{cz}'" for cx, cz in sorted(self.cells)) + "]"]) + [
            f"weight: {self.weight}",
            f"max-per-run: {self.max_per_run}",
            f"tags: [{self.kit}]",
            "doorways:",
        ]
        for cx, cz, side, floor in self.doors:
            lines.append(f"  - {{cell: '{cx},{cz}', side: {side}, width: {DOOR_W}"
                         + (f", floor: {floor}" if floor else "") + "}")
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


# ---------------------------------------------------------------------------- walk check

def _passable(name):
    return name in PASSABLE_EXACT or name.startswith(PASSABLE_PREFIXES)


def _half(name):
    """True for blocks a player stands on half a block up and walks onto without jumping."""
    base = name.split("[", 1)[0]
    if base.endswith("_stairs") or base.endswith("campfire"):
        return True
    return base.endswith("_slab") and "type=bottom" in name


def _stand_heights(room, x, z):
    """Heights, in half blocks, a player can stand at in one column (with 2 blocks of clearance)."""
    out = []
    for y in range(0, room.h):
        here = room.get(x, y, z)
        if _passable(here):
            continue
        # a slab is stood on half a block up; a stair both on its low step and its high back
        options = [y * 2 + 1] if _half(here) else [y * 2 + 2]
        if here.split("[", 1)[0].endswith("_stairs"):
            options.append(y * 2 + 2)
        for h in options:
            # clearance: every block the player's 1.8 blocks overlap, from the standing height up
            blocks = range((h + 1) // 2, (h + 5) // 2)
            if blocks.stop <= room.h and all(_passable(room.get(x, yy, z)) for yy in blocks):
                out.append(h)
    return out


def walk_check(room, max_drop=6):
    """Problems with walking the room: unreachable doorways or spawn points, shallow landings.

    A step is to a side neighbour at most half a block up (stairs and bottom slabs) or a drop of at
    most max_drop blocks. Everything must be reached from every doorway.
    """
    problems = []
    stands = {}

    def heights(x, z):
        if (x, z) not in stands:
            stands[(x, z)] = _stand_heights(room, x, z) if 0 <= x < room.w and 0 <= z < room.d else []
        return stands[(x, z)]

    def reach(start):
        seen = {start}
        todo = [start]
        while todo:
            x, h, z = todo.pop()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                for h2 in heights(x + dx, z + dz):
                    if h2 - h <= 1 and h - h2 <= max_drop * 2:
                        s = (x + dx, h2, z + dz)
                        if s not in seen:
                            seen.add(s)
                            todo.append(s)
        return seen

    starts = []
    for cx, cz, side, floor in room.doors:
        (x, y, z), (dx, dz) = room.landing(cx, cz, side, floor)
        h = y * 2
        for k in range(4):
            if h not in heights(x + dx * k, z + dz * k):
                problems.append(f"{room.id}: the {side} doorway at {cx},{cz} has no flat landing {k + 1} blocks in")
                break
        starts.append(((x, h, z), f"{side} doorway at {cx},{cz}"))
    if not starts:
        return problems
    first = reach(starts[0][0])
    for s, label in starts[1:]:
        if s not in first:
            problems.append(f"{room.id}: the {label} can't be walked to from the first doorway")
        elif starts[0][0] not in reach(s):
            problems.append(f"{room.id}: the first doorway can't be walked to from the {label}")
    for kind, tag, x, y, z, facing in room.markers:
        if kind not in ("spawn", "rest", "entry", "chest", "exit"):
            continue
        # the run stands players one block above a rest marker (it marks the block they wake on)
        feet = y + 1 if kind == "rest" else y
        if not any(c in first for c in ((x, feet * 2, z), (x, feet * 2 - 1, z))):
            problems.append(f"{room.id}: the {kind} {tag} marker at {x},{y},{z} can't be walked to")
    return problems

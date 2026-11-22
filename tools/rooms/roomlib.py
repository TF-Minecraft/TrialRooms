"""Shared room-building code for the dungeon kits: rooms, structure output, and the walk check.

Kit rules (the plugin checks the same ones):
- Cells are cell x cell blocks (CELL = 32 by default; a kit may use another even size, the same for
  all its rooms). A room template is a whole number of cells wide and deep.
- Each room has its own walls inside its cells. Doorways are DOOR_W wide and DOOR_H tall, centred
  on a cell edge (blocks 14-17 for a 32 cell), starting one block above their floor (y = floor + 1).
  A doorway's floor may be above the room's base: joined doorways meet at the same height, so the
  plugin raises or lowers the next room to match.
- A room may fill only some cells of its box (L, T, U shapes). Nothing is written in the other cells,
  not even air, so placing the room never touches a neighbour there.
- The room is closed: every block of its outer shell (its sides, floor and roof) is a solid block
  nobody can see through, except the doorway openings (shell_check). The dungeon world around
  the rooms is empty void, and a glimpse of it breaks the cave.
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

def state(name):
    """A palette entry from 'minecraft:block' or 'minecraft:block[key=value,...]'."""
    if "[" not in name:
        return Compound({"Name": String(name)})
    base, props = name[:-1].split("[", 1)
    pairs = dict(p.split("=", 1) for p in props.split(","))
    return Compound({"Name": String(base), "Properties": Compound({k: String(v) for k, v in pairs.items()})})


class Room:
    """One room template: blocks, doorways and markers, written as a structure plus a sidecar."""

    def __init__(self, rid, rtype, wc, dc, height, cells=None, kit="greybox", cell=CELL):
        self.id, self.type, self.wc, self.dc, self.h = rid, rtype, wc, dc, height
        self.kit = kit
        self.cell = cell
        self.w, self.d = wc * cell, dc * cell
        # the cells of the box the room fills; None = all of them
        self.cells = None if cells is None else set(cells)
        self.blocks = {}
        self.doors = []  # (cx, cz, side, floor)
        self.markers = []  # (kind, tag, x, y, z, facing)
        self.weight = 1
        self.max_per_run = 0
        self.threat = 1.0  # share of a fight's budget: small rooms below 1, big ones above
        self.rng = random.Random(rid)

    def fills(self, cx, cz):
        return 0 <= cx < self.wc and 0 <= cz < self.dc and (self.cells is None or (cx, cz) in self.cells)

    def put(self, x, y, z, name):
        if 0 <= x < self.w and 0 <= y < self.h and 0 <= z < self.d and self.fills(x // self.cell, z // self.cell):
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
        x0, z0 = cx * self.cell, cz * self.cell
        for k in range(self.cell):
            if side == "north":
                yield x0 + k, z0
            elif side == "south":
                yield x0 + k, z0 + self.cell - 1
            elif side == "west":
                yield x0, z0 + k
            else:
                yield x0 + self.cell - 1, z0 + k

    def open_doors(self):
        start = (self.cell - DOOR_W) // 2
        for cx, cz, side, floor in self.doors:
            bx, bz = cx * self.cell, cz * self.cell
            for k in range(start, start + DOOR_W):
                for y in range(floor + 1, floor + 1 + DOOR_H):
                    if side == "north":
                        self.clear(bx + k, y, bz)
                    elif side == "south":
                        self.clear(bx + k, y, bz + self.cell - 1)
                    elif side == "west":
                        self.clear(bx, y, bz + k)
                    else:
                        self.clear(bx + self.cell - 1, y, bz + k)

    def landing(self, cx, cz, side, floor, depth=5):
        """Spot (x, y, z) a door's landing starts at, and the inward step (dx, dz)."""
        mid = self.cell // 2
        bx, bz = cx * self.cell, cz * self.cell
        if side == "north":
            return (bx + mid, floor + 1, bz + 1), (0, 1)
        if side == "south":
            return (bx + mid, floor + 1, bz + self.cell - 2), (0, -1)
        if side == "west":
            return (bx + 1, floor + 1, bz + mid), (1, 0)
        return (bx + self.cell - 2, floor + 1, bz + mid), (-1, 0)

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
        ] + ([] if self.threat == 1.0 else [f"threat: {self.threat:g}"]) + [
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

# Blocks a player walks through. Snow layers count only up to 2 layers (a quarter block).
PASSABLE_BASES = {
    "minecraft:air", "minecraft:cave_air", "minecraft:light", "minecraft:torch", "minecraft:wall_torch",
    "minecraft:soul_torch", "minecraft:soul_wall_torch", "minecraft:white_carpet", "minecraft:light_blue_carpet",
    "minecraft:cyan_carpet",
}
# Blocks nobody should stand on or walk through: they hurt, trap or drown.
HAZARD_BASES = {
    "minecraft:water", "minecraft:lava", "minecraft:campfire", "minecraft:soul_campfire", "minecraft:fire",
    "minecraft:soul_fire", "minecraft:powder_snow", "minecraft:magma_block",
}
FACING = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
TALL_SUFFIXES = ("_wall", "_fence", "_fence_gate", "_pane")
TALL_BASES = {"minecraft:iron_bars", "minecraft:glass_pane"}


def _props(name):
    if "[" not in name:
        return name, {}
    base, rest = name[:-1].split("[", 1)
    return base, dict(p.split("=", 1) for p in rest.split(","))


def _passable(name):
    base, props = _props(name)
    if base == "minecraft:snow":
        return int(props.get("layers", "1")) <= 2
    return base in PASSABLE_BASES


def _stand_options(room, x, z):
    """Where a player can stand in one column, with 2 blocks of clearance.

    Returns {height in half blocks: (kind, facing)}: kind 'flat' (a full block or a bottom slab),
    'low' (a stair's front step) or 'high' (a stair's back); facing is the way a stair climbs.
    """
    out = {}
    for y in range(0, room.h):
        name = room.get(x, y, z)
        base, props = _props(name)
        if _passable(name) or base in HAZARD_BASES or base.endswith(TALL_SUFFIXES) or base in TALL_BASES:
            continue  # walls, fences and panes are 1.5 tall: never stood on without a jump
        if base.endswith("_stairs") and props.get("half", "bottom") == "bottom":
            facing = props.get("facing", "north")  # Minecraft's defaults
            options = [(y * 2 + 1, "low", facing), (y * 2 + 2, "high", facing)]
        elif base.endswith("_slab") and props.get("type", "bottom") == "bottom":
            options = [(y * 2 + 1, "flat", None)]
        else:
            options = [(y * 2 + 2, "flat", None)]
        for h, kind, facing in options:
            if _clear(room, x, z, h, h):
                out[h] = (kind, facing)
    return out


def _clear(room, x, z, lo, hi):
    """Whether a player fits in a column from standing height lo up to standing height hi (half blocks)."""
    blocks = range((lo + 1) // 2, (hi + 5) // 2)
    return blocks.stop <= room.h and all(_passable(room.get(x, yy, z)) for yy in blocks)


SEE_THROUGH_SUFFIXES = ("_slab", "_stairs", "_wall", "_fence", "_fence_gate", "_pane", "_door", "_trapdoor",
                        "campfire", "_leaves", "_carpet")
SEE_THROUGH_BASES = {"minecraft:ice", "minecraft:snow", "minecraft:iron_bars", "minecraft:barrier", "minecraft:chain",
                     "minecraft:lantern", "minecraft:soul_lantern"}


def see_through(name):
    """Whether a block lets the eye pass: air, light, ice, glass and anything less than a full cube."""
    base, _ = _props(name)
    return _passable(name) or base in PASSABLE_BASES or base in SEE_THROUGH_BASES or "glass" in base         or base.endswith(SEE_THROUGH_SUFFIXES)


def door_openings(room):
    """Every (x, y, z) a doorway leaves open in the room's outer wall."""
    start = (room.cell - DOOR_W) // 2
    out = set()
    for cx, cz, side, floor in room.doors:
        for (x, z) in list(room.edge_blocks(cx, cz, side))[start:start + DOOR_W]:
            for y in range(floor + 1, floor + 1 + DOOR_H):
                out.add((x, y, z))
    return out


def shell(room):
    """Every (x, y, z) of the room's outer shell: its roof and bottom layers and each block that faces
    a column outside the room."""
    for x in range(room.w):
        for z in range(room.d):
            if not room.fills(x // room.cell, z // room.cell):
                continue
            edge = any(not (0 <= x + dx < room.w and 0 <= z + dz < room.d)
                       or not room.fills((x + dx) // room.cell, (z + dz) // room.cell)
                       for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            for y in range(room.h):
                if edge or y in (0, room.h - 1):
                    yield x, y, z


def shell_check(room):
    """Shell blocks the void shows through, as messages (at most 5); an empty list means the room is closed."""
    doors = door_openings(room)
    leaks = [f"{room.id}: {room.get(*p)} at {p[0]} {p[1]} {p[2]} lets the void show"
             for p in shell(room) if p not in doors and see_through(room.get(*p))]
    return leaks[:5]


def walk_check(room, max_drop=6):
    """Problems with walking the room, as messages; an empty list means it is fine.

    - every doorway has a flat landing 4 blocks deep across its whole width, and a clear opening;
    - every doorway and every spawn, rest, entry, chest and exit point can be walked to from the first
      doorway, and every spot that can be reached can walk back to it (no pits that trap);
    - a step goes at most half a block up (a stair climbed the way it faces, or a bottom slab), a
      drop at most max_drop blocks, with room for the player's head all along the way.
    """
    problems = []
    cache = {}

    def options(x, z):
        if (x, z) not in cache:
            cache[(x, z)] = _stand_options(room, x, z) if 0 <= x < room.w and 0 <= z < room.d else {}
        return cache[(x, z)]

    def allowed(a, b):
        (x, h, z), (x2, h2, z2) = a, b
        dh = h2 - h
        if dh > 1 or -dh > max_drop * 2:
            return False
        kind, facing = options(x, z)[h]
        kind2, facing2 = options(x2, z2)[h2]
        stair, stair2 = kind in ("low", "high"), kind2 in ("low", "high")
        if (x, z) == (x2, z2):
            return abs(dh) == 1 and stair and stair2  # between a stair's front step and its back
        # A stair's front step (low) is on the side it climbs from, its back (high) on the side it
        # climbs to. A move only counts as a half step where the halves touch.
        d = (x2 - x, z2 - z)
        back, back2 = FACING.get(facing), FACING.get(facing2)
        ahead = lambda f: f is not None and d == f
        behind = lambda f: f is not None and d == (-f[0], -f[1])
        if kind == "high" and behind(back) or kind == "low" and ahead(back):
            return False  # leaving through the other half: go over this stair's own step first
        if kind2 == "high" and ahead(back2):
            return False  # walking onto a stair's back from its front: its step comes first
        if dh == 1 and kind2 == "low" and behind(back2):
            return False  # walking into a stair's back: it needs a jump
        if stair and stair2 and back == back2 and kind != kind2 and not ahead(back) and not behind(back):
            return False  # side by side, one stair's step only touches the other's step
        if dh < 0 and not _clear(room, x2, z2, h2, h):
            return False  # no room to step over and drop down
        if dh > 0 and not _clear(room, x, z, h, h2):
            return False
        return True

    def neighbours(state):
        x, h, z = state
        for h2 in options(x, z):
            if h2 != h:
                yield (x, h2, z)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for h2 in options(x + dx, z + dz):
                yield (x + dx, h2, z + dz)

    def search(start, forward):
        seen = {start}
        todo = [start]
        while todo:
            s = todo.pop()
            for n in neighbours(s):
                if n not in seen and (allowed(s, n) if forward else allowed(n, s)):
                    seen.add(n)
                    todo.append(n)
        return seen

    starts = []
    start = (room.cell - DOOR_W) // 2
    for cx, cz, side, floor in room.doors:
        bx, bz = cx * room.cell, cz * room.cell
        h = floor * 2 + 2
        bad = None
        for k in range(5):  # 0 = the doorway itself, then 4 blocks in
            for j in range(DOOR_W):
                x, z = {"north": (bx + start + j, bz + k), "south": (bx + start + j, bz + room.cell - 1 - k),
                        "west": (bx + k, bz + start + j), "east": (bx + room.cell - 1 - k, bz + start + j)}[side]
                if k == 0 and not all(_passable(room.get(x, y, z)) for y in range(floor + 1, floor + 1 + DOOR_H)):
                    bad = "its opening is blocked"
                elif options(x, z).get(h, ("", None))[0] != "flat":
                    bad = bad or f"no flat landing {k} blocks in" if k else bad or "no floor in its opening"
        if bad:
            problems.append(f"{room.id}: the {side} doorway at {cx},{cz}: {bad}")
        mid = room.cell // 2
        sx, sz = {"north": (bx + mid, bz + 1), "south": (bx + mid, bz + room.cell - 2),
                  "west": (bx + 1, bz + mid), "east": (bx + room.cell - 2, bz + mid)}[side]
        starts.append(((sx, h, sz), f"{side} doorway at {cx},{cz}"))
    if not starts or problems:
        return problems
    first = starts[0][0]
    reach = search(first, True)
    back = search(first, False)
    for s, label in starts[1:]:
        if s not in reach:
            problems.append(f"{room.id}: the {label} can't be walked to from the first doorway")
    for kind, tag, x, y, z, facing in room.markers:
        if kind not in ("spawn", "rest", "entry", "chest", "exit"):
            continue
        # the run stands players one block above a rest marker (it marks the block they wake on)
        feet = y + 1 if kind == "rest" else y
        if not any(c in reach for c in ((x, feet * 2, z), (x, feet * 2 - 1, z), (x, feet * 2 + 1, z))):
            problems.append(f"{room.id}: the {kind} {tag} marker at {x},{y},{z} can't be walked to")
    traps = sorted(reach - back)
    if traps:
        x, h, z = traps[0]
        problems.append(f"{room.id}: {len(traps)} spots can be walked into but not out of, such as {x},{h / 2},{z}")
    return problems

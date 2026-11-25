#!/usr/bin/env python3
"""Build the Seithr dungeon room kit (ice caves and a frozen fortress): one structure (.nbt) and
sidecar (.yml) per room.

Usage: python tools/rooms/build_seithr.py [output-dir]
Default output: src/main/resources/dungeons/kits/seithr
Needs nbtlib (pip install nbtlib). Kit rules: see roomlib.py.

The kit uses 16-block cells, so rooms come in many sizes: a 16x16 guard post, a 32x16 gallery, a
48x48 throne hall, and L, T, U and cross shapes in between. Every room is generated from a seed:

- 'cave' rooms have ragged walls of packed ice, blue ice, snow and dark stone, ceilings that sag
  toward the walls, icicles, ice spikes, glowing ice pillars and snow drifts;
- 'hall' rooms are the fortress: deepslate brick walls with spruce ribs and ice veins, tile floors
  half buried in snow, pillars, beams and hanging soul lanterns.

Some rooms have parts on different levels (terraces, a sunken pit) joined by wide stairs, with rails
along the drops. Floors are snow, calcite and stone, never ice (players would slide), and there is
no powder snow (it traps players). The plugin turns random ticks off in the dungeon world, so ice
and snow by bright light never melt. A seed whose room fails the walk check is skipped.
"""
import os
import sys
from collections import deque

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roomlib import DOOR_H, DOOR_W, Room, door_openings, see_through, shell, shell_check, walk_check  # noqa: E402

KIT = "seithr"
CELL = 16
DOOR_START = (CELL - DOOR_W) // 2  # blocks 6-9 of a cell edge

TILE = "minecraft:deepslate_tiles"
TILE_CRACKED = "minecraft:cracked_deepslate_tiles"
POLISHED = "minecraft:polished_deepslate"
BRICK = "minecraft:deepslate_bricks"
BRICK_CRACKED = "minecraft:cracked_deepslate_bricks"
COBBLED = "minecraft:cobbled_deepslate"
CHISELED = "minecraft:chiseled_deepslate"
DEEPSLATE = "minecraft:deepslate"
SNOW = "minecraft:snow_block"
CALCITE = "minecraft:calcite"
DIORITE = "minecraft:polished_diorite"
ICE = "minecraft:packed_ice"
BLUE = "minecraft:blue_ice"
CLEAR_ICE = "minecraft:ice"
GLASS = "minecraft:light_blue_stained_glass"
BEAM_X = "minecraft:stripped_spruce_log[axis=x]"
BEAM_Z = "minecraft:stripped_spruce_log[axis=z]"
BEAM = "minecraft:stripped_spruce_log[axis=y]"
LANTERN = "minecraft:soul_lantern[hanging=true]"
CHAIN = "minecraft:chain[axis=y]"
SEA = "minecraft:sea_lantern"
HEARTH = "minecraft:soul_campfire[lit=true]"
BENCH = "minecraft:spruce_slab[type=bottom]"
RAIL = "minecraft:deepslate_brick_wall"
BONES = "minecraft:bone_block[axis=y]"
BARREL = "minecraft:barrel[facing=up]"
LIGHT = "minecraft:light[level=11]"
AIR = "minecraft:air"
FLOORS = (SNOW, CALCITE, DIORITE, TILE, TILE_CRACKED, POLISHED)


def layer(n):
    return f"minecraft:snow[layers={n}]"


def stairs(facing):
    return f"minecraft:deepslate_brick_stairs[facing={facing}]"


SIDE_STEP = {"north": (0, -1), "south": (0, 1), "west": (-1, 0), "east": (1, 0)}
OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west"}


def edge_col(cx, cz, side, k, j):
    """The column k rows in from a cell's `side` edge, j blocks along it."""
    bx, bz = cx * CELL, cz * CELL
    return {"north": (bx + j, bz + k), "south": (bx + j, bz + CELL - 1 - k),
            "west": (bx + k, bz + j), "east": (bx + CELL - 1 - k, bz + j)}[side]


# ---------------------------------------------------------------------------- noise

def _hash(seed, x, y, z):
    h = (x * 374761393 + y * 668265263 + z * 1442695041 + seed * 2654435761) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFFFF) / float(0x1000000)


def _smooth(t):
    return t * t * (3 - 2 * t)


def noise(seed, x, z, scale, y=0):
    """Smooth value noise in [0, 1): features about `scale` blocks across."""
    fx, fz = x / scale, z / scale
    x0, z0 = int(fx // 1), int(fz // 1)
    tx, tz = _smooth(fx - x0), _smooth(fz - z0)
    a, b = _hash(seed, x0, y, z0), _hash(seed, x0 + 1, y, z0)
    c, d = _hash(seed, x0, y, z0 + 1), _hash(seed, x0 + 1, y, z0 + 1)
    top = a + (b - a) * tx
    return top + ((c + (d - c) * tx) - top) * tz


# ---------------------------------------------------------------------------- the generator

class SeithrRoom(Room):
    def __init__(self, rid, rtype, wc, dc, height, cells=None):
        super().__init__(rid, rtype, wc, dc, height, cells, kit=KIT, cell=CELL)


class Spec:
    """What a room should be; the generator fills in the rest from a seed."""

    def __init__(self, rid, rtype, wc, dc, height, doors, style="cave", cells=None, rises=None, threat=1.0,
                 weight=1, max_per_run=0, spawns=None, dress=None, inset=0):
        self.id, self.type, self.wc, self.dc, self.h = rid, rtype, wc, dc, height
        self.doors = doors              # [(cx, cz, side)]; a doorway's floor is its cell's rise
        self.style = style              # 'cave' or 'hall'
        self.cells = cells
        self.rises = rises or {}        # {(cx, cz): blocks the cell's floor is raised}
        self.threat = threat
        self.weight = weight
        self.max_per_run = max_per_run
        self.spawns = spawns            # spawn markers; None = by floor area
        self.dress = dress              # a set piece: dress(gen) once the room is shaped
        self.inset = inset              # extra wall thickness: narrows corridors


class Gen:
    """One attempt at a room from a spec and a seed."""

    def __init__(self, spec, seed):
        self.s = spec
        self.seed = seed
        r = self.r = SeithrRoom(spec.id, spec.type, spec.wc, spec.dc, spec.h, spec.cells)
        r.weight, r.max_per_run, r.threat = spec.weight, spec.max_per_run, spec.threat
        r.rng.seed(f"{spec.id}:{seed}")
        self.rng = r.rng
        self.cave = spec.style == "cave"
        self.cols = [(x, z) for x in range(r.w) for z in range(r.d) if r.fills(x // CELL, z // CELL)]
        self.inside = set(self.cols)
        self.floor = {c: spec.rises.get((c[0] // CELL, c[1] // CELL), 0) for c in self.cols}
        self.dist = self._distances()
        self.keep = set()        # columns kept open and bare: landings, stairs
        self.stair = {}          # stair column -> (stair y, facing)
        self.rails = []
        self.taken = set()       # columns holding a piece or a marker's elbow room
        self.open = set()
        self.ceil = {}           # open column -> its lowest ceiling block's y
        self.marked = []

    # ---- shape
    def _distances(self):
        """Steps (8-way) from each column to the nearest column outside the room."""
        dist = {}
        todo = deque()
        for x, z in self.cols:
            if any((x + dx, z + dz) not in self.inside for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
                dist[(x, z)] = 1
                todo.append((x, z))
        while todo:
            x, z = todo.popleft()
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    n = (x + dx, z + dz)
                    if n in self.inside and n not in dist:
                        dist[n] = dist[(x, z)] + 1
                        todo.append(n)
        return dist

    def door_strip(self, cx, cz, side, depth=6, wide=1):
        """A doorway's landing: its width plus `wide` each side, `depth` rows in."""
        return [edge_col(cx, cz, side, k, j) for k in range(1, depth + 1)
                for j in range(DOOR_START - wide, DOOR_START + DOOR_W + wide)]

    def shape(self):
        s = self.s
        narrow = min(self.r.w, self.r.d) <= CELL
        for c in self.cols:
            th = 1 + s.inset
            if self.cave:
                th += int(noise(self.seed, c[0], c[1], 5) * (2.2 if narrow else 3.4))
            if self.dist[c] > th:
                self.open.add(c)
        for cx, cz, side in s.doors:
            self.keep.update(self.door_strip(cx, cz, side))
        self._levels()
        if self.cave:
            for _ in range(2):
                self._smooth()
        self.open |= self.keep
        self._drop_pockets()

    def _smooth(self):
        nxt = set(self.open)
        for c in self.cols:
            if c in self.keep or self.dist[c] <= 1:
                continue
            n = sum((c[0] + dx, c[1] + dz) in self.open for dx in (-1, 0, 1) for dz in (-1, 0, 1) if dx or dz)
            if c not in self.open and n >= 5:
                nxt.add(c)
            elif c in self.open and n <= 2:
                nxt.discard(c)
        self.open = nxt

    def _drop_pockets(self):
        """Open ground joined to the first doorway stays open; cut-off pockets fill in."""
        start = self.door_strip(*self.s.doors[0])[0]
        seen, todo = {start}, [start]
        while todo:
            x, z = todo.pop()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + dx, z + dz)
                if n in self.open and n not in seen:
                    seen.add(n)
                    todo.append(n)
        self.open = seen

    def _levels(self):
        """Wide stairs from each lower cell up to a higher neighbour; rails along the rest of the drop."""
        s = self.s
        for cx in range(s.wc):
            for cz in range(s.dc):
                if not self.r.fills(cx, cz):
                    continue
                low = s.rises.get((cx, cz), 0)
                for side, (dx, dz) in SIDE_STEP.items():
                    ncx, ncz = cx + dx, cz + dz
                    if not self.r.fills(ncx, ncz):
                        continue
                    rise = s.rises.get((ncx, ncz), 0) - low
                    if rise <= 0:
                        continue
                    assert rise <= 5, f"{s.id}: a level step of more than 5 blocks"
                    for k in range(rise + 3):   # stair rows in the lower cell (k = 0 by the edge), then flat
                        for j in range(DOOR_START - 1, DOOR_START + DOOR_W + 1):
                            col = edge_col(cx, cz, side, k, j)
                            self.keep.add(col)
                            if k < rise and DOOR_START <= j < DOOR_START + DOOR_W:
                                self.stair[col] = (low + rise - k, side)
                    for k in range(3):          # a landing at the top
                        for j in range(DOOR_START - 1, DOOR_START + DOOR_W + 1):
                            self.keep.add(edge_col(ncx, ncz, OPPOSITE[side], k, j))
                    for j in range(CELL):
                        if not DOOR_START <= j < DOOR_START + DOOR_W:
                            self.rails.append(edge_col(ncx, ncz, OPPOSITE[side], 0, j))

    def fy(self, c):
        """The y of the floor block in a column."""
        return self.floor[c]

    # ---- blocks
    def build(self):
        r = self.r
        top = r.h - 1
        for c in self.cols:
            x, z = c
            fy = self.fy(c)
            for y in range(0, fy):
                r.put(x, y, z, DEEPSLATE)
            if c not in self.open:
                for y in range(fy, top + 1):
                    r.put(x, y, z, self.wall_block(x, y, z, fy))
                continue
            r.put(x, fy, z, self.floor_block(x, z))
            sag = 0
            if self.cave:
                n = noise(self.seed + 7, x, z, 4)
                if c in self.keep:
                    sag = min(int(n * 2), top - (fy + DOOR_H + 2))
                else:
                    sag = min(int(n * (4 if self.dist[c] <= 5 else 2)), top - (fy + 6))
                sag = max(0, sag)
            self.ceil[c] = top - sag
            for y in range(top - sag, top + 1):
                r.put(x, y, z, self.ceiling_block(x, y, z))
        for (x, z), (y_top, facing) in self.stair.items():
            for y in range(self.fy((x, z)), y_top):
                r.put(x, y, z, BRICK)
            r.put(x, y_top, z, stairs(facing))
        for x, z in self.rails:
            if (x, z) in self.open:
                r.put(x, self.fy((x, z)) + 1, z, RAIL)
        r.open_doors()
        self.frame_doors()

    def wall_block(self, x, y, z, fy):
        rng = self.rng
        if self.cave:
            n = noise(self.seed + 3, x + y * 0.7, z - y * 0.5, 4, y // 3)
            if y <= fy + 1 and n < 0.55:
                return SNOW
            if n < 0.28:
                return COBBLED if rng.random() < 0.5 else DEEPSLATE
            if n < 0.62:
                return ICE
            if n < 0.8:
                return SNOW if y < fy + 4 else ICE
            if n < 0.95:
                return BLUE
            return CLEAR_ICE
        # the fortress: ribbed brick with ice veins
        along = x if (x, z - 1) in self.open or (x, z + 1) in self.open else z
        if y == fy + 1:
            return SNOW if rng.random() < 0.4 else POLISHED
        if y >= self.r.h - 2:
            return POLISHED
        rib = along % 4
        if rib == 0:
            return BEAM
        if rib == 2:
            return BLUE if y % 3 == 0 else ICE
        return BRICK_CRACKED if rng.random() < 0.1 else BRICK

    def floor_block(self, x, z):
        n = noise(self.seed + 11, x, z, 4)
        if self.cave:
            if n < 0.62:
                return SNOW
            if n < 0.8:
                return CALCITE
            if n < 0.9:
                return DIORITE
            return POLISHED
        if n < 0.5:
            return SNOW
        return TILE_CRACKED if self.rng.random() < 0.12 else TILE

    def ceiling_block(self, x, y, z):
        n = noise(self.seed + 5, x, z, 3, y)
        if self.cave:
            return ICE if n < 0.5 else (SNOW if n < 0.7 else (BLUE if n < 0.9 else DEEPSLATE))
        return TILE if n < 0.85 else ICE

    def frame_doors(self):
        r = self.r
        for cx, cz, side, floor in r.doors:
            for k in range(DOOR_START - 1, DOOR_START + DOOR_W + 1):
                x, z = edge_col(cx, cz, side, 0, k)
                r.put(x, floor, z, SNOW if self.cave else CHISELED)  # never ice underfoot
                for y in range(floor + 1, floor + DOOR_H + 2):
                    if k in (DOOR_START - 1, DOOR_START + DOOR_W) or y == floor + DOOR_H + 1:
                        x, z = edge_col(cx, cz, side, 0, k)
                        r.put(x, y, z, (BLUE if (k + y) % 2 else ICE) if self.cave else CHISELED)

    # ---- markers
    def flat_spots(self, margin=1):
        """Open columns with open, level, bare ground all round: room for a mob, a player or a piece."""
        out = []
        for c in self.open:
            if c in self.keep or c in self.taken:
                continue
            fy = self.fy(c)
            if self.ceil.get(c, 0) - fy < 5:
                continue
            if all((n := (c[0] + dx, c[1] + dz)) in self.open and n not in self.stair and n not in self.taken
                   and self.fy(n) == fy for dx in range(-margin, margin + 1) for dz in range(-margin, margin + 1)):
                out.append(c)
        out.sort()
        return out

    def near_centre(self, spots):
        if not spots:
            raise ValueError(f"{self.s.id}: no open spot in the middle")
        return min(spots, key=lambda c: (c[0] - self.r.w / 2) ** 2 + (c[1] - self.r.d / 2) ** 2)

    def place_markers(self):
        s, rng = self.s, self.rng
        if s.type not in ("combat", "elite", "boss"):
            return
        spots = self.flat_spots()
        n = s.spawns if s.spawns is not None else max(3, min(9, len(self.open) // 45))
        if s.type in ("elite", "boss"):
            best = self.near_centre(spots)
            self.mark("spawn", best, "boss" if s.type == "boss" else "elite",
                      facing="north" if s.type == "boss" else None)
        rng.shuffle(spots)
        chosen = []
        for c in spots:
            if len(chosen) >= n:
                break
            if all(max(abs(c[0] - o[0]), abs(c[1] - o[1])) >= 4 for o in chosen + self.marked):
                chosen.append(c)
        # ranged enemies take the high ground, then the spots farthest from the first doorway
        first = self.door_strip(*s.doors[0])[0]
        chosen.sort(key=lambda c: (-self.fy(c), -((c[0] - first[0]) ** 2 + (c[1] - first[1]) ** 2)))
        ranged = max(1, len(chosen) // 3)
        for i, c in enumerate(chosen):
            self.mark("spawn", c, "ranged" if i < ranged else "melee")

    def mark(self, kind, c, tag="", facing=None, rest=False):
        """A marker standing in column c (a rest marker names the block players wake on)."""
        fy = self.fy(c)
        self.r.marker(kind, c[0], fy if rest else fy + 1, c[1], tag=tag, facing=facing)
        self.marked.append(c)
        self.claim(c, 2)

    # ---- pieces
    def free(self, c, margin=1):
        if c not in self.open:
            return False
        return not any((n := (c[0] + dx, c[1] + dz)) in self.taken or n in self.keep or n in self.stair
                       for dx in range(-margin, margin + 1) for dz in range(-margin, margin + 1))

    def claim(self, c, margin=1):
        for dx in range(-margin, margin + 1):
            for dz in range(-margin, margin + 1):
                self.taken.add((c[0] + dx, c[1] + dz))

    def spike(self, c, height):
        """An ice spike: a packed ice column with a blue tip and a wider foot."""
        r, (x, z) = self.r, c
        fy = self.fy(c)
        height = min(height, self.ceil[c] - fy - 2)
        if height < 2:
            return
        for y in range(fy + 1, fy + 1 + height):
            r.put(x, y, z, BLUE if y == fy + height else ICE)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, z + dz) in self.open and self.fy((x + dx, z + dz)) == fy:
                for y in range(fy + 1, fy + 1 + max(1, height // 3)):
                    r.put(x + dx, y, z + dz, ICE)
        self.claim(c)

    def glow_pillar(self, c):
        """A pillar of clear ice from floor to ceiling round a core of blue ice and sea lanterns."""
        r, (x, z) = self.r, c
        fy = self.fy(c)
        for y in range(fy + 1, self.ceil[c]):
            r.put(x, y, z, SEA if (y - fy) % 3 == 2 else BLUE)
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                r.put(x + dx, y, z + dz, CLEAR_ICE if (y + dx + dz) % 4 else ICE)
        self.claim(c, 2)

    def pillar(self, c):
        """A 2x2 fortress pillar with ice bands, floor to ceiling."""
        r, (x, z) = self.r, c
        fy = self.fy(c)
        top = min(self.ceil.get((x + dx, z + dz), r.h - 1) for dx in (0, 1) for dz in (0, 1)) - 1
        for y in range(fy + 1, top + 1):
            for dx in (0, 1):
                for dz in (0, 1):
                    if y in (fy + 1, top):
                        mat = CHISELED
                    elif (y - fy) % 4 == 0:
                        mat = BLUE if (dx + dz) % 2 else ICE
                    else:
                        mat = BRICK
                    r.put(x + dx, y, z + dz, mat)
        self.claim(c, 2)

    def icicle(self, c):
        r, (x, z) = self.r, c
        low = self.ceil[c]
        length = min(1 + int(self.rng.random() * 3), low - (self.fy(c) + 6))
        for k in range(1, length + 1):
            r.put(x, low - k, z, CLEAR_ICE if k == length else ICE)

    def hanging_lantern(self, c):
        r, (x, z) = self.r, c
        low = self.ceil[c]
        chain = min(2, low - (self.fy(c) + 7))
        if chain < 0:
            return
        for k in range(1, chain + 1):
            r.put(x, low - k, z, CHAIN)
        r.put(x, low - chain - 1, z, LANTERN)

    def drift(self, c):
        """A snow mound against a wall: a block of snow with deep layers round it."""
        r, (x, z) = self.r, c
        fy = self.fy(c)
        r.put(x, fy + 1, z, SNOW)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if n in self.open and n not in self.keep and n not in self.stair and self.fy(n) == fy \
                    and r.get(x + dx, fy + 1, z + dz) == AIR:
                r.put(x + dx, fy + 1, z + dz, layer(2))
        self.claim(c, 0)

    def crystals(self, c):
        """Blue ice and glass growing out of a wall."""
        r, (x, z) = self.r, c
        fy = self.fy(c)
        for y in range(fy + 1, fy + 2 + int(self.rng.random() * 3)):
            r.put(x, y, z, BLUE if self.rng.random() < 0.6 else GLASS)
        self.claim(c, 0)

    def remains(self, c):
        r, (x, z) = self.r, c
        r.put(x, self.fy(c) + 1, z, BONES if self.rng.random() < 0.5 else BARREL)
        self.claim(c, 0)

    def dress(self):
        s, r, rng = self.s, self.r, self.rng
        area = len(self.open)
        if s.dress:
            s.dress(self)
        by_wall = [c for c in sorted(self.open) if self.free(c, 0) and any(
            (c[0] + dx, c[1] + dz) not in self.open for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
        rng.shuffle(by_wall)
        if self.cave:
            for c in by_wall[: max(2, area // 25)]:
                if self.free(c, 0):
                    rng.choice((self.drift, self.drift, self.crystals, self.crystals, self.remains))(c)
            mids = self.flat_spots(2)
            rng.shuffle(mids)
            pillars = 0
            for c in mids:
                if pillars < area // 220 and self.free(c, 2) and self.ceil[c] - self.fy(c) >= 7:
                    self.glow_pillar(c)
                    pillars += 1
            spikes = 0
            for c in mids:
                if spikes < max(2, area // 60) and self.free(c, 1):
                    self.spike(c, 2 + int(rng.random() * 5))
                    spikes += 1
        else:
            for c in by_wall[: max(2, area // 40)]:
                if self.free(c, 0):
                    rng.choice((self.drift, self.remains, self.crystals))(c)
            if min(r.w, r.d) >= 2 * CELL:
                for c in self.flat_spots(2):
                    lx, lz = c[0] % CELL, c[1] % CELL
                    corners = ((4, 4), (10, 10)) if (c[0] // CELL + c[1] // CELL) % 2 == 0 else ((10, 4), (4, 10))
                    if (lx, lz) in corners and self.free(c, 2):
                        self.pillar(c)
            mids = self.flat_spots(1)
            rng.shuffle(mids)
            for c in mids[: max(1, area // 110)]:
                if self.free(c, 1):
                    self.spike(c, 2 + int(rng.random() * 3))
            for c in sorted(self.open):     # beams across the ceiling
                x, z = c
                if self.ceil[c] - self.fy(c) >= 8 and r.get(x, self.ceil[c] - 1, z) == AIR:
                    if z % 6 == 3 and r.d >= r.w:
                        r.put(x, self.ceil[c] - 1, z, BEAM_X)
                    elif x % 6 == 3 and r.w > r.d:
                        r.put(x, self.ceil[c] - 1, z, BEAM_Z)
        for c in sorted(self.open):
            x, z = c
            fy = self.fy(c)
            if c in self.stair:
                continue
            room = self.ceil[c] - fy
            # icicles and lanterns from the ceiling
            if room >= 8 and r.get(x, self.ceil[c] - 1, z) == AIR:
                if self.cave and rng.random() < 0.06:
                    self.icicle(c)
                elif not self.cave and x % 7 == 3 and z % 7 == 3 and self.dist[c] <= 4:
                    self.hanging_lantern(c)
            # hidden light, so the rooms are never pitch dark
            if x % 5 == 2 and z % 5 == 2 and r.get(x, fy + 3, z) == AIR:
                r.put(x, fy + 3, z, LIGHT)
            # snow on the floor: a thin cover, deeper by the walls
            if r.get(x, fy + 1, z) == AIR and r.get(x, fy, z) in FLOORS:
                n = noise(self.seed + 17, x, z, 3)
                near_wall = self.dist[c] <= 3
                if n > 0.5 or (near_wall and n > 0.25):
                    r.put(x, fy + 1, z, layer(2 if n > 0.75 or near_wall else 1))
            # light in the ceiling
            if not self.cave and (x + z) % 9 == 0 and self.ceil[c] == r.h - 1 and room >= 7:
                r.put(x, r.h - 1, z, SEA)
            elif self.cave and rng.random() < 0.03 and room >= 6:
                r.put(x, self.ceil[c], z, SEA)

    def run(self):
        s, r = self.s, self.r
        for cx, cz, side in s.doors:
            r.door(cx, cz, side, s.rises.get((cx, cz), 0))
        self.shape()
        self.build()
        self.place_markers()
        self.dress()
        self.seal()
        return r

    def seal(self):
        """Clear ice, glass and anything else see-through on the outer shell turns solid: never a view
        of the void."""
        r = self.r
        doors = door_openings(r)
        for p in shell(r):
            if p not in doors and see_through(r.get(*p)):
                r.put(*p, BLUE if self.cave else BRICK)


def make(spec, tries=80):
    """The room from the first seed that passes the walk check."""
    last = None
    for seed in range(tries):
        try:
            room = Gen(spec, seed).run()
        except ValueError as e:  # no spot for a piece the room needs
            last = [str(e)]
            continue
        last = walk_check(room) + shell_check(room)
        spawns = sum(1 for m in room.markers if m[0] == "spawn")
        if not last and (spec.type not in ("combat", "elite", "boss") or spawns >= 3):
            return room
    sys.exit(f"{spec.id}: no seed passed the walk check: {last}")


# ---------------------------------------------------------------------------- set pieces

def hearth(g):
    """A soul fire with spruce benches; players wake beside it."""
    c = g.near_centre(g.flat_spots(3))
    x, z = c
    fy = g.fy(c)
    g.r.put(x, fy + 1, z, HEARTH)
    for dx, dz in ((-2, 0), (2, 0), (0, -2)):
        g.r.put(x + dx, fy + 1, z + dz, BENCH)
    g.mark("rest", (x, z + 2), rest=True)
    g.claim(c, 2)


def cache(g):
    """The chest at the far end from the doorway, in a ring of ice spikes."""
    cx, cz, side = g.s.doors[0]
    first = g.door_strip(cx, cz, side)[0]
    spots = g.flat_spots(2)
    if not spots:
        raise ValueError(f"{g.s.id}: no spot for the chest")
    c = max(spots, key=lambda c: (c[0] - first[0]) ** 2 + (c[1] - first[1]) ** 2)
    g.mark("chest", c, facing=side)
    for dx, dz in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        n = (c[0] + dx, c[1] + dz)
        if n in g.open and n not in g.keep and n not in g.stair:
            g.spike(n, 3)


def vault(g):
    cache(g)
    cx, cz, side = g.s.doors[0]
    strip = g.door_strip(cx, cz, side, depth=3, wide=0)
    g.mark("exit", strip[len(strip) // 2], facing=side)


def gate(g):
    """The way in: the entry point in the middle with braziers round it."""
    c = g.near_centre(g.flat_spots(3))
    g.mark("entry", c, facing="north")
    for dx, dz in ((-3, -3), (3, -3), (-3, 3), (3, 3)):
        n = (c[0] + dx, c[1] + dz)
        g.r.put(n[0], g.fy(n) + 1, n[1], HEARTH)


def throne(g):
    """A dais of polished stone and snow under the boss, braziers at its corners."""
    boss = next(m for m in g.r.markers if m[1] == "boss")
    x, fy, z = boss[2], boss[3] - 1, boss[4]
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            n = (x + dx, z + dz)
            if n in g.open and n not in g.stair and g.fy(n) == fy and g.r.get(n[0], fy + 1, n[1]) in (
                    AIR, layer(1), layer(2), LIGHT):
                ring = max(abs(dx), abs(dz))
                g.r.put(n[0], fy, n[1], CHISELED if ring == 4 else (POLISHED if ring % 2 else SNOW))
                if g.r.get(n[0], fy + 1, n[1]) != LIGHT:
                    g.r.clear(n[0], fy + 1, n[1])
    for dx, dz in ((-6, -6), (6, -6), (-6, 6), (6, 6)):
        n = (x + dx, z + dz)
        if g.free(n, 0) and g.fy(n) == fy:
            g.r.put(n[0], fy + 1, n[1], HEARTH)
            g.claim(n, 0)


# ---------------------------------------------------------------------------- the rooms

N, E, S, W = "north", "east", "south", "west"


def specs():
    out = []

    def add(*a, **k):
        out.append(Spec(*a, **k))

    # ---- the way in
    add("seithr_gate", "start", 2, 2, 12, [(0, 0, N), (1, 0, E), (1, 1, S), (0, 1, W)], style="hall", dress=gate)
    add("frozen_landing", "start", 1, 2, 10, [(0, 0, N), (0, 1, E), (0, 1, S)], dress=gate)

    # ---- corridors: narrow halls and ice tunnels
    for rid, style, doors in (("ice_passage", "cave", [(0, 0, N), (0, 0, S)]),
                              ("ice_bend", "cave", [(0, 0, N), (0, 0, E)]),
                              ("ice_fork", "cave", [(0, 0, N), (0, 0, E), (0, 0, W)]),
                              ("frost_corridor", "hall", [(0, 0, N), (0, 0, S)]),
                              ("frost_bend", "hall", [(0, 0, N), (0, 0, E)]),
                              ("frost_crossing", "hall", [(0, 0, N), (0, 0, E), (0, 0, S), (0, 0, W)])):
        add(rid, "corridor", 1, 1, 8 if style == "cave" else 7, doors, style=style, weight=2,
            inset=1 if style == "cave" else 3)
    add("long_passage", "corridor", 1, 2, 9, [(0, 0, N), (0, 1, S)], inset=1)
    add("glacier_stair", "corridor", 1, 2, 12, [(0, 0, N), (0, 1, S)], style="hall", rises={(0, 1): 4}, inset=2)
    add("ice_ramp", "corridor", 1, 2, 11, [(0, 0, N), (0, 1, S), (0, 1, E)], rises={(0, 1): 3}, inset=1)

    # ---- fights, small to large
    add("guard_post", "combat", 1, 1, 8, [(0, 0, N), (0, 0, S), (0, 0, E)], style="hall", threat=0.5, spawns=3)
    add("ice_hollow", "combat", 1, 1, 9, [(0, 0, N), (0, 0, S)], threat=0.5, spawns=3)
    add("barracks", "combat", 2, 1, 10, [(0, 0, W), (1, 0, E), (1, 0, N)], style="hall", threat=0.75)
    add("frozen_gallery", "combat", 2, 1, 10, [(0, 0, W), (1, 0, E), (0, 0, S)], threat=0.75)
    add("long_hall", "combat", 1, 3, 10, [(0, 0, N), (0, 2, S), (0, 1, E)], style="hall", threat=0.9)
    add("ice_rift", "combat", 3, 1, 11, [(0, 0, W), (2, 0, E), (1, 0, N)], threat=0.9)
    add("ice_cavern", "combat", 2, 2, 12, [(0, 0, N), (1, 0, E), (0, 1, S)], cells={(0, 0), (1, 0), (0, 1)},
        threat=0.85)
    add("broken_hall", "combat", 2, 2, 11, [(0, 0, W), (1, 0, N), (1, 1, S)], style="hall",
        cells={(0, 0), (1, 0), (1, 1)}, threat=0.85)
    add("barracks_hall", "combat", 2, 2, 11, [(0, 0, N), (1, 0, E), (1, 1, S), (0, 1, W)], style="hall")
    add("frost_terrace", "combat", 2, 2, 13, [(0, 0, W), (1, 1, E), (1, 0, N)], rises={(1, 0): 3, (1, 1): 3})
    add("ice_t", "combat", 3, 2, 12, [(0, 0, W), (2, 0, E), (1, 1, S)], cells={(0, 0), (1, 0), (2, 0), (1, 1)})
    add("ice_u", "combat", 3, 2, 12, [(0, 0, N), (2, 0, N), (2, 1, E)],
        cells={(0, 0), (2, 0), (0, 1), (1, 1), (2, 1)}, threat=1.1)
    add("frost_steps", "combat", 3, 2, 14, [(0, 0, W), (2, 1, E), (1, 0, N)],
        rises={(1, 0): 2, (1, 1): 2, (2, 0): 4, (2, 1): 4}, threat=1.1)
    add("frozen_pit", "combat", 3, 3, 14, [(1, 0, N), (0, 1, W), (2, 1, E), (1, 2, S)],
        cells={(1, 0), (0, 1), (1, 1), (2, 1), (1, 2)},
        rises={(1, 0): 3, (0, 1): 3, (2, 1): 3, (1, 2): 3}, threat=1.2)
    add("great_hall", "combat", 3, 2, 13, [(0, 0, N), (2, 0, E), (1, 1, S), (0, 1, W)], style="hall",
        threat=1.25)

    # ---- elite rooms
    add("captains_court", "elite", 2, 2, 13, [(0, 0, N), (1, 0, E), (1, 1, S), (0, 1, W)], style="hall")
    add("frost_sanctum", "elite", 2, 2, 13, [(0, 0, W), (1, 1, E), (1, 0, N)], rises={(1, 0): 3, (1, 1): 3})
    add("glacier_court", "elite", 3, 2, 14, [(0, 0, W), (2, 1, E), (1, 0, N)], threat=1.2)

    # ---- rest, treasure, the boss and the vault
    add("frost_hearth", "rest", 1, 1, 8, [(0, 0, N), (0, 0, S)], style="hall", dress=hearth)
    add("snow_camp", "rest", 1, 2, 10, [(0, 0, N), (0, 1, S)], dress=hearth)
    add("frozen_cache", "treasure", 1, 1, 8, [(0, 0, S)], style="hall", dress=cache, inset=1)
    add("ice_grotto", "treasure", 1, 1, 9, [(0, 0, S)], dress=cache)
    add("frozen_throne", "boss", 3, 3, 20, [(1, 0, N), (1, 2, S)], style="hall", max_per_run=1, spawns=6,
        dress=throne)
    add("glacier_heart", "boss", 3, 3, 20, [(1, 0, N), (1, 2, S)], max_per_run=1, spawns=6, dress=throne)
    add("seithr_vault", "vault", 1, 1, 9, [(0, 0, S)], style="hall", dress=vault, inset=1)
    return out


def build():
    return [make(spec) for spec in specs()]


KIT_YML = """\
# Generated by tools/rooms/build_seithr.py.
cell-size: 16
cell-height: 24
max-rise: 16
door-height: 4
seal-block: minecraft:packed_ice
door-block: minecraft:iron_bars
# Enemies: the dungeon's own Seithr mobs (MythicMobs, Dungeon_Seithr_*, apart from Main's fortress ones).
# cost = share of a room's threat budget. Roles: grunt, bruiser, tank, ranged, caster, swarm, elite.
# Ranged and casters prefer 'ranged' spawn markers, the rest 'melee' ones. A room's own 'threat' scales
# its budget (small rooms less). tiers: the difficulties it appears on (1 Normal, 2 Hard, 3 Nightmare,
# 4 Mythic; "2+" from Hard up); harder difficulties bring other enemies, not only stronger ones. Each
# enemy's MythicMobs level is the difficulty's tier, so its skills can add moves as it rises.
# leader: a lower difficulty's boss leading an elite room (its attack areas are marked on the ground).
roster:
  miner: {mob: Dungeon_Seithr_Miner, role: swarm, cost: 0.5, weight: 2, tiers: 1-2}
  thrall: {mob: Dungeon_Seithr_Thrall, role: swarm, cost: 0.6, weight: 2}
  hound: {mob: Dungeon_Seithr_Hound, role: swarm, cost: 0.75, weight: 2}
  spider: {mob: Dungeon_Seithr_Spider, role: swarm, cost: 0.75, weight: 1, tiers: 1-3}
  soldier: {mob: Dungeon_Seithr_Soldier, role: grunt, cost: 1.5, weight: 3}
  archer: {mob: Dungeon_Seithr_Archer, role: ranged, cost: 2, weight: 2}
  veteran: {mob: Dungeon_Seithr_Veteran, role: elite, cost: 3.5, tiers: 1-2}
  spearman: {mob: Dungeon_Seithr_Spearman, role: grunt, cost: 1.75, weight: 2, tiers: 2+}
  shieldbearer: {mob: Dungeon_Seithr_Shieldbearer, role: tank, cost: 2.5, weight: 2, tiers: 2+}
  marksman: {mob: Dungeon_Seithr_Marksman, role: ranged, cost: 2.5, tiers: 2+}
  druid: {mob: Dungeon_Seithr_Druid, role: caster, cost: 3, tiers: 2+}
  golem: {mob: Dungeon_Seithr_Golem, role: tank, cost: 3.5, tiers: 2+}
  captain: {mob: Dungeon_Seithr_Captain, role: elite, cost: 5, tiers: 2+}
  hatchling: {mob: Dungeon_Seithr_Hatchling, role: swarm, cost: 0.4, tiers: 3+}
  berserker: {mob: Dungeon_Seithr_Berserker, role: bruiser, cost: 3, weight: 2, tiers: 3+}
  bearer: {mob: Dungeon_Seithr_Standard_Bearer, role: caster, cost: 3, tiers: 3+}
  wisp: {mob: Dungeon_Seithr_Wisp, role: swarm, cost: 1, tiers: 3+}
  frost_mage: {mob: Dungeon_Seithr_Mage, role: elite, cost: 6, tiers: 3+, leader: true}
  golem_leader: {mob: Dungeon_Seithr_Ice_Golem, role: elite, cost: 6, tiers: 3+, leader: true}
  royal_guard: {mob: Dungeon_Seithr_Royal_Guard, role: bruiser, cost: 3.5, weight: 2, tiers: 4}
  frostcaller: {mob: Dungeon_Seithr_Frostcaller, role: caster, cost: 3.5, tiers: 4}
  brood_leader: {mob: Dungeon_Seithr_Brood_Mother, role: elite, cost: 7, tiers: 4, leader: true}
  executioner_leader: {mob: Dungeon_Seithr_Executioner, role: elite, cost: 8, tiers: 4, leader: true}
# Pack shapes, one role per enemy; min-depth = fight rooms cleared before it appears; tiers as above.
packs:
  patrol: {slots: [grunt, grunt, ranged, swarm], weight: 3}
  diggers: {slots: [swarm, swarm, swarm, grunt], weight: 2, tiers: 1-2}
  hunters: {slots: [swarm, swarm, grunt, ranged], weight: 2}
  shieldline: {slots: [tank, grunt, grunt, ranged], weight: 2, min-depth: 1, tiers: 2+}
  frostcall: {slots: [caster, tank, grunt, swarm], weight: 1, min-depth: 2, tiers: 2+}
  warband: {slots: [bruiser, caster, grunt, grunt], weight: 2, tiers: 3+}
  escort: {slots: [bruiser, bruiser, caster, ranged], weight: 2, tiers: 4}
# Bosses. Each difficulty draws its boss from those of its tiers, by the run's seed (a recipe's
# boss: fixes one). health = base max health before party scaling. Phases start at a health percent;
# adds = threat budget of the adds that come with it; every = seconds between mechanics.
# Mechanics: slam (marks the ground under a player), shockwave (around the boss), rally-call (adds).
# A boss's own attacks are its MythicMobs skills (dungeonattack); these come on top.
bosses:
  ice_golem:
    mob: Dungeon_Seithr_Ice_Golem
    name: The Ice Golem
    tiers: 1
    health: 500
    enrage-seconds: 600
    phases:
      - {at: 100, adds: 0, mechanics: [slam], every: 12}
      - {at: 66, adds: 5, mechanics: [slam, shockwave], every: 10}
      - {at: 33, adds: 6, mechanics: [shockwave, slam, rally-call], every: 9}
  garrison_captain:
    mob: Dungeon_Seithr_Captain_Boss
    name: The Garrison Captain
    tiers: 1-2
    health: 550
    enrage-seconds: 600
    phases:
      - {at: 100, adds: 3, mechanics: [rally-call], every: 14}
      - {at: 50, adds: 5, mechanics: [slam, rally-call], every: 11}
  brood_mother:
    mob: Dungeon_Seithr_Brood_Mother
    name: The Brood Mother
    tiers: 2-3
    health: 700
    enrage-seconds: 600
    phases:
      - {at: 100, adds: 0, mechanics: [slam], every: 12}
      - {at: 60, adds: 4, mechanics: [slam, shockwave], every: 10}
      - {at: 30, adds: 6, mechanics: [shockwave, rally-call], every: 9}
  executioner:
    mob: Dungeon_Seithr_Executioner
    name: The Executioner
    tiers: 3
    health: 900
    enrage-seconds: 540
    phases:
      - {at: 100, adds: 0, mechanics: [slam], every: 11}
      - {at: 50, adds: 5, mechanics: [slam, shockwave], every: 9}
  frost_mage:
    mob: Dungeon_Seithr_Mage_Boss
    name: The Frost Mage
    tiers: 3
    health: 800
    enrage-seconds: 540
    phases:
      - {at: 100, adds: 0, mechanics: [shockwave], every: 12}
      - {at: 55, adds: 6, mechanics: [slam, rally-call], every: 10}
  vaaramereios:
    mob: Dungeon_Seithr_Vaaramereios
    name: General Vaaramereios
    tiers: 4
    health: 1600
    enrage-seconds: 600
    phases:
      - {at: 100, adds: 0, mechanics: [slam], every: 11}
      - {at: 70, adds: 7, mechanics: [slam, rally-call], every: 10}
      - {at: 40, adds: 8, mechanics: [shockwave, slam, rally-call], every: 8}
# Room events: some plain fight rooms get one (the recipe's event-chance and max-events).
# kindle: cold braziers stand on spawn points; enemies keep coming until all are lit (a player stays
#   beside one for light-seconds). endure: the doors stay shut for seconds while enemies keep coming.
# runes: stones light in an order (length, one more per tier above 1); players touch them in that
#   order; a mistake brings a group. every-seconds and budget: how often groups come and their
#   threat budget (scaled for the party); most-enemies: no group comes while this many stand.
events:
  kindle: {weight: 2, braziers: 3, light-seconds: 3}
  endure: {weight: 1, seconds: 60}
  runes: {weight: 2, stones: 4, length: 4}
  every-seconds: 15
  budget: 2
  most-enemies: 8
"""


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repo, "src", "main", "resources", "dungeons", "kits", KIT)
    rooms_dir = os.path.join(out, "rooms")
    os.makedirs(rooms_dir, exist_ok=True)
    rooms = build()
    for f in os.listdir(rooms_dir):  # rooms dropped from the kit must not linger
        if f.endswith((".yml", ".nbt")):
            os.remove(os.path.join(rooms_dir, f))
    ids = [r.write(rooms_dir) for r in rooms]
    with open(os.path.join(out, "kit.yml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(KIT_YML)
    files = ["kit.yml"] + [f"rooms/{i}.{ext}" for i in ids for ext in ("yml", "nbt")]
    with open(os.path.join(out, "files.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(files) + "\n")
    print(f"{len(ids)} rooms written to {out}")


if __name__ == "__main__":
    main()

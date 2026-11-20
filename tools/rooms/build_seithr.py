#!/usr/bin/env python3
"""Build the Seithr dungeon room kit (ice and dark stone): one structure (.nbt) and sidecar (.yml) per room.

Usage: python tools/rooms/build_seithr.py [output-dir]
Default output: src/main/resources/dungeons/kits/seithr
Needs nbtlib (pip install nbtlib). Kit rules: see roomlib.py.

Look: deepslate floors and walls with packed and blue ice veins, spruce beams, soul lanterns and
sea lanterns for cold light, ice spikes and snow drifts at the edges. Floors are never ice (players
would slide) and there is no powder snow (it traps players).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roomlib import CELL, DOOR_H, DOOR_W, Room, walk_check  # noqa: E402

KIT = "seithr"

TILE = "minecraft:deepslate_tiles"
TILE_CRACKED = "minecraft:cracked_deepslate_tiles"
POLISHED = "minecraft:polished_deepslate"
BRICK = "minecraft:deepslate_bricks"
BRICK_CRACKED = "minecraft:cracked_deepslate_bricks"
CHISELED = "minecraft:chiseled_deepslate"
DARK = "minecraft:polished_blackstone_bricks"
SNOW = "minecraft:snow_block"
DRIFT = "minecraft:snow[layers=1]"
ICE = "minecraft:packed_ice"
BLUE = "minecraft:blue_ice"
BEAM = "minecraft:stripped_spruce_log[axis=y]"
BEAM_X = "minecraft:stripped_spruce_log[axis=x]"
BEAM_Z = "minecraft:stripped_spruce_log[axis=z]"
LANTERN = "minecraft:soul_lantern[hanging=true]"
SEA = "minecraft:sea_lantern"
HEARTH = "minecraft:soul_campfire[lit=true]"
RAIL = "minecraft:deepslate_brick_wall"


def stairs(facing):
    return f"minecraft:deepslate_brick_stairs[facing={facing}]"


class SeithrRoom(Room):
    def __init__(self, rid, rtype, wc, dc, height, cells=None):
        super().__init__(rid, rtype, wc, dc, height, cells, kit=KIT)

    def shell(self):
        """Floor with a polished border, ribbed walls with ice veins and beams, a lit ceiling."""
        w, d, h = self.w, self.d, self.h
        for x in range(w):
            for z in range(d):
                lx, lz = x % CELL, z % CELL
                self.put(x, 0, z, TILE_CRACKED if self.rng.random() < 0.12 else TILE)
                self.put(x, h - 1, z, TILE)
                if (lx % 8 == 4) and (lz % 8 == 4):
                    self.put(x, h - 1, z, SEA)
        for cx, cz, side in self.outer_edges():
            for k, (x, z) in enumerate(self.edge_blocks(cx, cz, side)):
                rib = k % 8
                for y in range(1, h - 1):
                    if y == 1 or y == h - 2:
                        mat = POLISHED
                    elif rib == 0:
                        mat = BEAM
                    elif rib == 4:
                        mat = BLUE if y % 3 == 0 else ICE
                    else:
                        mat = BRICK_CRACKED if self.rng.random() < 0.1 else BRICK
                    self.put(x, y, z, mat)
                # a polished border on the floor, with snow drifting against the wall
                ix, iz = self.inward(cx, cz, side, x, z, 1)
                self.put(ix, 0, iz, POLISHED)
                if self.rng.random() < 0.35:
                    self.put(ix, 1, iz, DRIFT)
                # hanging soul lanterns along the walls
                if rib == 4:
                    lx, lz = self.inward(cx, cz, side, x, z, 2)
                    self.put(lx, h - 2, lz, LANTERN)
        self.open_doors()
        self.frame_doors()

    def inward(self, cx, cz, side, x, z, n):
        return {"north": (x, z + n), "south": (x, z - n), "west": (x + n, z), "east": (x - n, z)}[side]

    def frame_doors(self):
        """A chiseled frame around each doorway and a clear landing in front of it."""
        start = (CELL - DOOR_W) // 2
        for cx, cz, side, floor in self.doors:
            bx, bz = cx * CELL, cz * CELL
            for k in range(start - 1, start + DOOR_W + 1):
                for y in range(floor + 1, floor + DOOR_H + 2):
                    edge = k in (start - 1, start + DOOR_W) or y == floor + DOOR_H + 1
                    if not edge:
                        continue
                    if side == "north":
                        self.put(bx + k, y, bz, CHISELED)
                    elif side == "south":
                        self.put(bx + k, y, bz + CELL - 1, CHISELED)
                    elif side == "west":
                        self.put(bx, y, bz + k, CHISELED)
                    else:
                        self.put(bx + CELL - 1, y, bz + k, CHISELED)
            # no snow drift on the landing
            (x, y, z), (dx, dz) = self.landing(cx, cz, side, floor)
            for n in range(6):
                for s in range(-3, 3):
                    px = x + dx * n + (s if dx == 0 else 0)
                    pz = z + dz * n + (s if dz == 0 else 0)
                    if self.get(px, y, pz) == DRIFT:
                        self.clear(px, y, pz)

    # ---- pieces ----
    def pillar(self, x, z, top=None):
        top = self.h - 2 if top is None else top
        for y in range(1, top + 1):
            for dx in (0, 1):
                for dz in (0, 1):
                    if y == 1 or y == top:
                        mat = CHISELED
                    elif (dx + dz) % 2 == 0 and y % 4 == 2:
                        mat = ICE
                    else:
                        mat = BRICK
                    self.put(x + dx, y, z + dz, mat)

    def spike(self, x, z, height, base=1):
        """An ice spike: a packed ice column with a blue tip, a wider foot."""
        for y in range(base, base + height):
            self.put(x, y, z, BLUE if y == base + height - 1 else ICE)
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            for y in range(base, base + max(1, height // 3)):
                self.put(x + dx, y, z + dz, ICE)

    def brazier(self, x, z, y=1):
        self.put(x, y, z, HEARTH)

    def floor_inlay(self, x0, z0, x1, z1):
        """A snow and polished inlay on the floor (walkable, not slippery)."""
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                ring = x in (x0, x1) or z in (z0, z1)
                self.put(x, 0, z, POLISHED if ring else SNOW)

    def solid(self, x0, z0, x1, z1, top, mat=BRICK):
        self.fill(x0, 1, z0, x1, top, z1, mat)


def all_doors(room):
    for side in ("north", "east", "south", "west"):
        room.door(0, 0, side)


def build():
    rooms = []

    # ---- start: the gate hall ----
    r = SeithrRoom("seithr_gate", "start", 1, 1, 14)
    all_doors(r)
    r.shell()
    r.floor_inlay(11, 11, 20, 20)
    for x, z in ((6, 6), (24, 6), (6, 24), (24, 24)):
        r.pillar(x, z)
    for x, z in ((3, 3), (28, 3), (3, 28), (28, 28)):
        r.spike(x, z, 5)
    r.marker("entry", 16, 1, 16, facing="north")
    rooms.append(r)

    # ---- corridors ----
    r = SeithrRoom("frost_corridor", "corridor", 1, 1, 10)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.shell()
    r.solid(1, 1, 11, 30, 8)
    r.solid(20, 1, 30, 30, 8)
    for z in range(2, 30, 6):           # spruce ribs over an 8-wide hall
        for x in range(12, 20):
            r.put(x, 8, z, BEAM_X)
        r.put(12, 1, z, BEAM)
        r.put(19, 1, z, BEAM)
    r.floor_inlay(13, 1, 18, 30)
    rooms.append(r)

    r = SeithrRoom("frost_bend", "corridor", 1, 1, 10)
    r.door(0, 0, "north")
    r.door(0, 0, "east")
    r.shell()
    r.solid(1, 1, 11, 30, 8)
    r.solid(12, 20, 30, 30, 8)
    r.solid(20, 1, 30, 11, 8)
    rooms.append(r)

    r = SeithrRoom("glacier_stair", "corridor", 1, 1, 16)
    r.door(0, 0, "north", floor=0)
    r.door(0, 0, "south", floor=6)
    r.shell()
    r.solid(1, 1, 11, 30, 14)
    r.solid(20, 1, 30, 30, 14)
    for k in range(1, 7):               # six steps up between flat landings
        z = 10 + k
        if k > 1:
            r.fill(12, 1, z, 19, k - 1, z, BRICK)
        r.fill(12, k, z, 19, k, z, stairs("south"))
    r.fill(12, 1, 17, 19, 6, 30, TILE)
    for z in (20, 26):                  # wall lanterns on the upper landing
        r.put(12, 9, z, BEAM)
        r.put(19, 9, z, BEAM)
    r.open_doors()
    r.frame_doors()
    rooms.append(r)

    # ---- fights ----
    r = SeithrRoom("barracks_hall", "combat", 1, 1, 12)
    all_doors(r)
    r.shell()
    for x, z in ((8, 8), (22, 8), (8, 22), (22, 22)):
        r.pillar(x, z)
    r.floor_inlay(13, 13, 18, 18)
    for x, z in ((3, 10), (28, 21)):
        r.spike(x, z, 4)
    for x, z in ((6, 16), (26, 16), (16, 6), (16, 26)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 12, 1, 12, tag="ranged")
    r.marker("spawn", 20, 1, 20, tag="ranged")
    rooms.append(r)

    # an icy cavern in an L, its notch walled off by the room's own walls
    r = SeithrRoom("ice_cavern", "combat", 2, 2, 14, cells={(0, 0), (1, 0), (0, 1)})
    r.door(0, 0, "north")
    r.door(1, 0, "east")
    r.door(1, 0, "south")
    r.door(0, 1, "south")
    r.shell()
    for x, z, hgt in ((10, 10, 6), (40, 22, 7), (22, 40, 5), (52, 8, 4), (8, 52, 4), (26, 26, 8)):
        r.spike(x, z, hgt)
    r.floor_inlay(28, 6, 36, 14)
    for x, z in ((14, 18), (34, 20), (46, 14), (18, 34), (14, 48), (24, 56)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 56, 1, 24, tag="ranged")
    r.marker("spawn", 6, 1, 36, tag="ranged")
    rooms.append(r)

    # a frozen pit: doorways on a railed ledge 4 up, wide stairs down to the ice-ringed floor
    r = SeithrRoom("frozen_pit", "combat", 1, 1, 14)
    r.door(0, 0, "north", floor=4)
    r.door(0, 0, "south", floor=4)
    r.door(0, 0, "east", floor=4)
    r.shell()
    r.fill(1, 1, 1, 30, 4, 30, BRICK)
    r.fill(1, 4, 1, 30, 4, 30, TILE)
    r.fill(6, 1, 6, 25, 4, 25, None)
    for k in range(4):
        if k < 3:
            r.fill(12, 1, 6 + k, 19, 3 - k, 6 + k, BRICK)
        r.fill(12, 4 - k, 6 + k, 19, 4 - k, 6 + k, stairs("north"))
    for x in range(6, 26):
        for z in (5, 26):
            if not (12 <= x <= 19 and z == 5):
                r.put(x, 5, z, RAIL)
    for z in range(6, 26):
        r.put(5, 5, z, RAIL)
        r.put(26, 5, z, RAIL)
    for x, z in ((6, 25), (25, 25), (25, 6), (6, 6)):
        r.put(x, 1, z, BLUE)            # ice in the pit's corners, off the floor paths
    r.floor_inlay(12, 14, 19, 21)
    r.open_doors()
    r.frame_doors()
    for x, z in ((10, 14), (21, 14), (10, 21), (21, 21), (16, 18)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 3, 5, 16, tag="ranged")
    r.marker("spawn", 28, 5, 8, tag="ranged")
    rooms.append(r)

    r = SeithrRoom("long_hall", "combat", 1, 2, 12)
    r.door(0, 0, "north")
    r.door(0, 0, "east")
    r.door(0, 1, "west")
    r.door(0, 1, "south")
    r.shell()
    for z in (12, 24, 40, 52):          # a colonnade with spruce beams overhead
        r.pillar(8, z)
        r.pillar(22, z)
        for x in range(10, 22):
            r.put(x, 10, z, BEAM_X)
    r.floor_inlay(14, 6, 17, 58)
    for z in (10, 30, 50):
        r.marker("spawn", 12, 1, z, tag="melee")
        r.marker("spawn", 20, 1, z, tag="melee")
    r.marker("spawn", 16, 1, 32, tag="ranged")
    rooms.append(r)

    # ---- elite: the captain's court, a ring of pillars around an open centre ----
    r = SeithrRoom("captains_court", "elite", 1, 1, 14)
    all_doors(r)
    r.shell()
    for x, z in ((9, 9), (21, 9), (9, 21), (21, 21)):
        r.pillar(x, z)
    r.floor_inlay(12, 12, 19, 19)
    for x, z in ((4, 4), (27, 4), (4, 27), (27, 27)):
        r.brazier(x, z)
    r.marker("spawn", 16, 1, 16, tag="elite")
    for x, z in ((12, 12), (19, 12), (12, 19), (19, 19)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 6, 1, 16, tag="ranged")
    r.marker("spawn", 25, 1, 16, tag="ranged")
    rooms.append(r)

    # ---- rest: a hearth in a snowed-in hut ----
    r = SeithrRoom("frost_hearth", "rest", 1, 1, 10)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.shell()
    r.floor_inlay(12, 12, 19, 19)
    r.brazier(16, 16)
    for x, z in ((14, 16), (18, 16), (16, 14)):
        r.put(x, 1, z, "minecraft:spruce_slab[type=bottom]")  # benches round the hearth
    r.marker("rest", 16, 0, 19)
    rooms.append(r)

    # ---- treasure: a frozen cache at the end of a short passage ----
    r = SeithrRoom("frozen_cache", "treasure", 1, 1, 9)
    r.door(0, 0, "south")
    r.shell()
    r.solid(1, 1, 9, 30, 7)             # west of the passage
    r.solid(22, 1, 30, 30, 7)           # east of it
    r.solid(10, 1, 21, 9, 7)            # behind the cache
    r.spike(11, 11, 3)
    r.spike(20, 11, 3)
    r.marker("chest", 16, 1, 11, facing="south")
    rooms.append(r)

    # ---- boss: a tall hall of ice with a ring of pillars ----
    r = SeithrRoom("frozen_throne", "boss", 2, 2, 22)
    r.door(0, 0, "north")
    r.door(1, 1, "south")
    r.shell()
    for x, z in ((12, 12), (50, 12), (12, 50), (50, 50), (31, 8), (31, 54), (8, 31), (54, 31)):
        r.pillar(x, z)
    for x, z in ((4, 4), (59, 4), (4, 59), (59, 59)):
        r.spike(x, z, 9)
    r.floor_inlay(26, 26, 37, 37)
    r.marker("spawn", 32, 1, 32, tag="boss", facing="north")
    for x, z in ((20, 20), (44, 20), (20, 44), (44, 44)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 6, 1, 32, tag="ranged")
    r.marker("spawn", 57, 1, 32, tag="ranged")
    r.max_per_run = 1
    rooms.append(r)

    r = SeithrRoom("seithr_vault", "vault", 1, 1, 10)
    r.door(0, 0, "south")
    r.shell()
    r.floor_inlay(12, 6, 19, 14)
    r.marker("chest", 16, 1, 10, facing="south")
    r.marker("exit", 16, 1, 6, facing="south")
    rooms.append(r)
    return rooms


KIT_YML = """\
# Generated by tools/rooms/build_seithr.py.
cell-size: 32
cell-height: 24
door-height: 4
seal-block: minecraft:deepslate_bricks
door-block: minecraft:iron_bars
# Enemies: the Seithr Fortress mobs (MythicMobs). cost = share of a room's threat budget.
# Roles: grunt, bruiser, tank, ranged, caster, swarm, elite. Ranged and casters prefer 'ranged'
# spawn markers, the rest 'melee' ones.
roster:
  miner: {mob: Seithr_Miner, role: swarm, cost: 0.5, weight: 2}
  spider: {mob: Seithr_Spider, role: swarm, cost: 0.75, weight: 2}
  soldier: {mob: Seithr_Soldier, role: grunt, cost: 1.5, weight: 3}
  veteran: {mob: Elite_Seithr_Soldier, role: bruiser, cost: 2}
  archer: {mob: Seithr_Archer, role: ranged, cost: 2, weight: 2}
  marksman: {mob: Elite_Seithr_Archer, role: ranged, cost: 2.5}
  druid: {mob: Seithr_Druid, role: caster, cost: 3}
  golem: {mob: Seithr_Golem, role: tank, cost: 3.5}
  captain: {mob: Seithr_Captain, role: elite, cost: 5}
  mage: {mob: Seithr_Mage, role: elite, cost: 6}
# Pack shapes, one role per enemy; min-depth = fight rooms cleared before it appears.
packs:
  patrol: {slots: [grunt, grunt, ranged, swarm], weight: 3}
  diggers: {slots: [swarm, swarm, swarm, grunt], weight: 2}
  shieldline: {slots: [tank, grunt, swarm, swarm], weight: 2, min-depth: 1}
  frostcall: {slots: [caster, bruiser, grunt, swarm], weight: 1, min-depth: 2}
# Bosses. health = base max health before party scaling. Phases start at a health percent;
# adds = threat budget of the adds that come with it; every = seconds between mechanics.
# Mechanics: slam (marks the ground under a player), shockwave (around the boss), rally-call (adds).
# The Elite Ice Golem stands in until the dungeon's own boss mobs are made.
bosses:
  ice_golem:
    mob: Elite_Seithr_Golem
    name: The Elite Ice Golem
    health: 500
    enrage-seconds: 600
    phases:
      - {at: 100, adds: 0, mechanics: [slam], every: 12}
      - {at: 66, adds: 5, mechanics: [slam, shockwave], every: 10}
      - {at: 33, adds: 6, mechanics: [shockwave, slam, rally-call], every: 9}
"""


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repo, "src", "main", "resources", "dungeons", "kits", KIT)
    rooms_dir = os.path.join(out, "rooms")
    os.makedirs(rooms_dir, exist_ok=True)
    rooms = build()
    problems = [p for r in rooms for p in walk_check(r)]
    if problems:
        sys.exit("Walk check failed:\n" + "\n".join(problems))
    ids = [r.write(rooms_dir) for r in rooms]
    with open(os.path.join(out, "kit.yml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(KIT_YML)
    files = ["kit.yml"] + [f"rooms/{i}.{ext}" for i in ids for ext in ("yml", "nbt")]
    with open(os.path.join(out, "files.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(files) + "\n")
    print(f"{len(ids)} rooms written to {out}")


if __name__ == "__main__":
    main()

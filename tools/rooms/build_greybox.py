#!/usr/bin/env python3
"""Build the greybox dungeon room kit: one vanilla structure (.nbt) and one sidecar (.yml) per room.

Usage: python tools/rooms/build_greybox.py [output-dir]
Default output: src/main/resources/dungeons/kits/greybox
Needs nbtlib (pip install nbtlib).

Kit rules: see roomlib.py.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roomlib import CELL, DOOR_H, DOOR_W, Room as BaseRoom, walk_check  # noqa: E402

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
    "elite": "minecraft:orange_concrete",
    "rest": "minecraft:lime_concrete",
    "treasure": "minecraft:yellow_concrete",
    "boss": "minecraft:black_concrete",
    "vault": "minecraft:purple_concrete",
}

SIDES = {"north", "east", "south", "west"}


class Room(BaseRoom):
    """A greybox room: plain walls with one colour band per room type."""

    def __init__(self, rid, rtype, wc, dc, height, cells=None):
        super().__init__(rid, rtype, wc, dc, height, cells, kit=KIT)

    def shell(self):
        """Floor, outer walls with a colour band, ceiling with lights, and doorway openings."""
        w, d, h = self.w, self.d, self.h
        self.fill(0, 0, 0, w - 1, 0, d - 1, FLOOR)
        self.fill(0, h - 1, 0, w - 1, h - 1, d - 1, CEIL)
        band = BAND[self.type]
        for cx, cz, side in self.outer_edges():
            for y in range(1, h - 1):
                mat = band if y == 6 else WALL
                for x, z in self.edge_blocks(cx, cz, side):
                    self.put(x, y, z, mat)
        for x in range(4, w - 3, 6):
            for z in range(4, d - 3, 6):
                self.put(x, h - 1, z, LIGHT)
        self.open_doors()

    def pillar(self, x, z):
        self.fill(x, 1, z, x + 1, self.h - 2, z + 1, PILLAR)


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

    r = Room("elite_court", "elite", 1, 1, 12)
    all_doors(r)
    r.shell()
    # a ring of pillars around an open centre where the elite stands
    for x, z in ((9, 9), (22, 9), (9, 22), (22, 22), (16, 7), (16, 24), (7, 16), (24, 16)):
        r.pillar(x, z)
    r.marker("spawn", 16, 1, 16, tag="elite")
    for x, z in ((12, 12), (20, 12), (12, 20), (20, 20)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 4, 1, 4, tag="ranged")
    r.marker("spawn", 27, 1, 27, tag="ranged")
    rooms.append(r)

    r = Room("rest_camp", "rest", 1, 1, 10)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.shell()
    r.put(16, 1, 16, "minecraft:campfire")
    r.marker("rest", 16, 0, 19)  # the floor beside the fire: players wake standing on it, not in the fire
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
    # where the boss's adds come in
    for x, z in ((10, 34), (22, 34), (10, 44), (22, 44)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 4, 1, 40, tag="ranged")
    r.marker("spawn", 27, 1, 40, tag="ranged")
    r.max_per_run = 1
    rooms.append(r)

    # ---- shapes and heights ----
    # an L: the 2x2 box without its south-east cell; one doorway opens onto the notch
    r = Room("combat_ell", "combat", 2, 2, 12, cells={(0, 0), (1, 0), (0, 1)})
    r.door(0, 0, "north")
    r.door(1, 0, "east")
    r.door(1, 0, "south")
    r.door(0, 1, "south")
    r.shell()
    for x, z in ((14, 14), (46, 14), (14, 46)):
        r.pillar(x, z)
    for x, z in ((8, 8), (24, 24), (40, 10), (54, 22), (10, 40), (22, 54)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 30, 1, 8, tag="ranged")
    r.marker("spawn", 8, 1, 30, tag="ranged")
    rooms.append(r)

    # a corridor that climbs 6 blocks from its north doorway to its south one
    r = Room("corridor_ramp", "corridor", 1, 1, 16)
    r.door(0, 0, "north", floor=0)
    r.door(0, 0, "south", floor=6)
    r.shell()
    r.fill(1, 1, 1, 11, 14, 30, WALL)
    r.fill(20, 1, 1, 30, 14, 30, WALL)
    # a flat landing (z 1-10), six steps up (z 11-16), then the upper floor (z 17-30)
    for k in range(1, 7):
        z = 10 + k
        r.fill(12, 1, z, 19, k - 1, z, WALL) if k > 1 else None
        r.fill(12, k, z, 19, k, z, "minecraft:stone_brick_stairs[facing=south]")
    r.fill(12, 1, 17, 19, 6, 30, FLOOR)
    r.open_doors()
    rooms.append(r)

    # a sunken arena: its doorways open onto a ledge 4 blocks up, wide stairs lead down to the pit
    r = Room("combat_pit", "combat", 1, 1, 14)
    r.door(0, 0, "north", floor=4)
    r.door(0, 0, "south", floor=4)
    r.door(0, 0, "east", floor=4)
    r.shell()
    r.fill(1, 1, 1, 30, 4, 30, FLOOR)           # the ledge, 5 blocks wide all round
    r.fill(6, 1, 6, 25, 4, 25, None)            # the pit
    for k in range(4):                          # stairs down from the north ledge, 8 wide
        r.fill(12, 1, 6 + k, 19, 3 - k, 6 + k, FLOOR) if k < 3 else None
        r.fill(12, 4 - k, 6 + k, 19, 4 - k, 6 + k, "minecraft:stone_brick_stairs[facing=north]")
    for x in range(6, 26):                      # a rail along the ledge's edge, open at the stairs
        for z in (5, 26):
            if not (12 <= x <= 19 and z == 5):
                r.put(x, 5, z, "minecraft:stone_brick_wall")
    for z in range(6, 26):
        r.put(5, 5, z, "minecraft:stone_brick_wall")
        r.put(26, 5, z, "minecraft:stone_brick_wall")
    r.open_doors()
    for x, z in ((10, 14), (21, 14), (10, 21), (21, 21), (16, 18)):
        r.marker("spawn", x, 1, z, tag="melee")
    r.marker("spawn", 3, 5, 16, tag="ranged")
    r.marker("spawn", 28, 5, 8, tag="ranged")  # clear of the east doorway's landing
    rooms.append(r)

    r = Room("vault_exit", "vault", 1, 1, 10)
    r.door(0, 0, "south")
    r.shell()
    r.marker("chest", 16, 1, 10, facing="south")
    r.marker("exit", 16, 1, 6, facing="south")
    rooms.append(r)
    return rooms


FIGHTS = """\
# Enemies: the existing dungeon skeletons (MythicMobs). cost = share of a room's threat budget.
# Roles: grunt, bruiser, tank, ranged, caster, swarm, elite. Ranged and casters prefer 'ranged'
# spawn markers, the rest 'melee' ones.
roster:
  footman: {mob: dSkeleton_footman_em, role: grunt, cost: 1, weight: 3}
  swordman: {mob: dSkeleton_swordman_em, role: grunt, cost: 1.5, weight: 2}
  halberdier: {mob: dSkeleton_Halberdier_em, role: bruiser, cost: 2}
  warrior: {mob: dSkeleton_Warrior_em, role: tank, cost: 2.5}
  archer: {mob: dSkeleton_Archer_em, role: ranged, cost: 2.5}
  wizard: {mob: dSkeleton_Wizard_em, role: caster, cost: 3}
  tank: {mob: dSkeleton_Tank_em, role: elite, cost: 5}
# Pack shapes, one role per enemy; min-depth = fight rooms cleared before it appears.
packs:
  patrol: {slots: [grunt, grunt, grunt, ranged], weight: 3}
  shieldwall: {slots: [tank, grunt, grunt, ranged], weight: 2, min-depth: 1}
  warband: {slots: [bruiser, bruiser, grunt, caster], weight: 1, min-depth: 2}
# Bosses. health = base max health before party scaling. Phases start at a health percent;
# adds = threat budget of the adds that come with it; every = seconds between mechanics.
# Mechanics: slam (marks the ground under a player), shockwave (around the boss), rally-call (adds).
bosses:
  bone_warden:
    mob: dSkeleton_Tank_em
    name: The Bone Warden
    health: 400
    enrage-seconds: 600
    phases:
      - {at: 100, adds: 0, mechanics: [slam], every: 12}
      - {at: 66, adds: 5, mechanics: [slam, shockwave], every: 10}
      - {at: 33, adds: 6, mechanics: [shockwave, slam, rally-call], every: 9}
# Fight numbers (all optional; these are the defaults), for one player. Bigger parties get more
# budget and enemy health from their recipe (enemies-per-extra-player, health-per-extra-player).
fights:
  budget-combat: 6
  budget-elite: 8
  depth-step: 0.15
  tier-factors: [1.0, 1.35, 1.8]
  wave-split: 9
  third-wave: 16
  wave-warning-seconds: 5
  max-alive: 24
  elite-chance: 0.35
  elite-min-depth: 2
  elite-health: 2.0
  health-cap: 4.0
  slam-seconds: 3
  shockwave-seconds: 4
  burst-seconds: 3
  slam-damage: 0.35
  shockwave-damage: 0.45
  burst-damage: 0.30
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
        f.write("# Generated by tools/rooms/build_greybox.py.\n"
                f"cell-size: {CELL}\ncell-height: 24\ndoor-height: {DOOR_H}\nseal-block: {WALL}\n"
                "door-block: minecraft:iron_bars\n" + FIGHTS)
    files = ["kit.yml"] + [f"rooms/{i}.{ext}" for i in ids for ext in ("yml", "nbt")]
    with open(os.path.join(out, "files.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(files) + "\n")
    print(f"{len(ids)} rooms written to {out}")


if __name__ == "__main__":
    main()

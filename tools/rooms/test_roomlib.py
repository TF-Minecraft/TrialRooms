#!/usr/bin/env python3
"""Checks for the walk check in roomlib.py. Run: python tools/rooms/test_roomlib.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from roomlib import Room, walk_check  # noqa: E402

STONE = "minecraft:stone"


def corridor(height=10):
    """A plain 8-wide north-south hall, floor at y 0."""
    r = Room("t", "corridor", 1, 1, height)
    r.door(0, 0, "north")
    r.door(0, 0, "south")
    r.fill(0, 0, 0, 31, 0, 31, STONE)
    r.fill(0, height - 1, 0, 31, height - 1, 31, STONE)
    r.fill(0, 1, 0, 11, height - 2, 31, STONE)
    r.fill(20, 1, 0, 31, height - 2, 31, STONE)
    return r


def ramp(facing="south", half="bottom"):
    """A hall whose south doorway is 3 blocks up, reached by three stairs at z 12-14."""
    r = corridor(12)
    r.doors[1] = (0, 0, "south", 3)
    for k in range(1, 4):
        z = 11 + k
        if k > 1:
            r.fill(12, 1, z, 19, k - 1, z, STONE)
        r.fill(12, k, z, 19, k, z, f"minecraft:stone_brick_stairs[facing={facing},half={half}]")
    r.fill(12, 1, 15, 19, 3, 31, STONE)
    return r


def expect(name, room, fails, says=None):
    problems = walk_check(room)
    ok = bool(problems) == fails and (says is None or any(says in p for p in problems))
    print(("ok   " if ok else "FAIL ") + name + ("" if ok else f" -> {problems}"))
    return ok


def main():
    results = []
    results.append(expect("a plain hall passes", corridor(), False))
    results.append(expect("stairs climbed the way they face pass", ramp(), False))
    results.append(expect("stairs facing backwards need a jump", ramp(facing="north"), True))
    results.append(expect("upside-down stairs need a jump", ramp(half="top"), True))

    r = corridor()
    r.put(15, 1, 2, STONE)
    results.append(expect("a block on the landing beside the middle", r, True))
    r = corridor()
    r.fill(14, 1, 0, 17, 4, 0, STONE)
    results.append(expect("a bricked-up doorway", r, True))

    results.append(expect("a pit with no way out", raised(pit=True), True, "walked into but not out of"))
    results.append(expect("no room to drop under rock", raised(overhang=True), True, "marker at 13,1,18"))
    results.append(expect("the raised hall itself passes", raised(), False))

    r = corridor()
    r.fill(12, 1, 16, 19, 1, 16, "minecraft:snow[layers=8]")
    results.append(expect("a wall of deep snow", r, True))
    r = corridor()
    r.fill(12, 1, 16, 19, 1, 16, "minecraft:snow[layers=1]")
    results.append(expect("a thin snow drift is walked over", r, False))
    r = corridor()
    r.fill(12, 0, 16, 19, 0, 16, "minecraft:water")
    results.append(expect("a water channel across the hall", r, True))
    r = corridor()
    r.fill(12, 1, 16, 19, 1, 16, "minecraft:campfire")
    results.append(expect("a row of campfires across the hall", r, True))

    # stair halves only touch where they touch
    r = corridor(12)
    r.doors[1] = (0, 0, "south", 2)
    r.fill(12, 1, 14, 19, 1, 14, "minecraft:stone_brick_stairs[facing=north]")   # climbs north...
    r.fill(12, 1, 15, 19, 1, 15, "minecraft:stone_slab[type=bottom]")           # ...but the hall goes on south
    r.fill(12, 2, 15, 19, 2, 15, "minecraft:stone_slab[type=bottom]")
    r.fill(12, 1, 16, 19, 2, 31, STONE)
    results.append(expect("off a stair's back over its front step onto a higher slab", r, True))
    r = corridor(12)
    r.doors[1] = (0, 0, "south", 2)
    r.fill(12, 1, 14, 15, 1, 14, "minecraft:stone_brick_stairs[facing=south]")  # a flight on the west half,
    r.fill(16, 1, 14, 19, 1, 14, STONE)                                        # a block on the east,
    r.fill(16, 2, 14, 19, 2, 14, "minecraft:stone_brick_stairs[facing=south]")  # a higher flight beside it
    r.fill(12, 1, 15, 19, 2, 31, STONE)
    r.fill(12, 2, 14, 15, 2, 14, None)
    r.fill(12, 1, 15, 15, 2, 31, STONE)
    results.append(expect("from one flight's back sideways onto a higher flight's step", r, True,
                          "south doorway"))
    results.append(expect("stairs without a facing face north (a jump when climbed south)",
                          ramp_props("minecraft:stone_brick_stairs"), True))
    results.append(expect("slabs without a type are bottom slabs", ramp_props("minecraft:stone_slab"), False))
    r = corridor()
    r.fill(12, 0, 16, 19, 0, 16, "minecraft:stone_brick_wall")
    r.fill(12, 1, 16, 19, 1, 16, "minecraft:stone_brick_wall")
    results.append(expect("a wall row across the hall", r, True))
    r = corridor()
    r.put(16, 1, 20, "minecraft:stone_slab[type=bottom]")
    r.marker("spawn", 16, 1, 20)
    results.append(expect("a spawn point standing in a slab", r, False))
    r = corridor()
    r.fill(12, 0, 16, 19, 0, 16, "minecraft:lava")
    results.append(expect("a lava channel", r, True))
    r = corridor()
    r.put(16, 1, 20, "minecraft:campfire")
    r.marker("rest", 16, 1, 20)
    results.append(expect("a rest point on a campfire", r, True, "rest"))
    r = corridor()
    r.put(17, 1, 4, STONE)
    results.append(expect("a block on the landing's edge, 4 in", r, True, "landing"))
    results.append(expect("a 6-block drop with stairs back passes", drop(6), False))

    if not all(results):
        sys.exit("walk check tests failed")
    print("all walk check tests passed")


def ramp_props(block):
    """A hall climbing 1 block south over a single step block with no properties set."""
    r = corridor(12)
    r.doors[1] = (0, 0, "south", 1)
    r.fill(12, 1, 14, 19, 1, 14, block)
    r.fill(12, 1, 15, 19, 1, 31, STONE)
    return r


def drop(depth):
    """A ledge at y depth, a drop to the floor at y 0 midway, and stairs back up beside it."""
    r = Room("t", "corridor", 1, 1, depth + 8)
    r.door(0, 0, "north", depth)
    r.door(0, 0, "south", 0)
    r.fill(0, 0, 0, 31, 0, 31, STONE)
    r.fill(0, depth + 7, 0, 31, depth + 7, 31, STONE)
    r.fill(0, 1, 0, 11, depth + 6, 31, STONE)
    r.fill(20, 1, 0, 31, depth + 6, 31, STONE)
    r.fill(12, 1, 0, 19, depth, 9, STONE)                     # the ledge, z 0-9
    for k in range(depth):                                     # stairs back up along the east side, top by the ledge
        z, top = 10 + k, depth - k
        if top > 1:
            r.fill(18, 1, z, 19, top - 1, z, STONE)
        r.fill(18, top, z, 19, top, z, "minecraft:stone_brick_stairs[facing=north]")
    return r


def raised(pit=False, overhang=False):
    """A hall whose floor is 3 blocks up (y 3), with a pit or a slot under rock beside the path."""
    r = Room("t", "corridor", 1, 1, 12)
    r.door(0, 0, "north", 3)
    r.door(0, 0, "south", 3)
    r.fill(0, 0, 0, 31, 3, 31, STONE)
    r.fill(0, 11, 0, 31, 11, 31, STONE)
    r.fill(0, 4, 0, 11, 10, 31, STONE)
    r.fill(20, 4, 0, 31, 10, 31, STONE)
    if pit:  # 3 deep, beside the path, no stairs out
        r.fill(12, 1, 18, 15, 3, 22, None)
        r.marker("spawn", 13, 1, 20)
    if overhang:  # a slot whose roof is flush with the hall floor's edge: dropping in hits your head
        r.fill(12, 1, 18, 14, 3, 19, None)
        r.fill(12, 4, 18, 14, 8, 19, STONE)
        r.marker("spawn", 13, 1, 18)
    return r


if __name__ == "__main__":
    main()

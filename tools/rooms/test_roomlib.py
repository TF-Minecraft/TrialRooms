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

    if not all(results):
        sys.exit("walk check tests failed")
    print("all walk check tests passed")


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

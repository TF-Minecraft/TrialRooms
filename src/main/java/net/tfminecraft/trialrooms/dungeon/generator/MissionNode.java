package net.tfminecraft.trialrooms.dungeon.generator;

import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * One room in the abstract plan, before it has a template or a place.
 *
 * @param index  position in the plan; parents always come first
 * @param type   room type
 * @param parent index of the room it opens from, or -1 for the start room
 * @param depth  rooms walked from the start room (start = 0)
 * @param trunk  whether it is on the main path to the boss
 */
public record MissionNode(int index, RoomType type, int parent, int depth, boolean trunk) {
}

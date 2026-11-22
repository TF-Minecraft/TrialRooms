package net.tfminecraft.trialrooms.dungeon.locker;

import org.bukkit.inventory.ItemStack;

/** Turns items into bytes and back, keeping everything on them (the server's own item format). */
public interface ItemCodec {

    byte[] encode(ItemStack item);

    ItemStack decode(byte[] data);

    /** Paper's item bytes: every component, versioned, upgraded by the server on load. */
    ItemCodec PAPER = new ItemCodec() {
        @Override
        public byte[] encode(ItemStack item) {
            return item.serializeAsBytes();
        }

        @Override
        public ItemStack decode(byte[] data) {
            return ItemStack.deserializeBytes(data);
        }
    };
}

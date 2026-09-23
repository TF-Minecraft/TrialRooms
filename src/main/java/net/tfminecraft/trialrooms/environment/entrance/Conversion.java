package net.tfminecraft.trialrooms.environment.entrance;

import org.bukkit.inventory.ItemStack;

import me.Plugins.TLibs.TLibs;

public class Conversion {
    private final String item;
    private final double amount;

    /**
     * Parse lines like:
     *   "v.emerald 0.4"
     * Optional trailing comments starting with '#' are ignored.
     * If the amount is omitted, it defaults to 1.0.
     *
     * @throws IllegalArgumentException if the line is empty or the amount is invalid
     */
    public Conversion(String s) {

        String[] parts = s.split("\\s+");

        this.item = parts[0];

        if (parts.length >= 2) {
            try {
                this.amount = Double.parseDouble(parts[1]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid amount in conversion: " + parts[1]);
            }
        } else {
            this.amount = 1.0; // default if not provided
        }
    }

    public String getItem() {
        return item;
    }

    public double getAmount() {
        return amount;
    }

    public boolean match(ItemStack i) {
        return TLibs.getItemAPI().getChecker().checkItemWithPath(i, item);
    }

    @Override
    public String toString() {
        return item + " " + amount;
    }
}


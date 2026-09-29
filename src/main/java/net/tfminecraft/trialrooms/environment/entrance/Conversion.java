package net.tfminecraft.trialrooms.environment.entrance;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;

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

        if (s == null) throw new IllegalArgumentException("Conversion cannot be null");
        String line = s.split("#", 2)[0].trim();
        if (line.isEmpty()) throw new IllegalArgumentException("Conversion cannot be empty");
        String[] parts = line.split("\\s+");

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
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Conversion amount must be finite and nonnegative");
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

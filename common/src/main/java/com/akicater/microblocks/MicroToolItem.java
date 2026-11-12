package com.akicater.microblocks;

import net.minecraft.world.item.Item;

/**
 * The chisels and the hand drills: each takes away a different shape of the microblocks that it is used on (a click on a block with the tool in the hand, see
 * {@link MicroTools}). A chisel works on the spot that is looked at, a drill bores a hole in from the face that is looked at.
 */
public class MicroToolItem extends Item {
    /** What the tool takes away. */
    public enum Tool {
        FINE(250), FLAT(180), GOUGE(150), SPLITTER(120), DRILL_THIN(200), DRILL_WIDE(160);

        public final int durability;

        Tool(int durability) {
            this.durability = durability;
        }
    }

    public final Tool tool;

    public MicroToolItem(Tool tool, Properties properties) {
        super(properties);
        this.tool = tool;
    }
}

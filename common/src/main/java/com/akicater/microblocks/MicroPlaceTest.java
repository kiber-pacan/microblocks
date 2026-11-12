package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Used by the autotest: where parts land when an item is used on a block (snapping, stacking, turning), checked against the numbers. */
public final class MicroPlaceTest {
    private MicroPlaceTest() {}

    private static String box(Part p) {
        return p.minX + "," + p.minY + "," + p.minZ + "-" + p.maxX + "," + p.maxY + "," + p.maxZ;
    }

    private static void expect(List<String> errors, String what, MicroPlacement.Result r, BlockPos pos, String box) {
        if (r == null) { errors.add(what + ": nowhere (expected " + box + ")"); return; }
        if (!r.pos().equals(pos) || !box(r.part()).equals(box)) errors.add(what + ": " + r.pos() + " " + box(r.part()) + ", expected " + pos + " " + box);
    }

    public static String run(ServerLevel level, BlockPos near) {
        List<String> errors = new ArrayList<>();
        BlockPos at = near.offset(0, 3, -16);   // in the air, so that the spaces around it are free
        level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos above = at.above();
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Block m = Blocks.STONE_BRICKS;
        Vec3 top = new Vec3(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);

        // a plane 16 x 16 x 4 lies on the top of a block: the thin side against the surface
        int[] plane = MicroItemData.worldDims(new int[]{16, 16, 4}, 0, Direction.UP, Direction.EAST);
        expect(errors, "plane on the top", MicroPlacement.compute(level, at, Direction.UP, top, m, plane, false), above, "0,0,0-16,4,16");
        // under a block it hangs from the ceiling
        expect(errors, "plane under a block", MicroPlacement.compute(level, at, Direction.DOWN, new Vec3(top.x, at.getY(), top.z), m,
                MicroItemData.worldDims(new int[]{16, 16, 4}, 0, Direction.DOWN, Direction.EAST), false), at.below(), "0,12,0-16,16,16");
        // on a wall (the east face): thin along x
        expect(errors, "plane on a wall", MicroPlacement.compute(level, at, Direction.EAST, new Vec3(at.getX() + 1, at.getY() + 0.5, at.getZ() + 0.5), m,
                MicroItemData.worldDims(new int[]{16, 16, 4}, 0, Direction.EAST, Direction.EAST), false), at.east(), "0,0,0-4,16,16");

        // a stick 4 x 4 x 16: long along the way the player looks (east, then south), snapped to the middle of the block when the click is near it
        int[] stickEast = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.UP, Direction.EAST);
        expect(errors, "stick looking east", MicroPlacement.compute(level, at, Direction.UP, top, m, stickEast, false), above, "0,0,6-16,4,10");
        int[] stickSouth = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.UP, Direction.SOUTH);
        expect(errors, "stick looking south", MicroPlacement.compute(level, at, Direction.UP, top, m, stickSouth, false), above, "6,0,0-10,4,16");
        // snapping to an edge: a click near the east edge of the top puts the stick at that edge; sneaking follows the click to the sixteenth
        Vec3 nearEdge = new Vec3(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.9);
        expect(errors, "snap to the edge", MicroPlacement.compute(level, at, Direction.UP, nearEdge, m, stickEast, false), above, "0,0,12-16,4,16");
        expect(errors, "precise", MicroPlacement.compute(level, at, Direction.UP, nearEdge, m, stickEast, true), above, "0,0,12-16,4,16");
        Vec3 offCenter = new Vec3(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.3125);       // 5/16
        expect(errors, "precise follows the click", MicroPlacement.compute(level, at, Direction.UP, offCenter, m, stickEast, true), above, "0,0,3-16,4,7");
        // a cube 2 x 2 x 2, the smallest things
        expect(errors, "tiny cube", MicroPlacement.compute(level, at, Direction.UP, new Vec3(at.getX() + 0.97, at.getY() + 1, at.getZ() + 0.03), m, new int[]{2, 2, 2}, false), above, "14,0,0-16,2,2");

        // the automatic turn: a stick put near an edge of the top runs along that edge, whatever way the player looks
        int[] nearZEdge = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.UP, Direction.SOUTH, new double[]{0.5, 1, 0.9});
        if (nearZEdge[0] != 16 || nearZEdge[2] != 4) errors.add("a stick near the south edge did not run along it: " + nearZEdge[0] + "," + nearZEdge[1] + "," + nearZEdge[2]);
        int[] nearXEdge = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.UP, Direction.EAST, new double[]{0.1, 1, 0.5});
        if (nearXEdge[2] != 16 || nearXEdge[0] != 4) errors.add("a stick near the west edge did not run along it: " + nearXEdge[0] + "," + nearXEdge[1] + "," + nearXEdge[2]);
        int[] middle = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.UP, Direction.SOUTH, new double[]{0.5, 1, 0.5});
        if (middle[2] != 16) errors.add("a stick in the middle did not follow the view");
        int[] onWall = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.EAST, Direction.EAST, new double[]{1, 0.9, 0.5});
        if (onWall[2] != 16) errors.add("a stick near the top edge of a wall did not run along it (horizontally)");
        int[] onWallSide = MicroItemData.worldDims(new int[]{4, 4, 16}, 0, Direction.EAST, Direction.EAST, new double[]{1, 0.5, 0.95});
        if (onWallSide[1] != 16) errors.add("a stick near the side edge of a wall did not run up and down");

        // the six ways to lay (4, 8, 16) on the axes
        int[] r2 = MicroItemData.worldDims(new int[]{4, 8, 16}, 2, Direction.UP, Direction.NORTH);   // a to x, c to y, b to z
        if (r2[0] != 4 || r2[1] != 16 || r2[2] != 8) errors.add("rotation 2 gave " + r2[0] + "," + r2[1] + "," + r2[2]);

        // stacking on a face of a part inside a block space: a plane 8 thick, a cube on top of it
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Part slab = new Part(0, 0, 0, 16, 8, 16, Blocks.STONE);
        if (!MicroPlacement.place(level, new MicroPlacement.Result(above, slab))) errors.add("a plane could not be put on a block");
        Vec3 onSlab = new Vec3(above.getX() + 0.5, above.getY() + 0.5, above.getZ() + 0.5);
        expect(errors, "stacking on a part", MicroPlacement.compute(level, above, Direction.UP, onSlab, m, new int[]{4, 4, 4}, false), above, "6,8,6-10,12,10");
        // too thick for the space that is left: 12 on top of 8 does not fit
        if (MicroPlacement.compute(level, above, Direction.UP, onSlab, m, new int[]{4, 12, 4}, false) != null) errors.add("a box that does not fit into the space was placed");
        // the same place cannot be filled twice: a plane under the first one, where the first one is, is refused
        if (MicroPlacement.compute(level, above, Direction.UP, new Vec3(above.getX() + 0.5, above.getY(), above.getZ() + 0.5), m, new int[]{16, 4, 16}, false) != null)
            errors.add("a part was placed into the space of another part");
        // the border of a block space that holds a part: the next space gets the part
        expect(errors, "on the border of a part", MicroPlacement.compute(level, above, Direction.EAST, new Vec3(above.getX() + 1, above.getY() + 0.25, above.getZ() + 0.5), m, new int[]{4, 4, 4}, false),
                above.east(), "0,0,6-4,4,10");

        // Ctrl: a stick 16 x 4 x 4 clicked at x = 0.9 of the top of a block lies from 6/16 of this space to 6/16 of the next: two pieces
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        MicroPlacement.Result free = MicroPlacement.computeFree(level, at, Direction.UP, new Vec3(at.getX() + 0.9, at.getY() + 1, at.getZ() + 0.5), m, new int[]{16, 4, 4});
        if (free == null) errors.add("a free stick was refused");
        else if (free.more().size() != 1 || !free.pos().equals(above) || !box(free.part()).equals("6,0,6-16,4,10") || !box(free.more().get(0).part()).equals("0,0,6-6,4,10")
                || !free.more().get(0).pos().equals(above.east()))
            errors.add("a free stick across two spaces: " + free.pos() + " " + box(free.part()) + " + " + free.more());
        else if (!MicroPlacement.place(level, free) || !(level.getBlockEntity(above.east()) instanceof MicroBlockEntity)) errors.add("the pieces of a free stick were not placed");
        level.setBlock(above.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // the texture turns with the part: the turn that lays (4, 8, 16) as (4, 16, 8) is 2, and the item of such a part gives the sizes back in the frame of the block
        if (MicroItemData.orientation(new int[]{4, 8, 16}, new int[]{4, 16, 8}) != 2) errors.add("orientation of a turned box");
        Part turned = new Part(0, 0, 0, 4, 16, 8, Blocks.STONE, 2);
        int[] back = MicroItemData.dims(MicroItemData.make(turned));
        if (back[0] != 4 || back[1] != 8 || back[2] != 16 || MicroItemData.rotation(MicroItemData.make(turned)) != 2) errors.add("the item of a turned part: " + back[0] + "," + back[1] + "," + back[2]);

        // the mode that fills: a whole block put over a post fills only what is not the post, in pieces that belong together
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        MicroPlacement.place(level, new MicroPlacement.Result(above, new Part(6, 0, 6, 10, 16, 10, Blocks.STONE)));
        if (!MicroPlacement.place(level, new MicroPlacement.Result(above, new Part(0, 0, 0, 16, 16, 16, Blocks.OAK_PLANKS)), true, true)) errors.add("the fill was refused");
        else if (level.getBlockEntity(above) instanceof MicroBlockEntity filled) {
            int volume = 0, ids = 0;
            java.util.Set<Integer> groups = new java.util.HashSet<>();
            for (Part p : filled.parts()) {
                if (p.material != Blocks.OAK_PLANKS) continue;
                volume += p.volume();
                if (p.group == null || !p.group.fill()) errors.add("a piece of a fill without its group");
                else groups.add(p.group.id());
                ids++;
            }
            if (volume != 4096 - 256) errors.add("the fill took " + volume + " cubic sixteenths, not 3840");
            if (ids != 1 || groups.size() != 1) errors.add("a fill is one part: " + ids + " in " + groups.size() + " groups");
            for (Part p : filled.parts()) if (p.material == Blocks.OAK_PLANKS && p.mask == null) errors.add("a fill over a post has no holes");
        }
        // an ordinary block that can be cut: refused as it is, but where replacing is allowed it becomes a block of microblocks and the new part goes in
        level.setBlock(above, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        Vec3 inStone = new Vec3(above.getX() + 0.5, above.getY() + 1, above.getZ() + 0.5);
        if (MicroPlacement.compute(level, above, Direction.UP, inStone, Blocks.OAK_PLANKS, new int[]{4, 4, 4}, false, false) != null && MicroPlacement.compute(level, above.below(), Direction.UP, inStone, Blocks.OAK_PLANKS, new int[]{4, 4, 4}, false, false) == null) { }
        MicroPlacement.Result inside = MicroPlacement.compute(level, above.above(), Direction.DOWN, new Vec3(above.getX() + 0.5, above.getY() + 1, above.getZ() + 0.5), Blocks.OAK_PLANKS, new int[]{4, 4, 4}, false, true);
        if (inside == null) errors.add("a part could not be put into an ordinary block where replacing is allowed");
        else {
            if (!MicroPlacement.place(level, inside, true)) errors.add("the part was not put into the ordinary block");
            else if (!(level.getBlockEntity(inside.pos()) instanceof MicroBlockEntity converted) || converted.parts().size() != 2 || converted.parts().get(0).material != Blocks.STONE) errors.add("the ordinary block did not become a block of microblocks with the whole block as a part");
        }
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(above.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        // a prefab as a statue: put across two block spaces, it is made whole again from its pieces
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        java.util.List<Part> prefab = java.util.List.of(new Part(0, 0, 0, 16, 4, 16, Blocks.STONE_BRICKS), new Part(7, 4, 7, 9, 16, 9, Blocks.OAK_PLANKS));
        MicroSettings.State freeState = new MicroSettings.State();
        MicroStatue.Plan plan = MicroStatue.plan(level, at, Direction.UP, new Vec3(at.getX() + 0.9, at.getY() + 1, at.getZ() + 0.5), prefab, freeState);
        if (plan == null) errors.add("a statue was refused");
        else {
            if (plan.pieces().size() < 3) errors.add("a statue across two spaces has " + plan.pieces().size() + " pieces");
            if (!MicroStatue.place(level, plan, false)) errors.add("the pieces of a statue were not placed");
            else {
                Part.Group g = plan.pieces().get(0).part().group;
                java.util.List<Part> whole = MicroStatue.rebuild(level, g);
                if (whole.size() != 2 || whole.get(0).volume() != 16 * 4 * 16 || whole.get(1).volume() != 2 * 12 * 2) errors.add("the statue made whole: " + whole.size() + " parts");
                else if (whole.get(0).minX != 0 || whole.get(1).minX != 7 || whole.get(1).minY != 4) errors.add("the statue lost its places");
            }
        }
        for (int dx = 0; dx <= 1; dx++) level.setBlock(above.offset(dx, 0, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        System.out.println("AUTOTEST place checks: " + (errors.isEmpty() ? "all passed" : errors));
        return errors.isEmpty() ? "ok" : String.join("; ", errors);
    }
}

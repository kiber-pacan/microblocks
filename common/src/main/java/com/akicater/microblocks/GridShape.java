package com.akicater.microblocks;

#if MC_VER < V26_2
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
#endif
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * The shape of parts as a grid of 16 x 16 x 16 cells, made in one pass over the cells that they hold. Joining boxes (the way the game builds a shape from pieces) takes
 * longer the more boxes there are: for a block that was worked with a chisel a hundred boxes took a quarter of a second, the grid takes a fraction of a millisecond.
 * From 26.2 the game does not let the shape with lists of coordinates be made from outside its package: the grid is a shape of its own there.
 *
 * The outline that the game draws around the block that is looked at follows every edge of a shape, which around a carved part is a line around every cell: the grid
 * gives the edges of the box of each part instead.
 */
public final class GridShape {
    private GridShape() {}

    #if MC_VER < V26_2
    private static final double[] COORDS = new double[17];

    static {
        for (int i = 0; i <= 16; i++) COORDS[i] = i / 16.0;
    }

    private static final class Grid extends ArrayVoxelShape {
        private final int[] boxes;

        Grid(BitSetDiscreteVoxelShape cells, int[] boxes) {
            super(cells, COORDS, COORDS, COORDS);
            this.boxes = boxes;
        }

        @Override
        public void forAllEdges(Shapes.DoubleLineConsumer out) {
            edges(boxes, out);
        }

        void detail(Shapes.DoubleLineConsumer out) {
            super.forAllEdges(out);
        }
    }
    #else
    private static final it.unimi.dsi.fastutil.doubles.DoubleList COORDS = new net.minecraft.world.phys.shapes.CubePointRange(16);

    private static final class Grid extends VoxelShape {
        private final int[] boxes;

        Grid(BitSetDiscreteVoxelShape cells, int[] boxes) {
            super(cells);
            this.boxes = boxes;
        }

        @Override
        public it.unimi.dsi.fastutil.doubles.DoubleList getCoords(net.minecraft.core.Direction.Axis axis) {
            return COORDS;
        }

        @Override
        protected int findIndex(net.minecraft.core.Direction.Axis axis, double coord) {
            return (int) Math.floor(Math.max(-1.0, Math.min(16.0, coord * 16.0)));
        }

        @Override
        public void forAllEdges(Shapes.DoubleLineConsumer out) {
            edges(boxes, out);
        }

        void detail(Shapes.DoubleLineConsumer out) {
            super.forAllEdges(out);
        }
    }
    #endif

    /** Every edge of the cells of a shape made here (the steps of a carved part): for a faint outline that the mod draws itself. Nothing for any other shape. */
    public static void detail(VoxelShape shape, Shapes.DoubleLineConsumer out) {
        if (shape instanceof Grid grid) grid.detail(out);
    }

    /** The twelve edges of every box (six numbers each, in sixteenths). */
    private static void edges(int[] boxes, Shapes.DoubleLineConsumer out) {
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            double x0 = boxes[i] / 16.0, y0 = boxes[i + 1] / 16.0, z0 = boxes[i + 2] / 16.0, x1 = boxes[i + 3] / 16.0, y1 = boxes[i + 4] / 16.0, z1 = boxes[i + 5] / 16.0;
            out.consume(x0, y0, z0, x1, y0, z0); out.consume(x0, y1, z0, x1, y1, z0); out.consume(x0, y0, z1, x1, y0, z1); out.consume(x0, y1, z1, x1, y1, z1);
            out.consume(x0, y0, z0, x0, y1, z0); out.consume(x1, y0, z0, x1, y1, z0); out.consume(x0, y0, z1, x0, y1, z1); out.consume(x1, y0, z1, x1, y1, z1);
            out.consume(x0, y0, z0, x0, y0, z1); out.consume(x1, y0, z0, x1, y0, z1); out.consume(x0, y1, z0, x0, y1, z1); out.consume(x1, y1, z0, x1, y1, z1);
        }
    }

    /** The cells that any of the parts holds; null when there are none. */
    public static VoxelShape of(List<Part> parts) {
        BitSetDiscreteVoxelShape cells = new BitSetDiscreteVoxelShape(16, 16, 16);
        int[] boxes = new int[parts.size() * 6];
        int count = 0;
        for (Part p : parts) {
            int x0 = 16, y0 = 16, z0 = 16, x1 = -1, y1 = -1, z1 = -1;
            for (int x = Math.max(0, p.minX); x < Math.min(16, p.maxX); x++) for (int y = Math.max(0, p.minY); y < Math.min(16, p.maxY); y++) for (int z = Math.max(0, p.minZ); z < Math.min(16, p.maxZ); z++) {
                if (!p.has(x, y, z)) continue;
                cells.fill(x, y, z);
                if (x < x0) x0 = x; if (y < y0) y0 = y; if (z < z0) z0 = z;
                if (x > x1) x1 = x; if (y > y1) y1 = y; if (z > z1) z1 = z;
            }
            if (x1 < 0) continue;
            boxes[count++] = x0; boxes[count++] = y0; boxes[count++] = z0; boxes[count++] = x1 + 1; boxes[count++] = y1 + 1; boxes[count++] = z1 + 1;
        }
        return count == 0 ? null : new Grid(cells, java.util.Arrays.copyOf(boxes, count));
    }
}

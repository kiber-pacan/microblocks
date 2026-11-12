package com.akicater.microblocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a 16 x 16 mask of face kinds (0 is no face, a number is a kind of face) into as few rectangles of one kind as it can: the heart of the greedy meshing.
 * Three ways, from the easiest to the most thorough: rows first (the classic greedy mesh), the better of rows first and columns first, and the biggest rectangle
 * first. {@link #best} tries them all and keeps the one with the fewest rectangles. No game classes in here: it can be tested on its own.
 */
public final class MicroPartition {
    private MicroPartition() {}

    public static final int N = 16;

    /** A rectangle: it starts at row {@code a} and column {@code b}, is {@code h} rows high and {@code w} columns wide, and holds the kind {@code v}. */
    public record Rect(int a, int b, int h, int w, int v) {}

    /** The classic one: scan the cells in order, grow along the row, then down while whole rows match. */
    public static List<Rect> rows(int[] mask) {
        int[] m = mask.clone();
        List<Rect> out = new ArrayList<>();
        for (int a = 0; a < N; a++) {
            for (int b = 0; b < N; b++) {
                int v = m[a * N + b];
                if (v == 0) continue;
                int w = 1;
                while (b + w < N && m[a * N + b + w] == v) w++;
                int h = 1;
                outer:
                while (a + h < N) {
                    for (int i = 0; i < w; i++) if (m[(a + h) * N + b + i] != v) break outer;
                    h++;
                }
                for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) m[(a + i) * N + b + j] = 0;
                out.add(new Rect(a, b, h, w, v));
            }
        }
        return out;
    }

    /** The same with the rows and the columns exchanged: grows down first, then along. */
    public static List<Rect> cols(int[] mask) {
        int[] t = new int[N * N];
        for (int a = 0; a < N; a++) for (int b = 0; b < N; b++) t[b * N + a] = mask[a * N + b];
        List<Rect> swapped = rows(t);
        List<Rect> out = new ArrayList<>(swapped.size());
        for (Rect r : swapped) out.add(new Rect(r.b(), r.a(), r.w(), r.h(), r.v()));
        return out;
    }

    /** Takes the biggest rectangle of one kind that there is, again and again; thin leftovers do not pile up the way they do when the scan order decides. */
    public static List<Rect> largest(int[] mask) {
        int[] m = mask.clone();
        List<Rect> out = new ArrayList<>();
        while (true) {
            int bestArea = 0, ba = 0, bb = 0, bh = 0, bw = 0;
            for (int a = 0; a < N; a++) {
                for (int b = 0; b < N; b++) {
                    int v = m[a * N + b];
                    if (v == 0) continue;
                    int width = N - b;                       // the widest it can be, shrinking as the rows go down
                    for (int h = 1; a + h <= N; h++) {
                        int row = a + h - 1, run = 0;
                        while (run < width && m[row * N + b + run] == v) run++;
                        width = run;
                        if (width == 0) break;
                        if (width * h > bestArea) { bestArea = width * h; ba = a; bb = b; bh = h; bw = width; }
                    }
                }
            }
            if (bestArea == 0) break;
            int v = m[ba * N + bb];
            for (int i = 0; i < bh; i++) for (int j = 0; j < bw; j++) m[(ba + i) * N + bb + j] = 0;
            out.add(new Rect(ba, bb, bh, bw, v));
        }
        return out;
    }

    // ---- the fast way: a row of the mask is 16 bits, a run of cells is found and checked with a few operations on whole rows (the binary greedy meshing)

    /** The rows of the cells of the kind {@code v}, one bit for a cell: row a is bit b of rows[a]. */
    private static int[] bitsOf(int[] mask, int v) {
        int[] rows = new int[N];
        for (int a = 0; a < N; a++) {
            int bits = 0;
            for (int b = 0; b < N; b++) if (mask[a * N + b] == v) bits |= 1 << b;
            rows[a] = bits;
        }
        return rows;
    }

    private static int[] transpose(int[] rows) {
        int[] t = new int[N];
        for (int a = 0; a < N; a++) for (int b = 0; b < N; b++) if ((rows[a] >>> b & 1) != 0) t[b] |= 1 << a;
        return t;
    }

    /** Rows first, on bits: the same cut as {@link #rows} for one kind, in a few operations per rectangle instead of a walk over the cells. */
    private static List<Rect> bitRows(int[] rows, int v) {
        List<Rect> out = new ArrayList<>();
        for (int a = 0; a < N; a++) {
            while (rows[a] != 0) {
                int b = Integer.numberOfTrailingZeros(rows[a]);
                int w = Integer.numberOfTrailingZeros(~(rows[a] >>> b));
                int run = (w >= 32 ? -1 : (1 << w) - 1) << b;
                int h = 1;
                while (a + h < N && (rows[a + h] & run) == run) {
                    rows[a + h] &= ~run;
                    h++;
                }
                rows[a] &= ~run;
                out.add(new Rect(a, b, h, w, v));
            }
        }
        return out;
    }

    /** The cut with the fewest rectangles of the ways above, kind by kind (the kinds do not share cells, so each can take its best way). */
    public static List<Rect> best(int[] mask) {
        int[] kinds = new int[8];
        int count = 0;
        List<Integer> more = null;
        for (int i = 0; i < N * N; i++) {
            int v = mask[i];
            if (v == 0) continue;
            boolean known = false;
            for (int k = 0; k < count && k < kinds.length; k++) if (kinds[k] == v) { known = true; break; }
            if (!known && more != null) known = more.contains(v);
            if (known) continue;
            if (count < kinds.length) kinds[count++] = v;
            else { if (more == null) more = new ArrayList<>(); more.add(v); count++; }
        }
        List<Rect> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int v = k < kinds.length ? kinds[k] : more.get(k - kinds.length);
            int[] rows = bitsOf(mask, v);
            List<Rect> a = bitRows(rows.clone(), v);
            if (a.size() > 1) {
                List<Rect> b = bitRows(transpose(rows), v);
                if (b.size() < a.size()) {
                    List<Rect> swapped = new ArrayList<>(b.size());
                    for (Rect r : b) swapped.add(new Rect(r.b(), r.a(), r.w(), r.h(), r.v()));
                    a = swapped;
                }
            }
            out.addAll(a);
        }
        return out;
    }
}

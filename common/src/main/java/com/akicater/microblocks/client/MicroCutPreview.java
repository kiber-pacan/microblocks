package com.akicater.microblocks.client;


/**
 * The box that the table cuts, drawn in the screen as a turning 3D box with its textures (by the software rasterizer of Aki Lib, so it works the same on every version of the game): drag to turn it,
 * click a face to cut across its axis, click a side face to put the cut where the click is, scroll to move the cut by a sixteenth.
 * The geometry is in sixteenths with the middle of the box at the origin, y up; the screen is x right, y down.
 */
public final class MicroCutPreview {
    public interface Fill {
        void fill(int x0, int y0, int x1, int y1, int argb);
    }

    public float yaw = -38, pitch = 24;
    private double[] sx = new double[8], sy = new double[8];

    // the screen rectangle of the preview
    private int rx, ry, rw, rh;
    private int[] dims = {16, 16, 16};
    private double scale = 1;

    public void layout(int x, int y, int w, int h, int[] dims) {
        this.rx = x; this.ry = y; this.rw = w; this.rh = h;
        this.dims = dims;
        int m = Math.max(dims[0], Math.max(dims[1], dims[2]));
        this.scale = Math.min(w, h) * 0.5 / (m * 0.8);
    }

    public boolean inside(double mx, double my) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    public void turn(double dx, double dy) {
        yaw += dx * 0.7f;
        pitch = (float) Math.max(-89, Math.min(89, pitch + dy * 0.7f));
    }

    // ---- projection

    /** The screen position of a point of the box (in sixteenths, from the middle); the third value is the depth towards the viewer. */
    private double[] project(double x, double y, double z) {
        double cy = Math.cos(Math.toRadians(yaw)), sy_ = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double x1 = x * cy + z * sy_, z1 = -x * sy_ + z * cy;      // turn about y
        double y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;       // tilt about x
        return new double[]{rx + rw / 2.0 + x1 * scale, ry + rh / 2.0 - y2 * scale, z2};
    }

    private double[] corner(int axis, int sign, int u, int v) {
        // a corner of the face of {@code axis} on the side {@code sign}; u and v (-1 or 1) pick the corner in the two other axes
        double[] p = new double[3];
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        p[axis] = sign * dims[axis] / 2.0;
        p[a1] = u * dims[a1] / 2.0;
        p[a2] = v * dims[a2] / 2.0;
        return project(p[0], p[1], p[2]);
    }

    private double[][] face(int axis, int sign) {
        return new double[][]{corner(axis, sign, -1, -1), corner(axis, sign, 1, -1), corner(axis, sign, 1, 1), corner(axis, sign, -1, 1)};
    }

    private boolean visible(int axis, int sign) {
        double[] p = new double[3];
        p[axis] = sign;
        return project(p[0], p[1], p[2])[2] - project(0, 0, 0)[2] > 0;
    }

    // ---- drawing

    public static void polygon(Fill fill, double[][] q, int argb) {
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : q) { minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
        for (int y = (int) Math.floor(minY); y <= (int) Math.ceil(maxY); y++) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            double yc = y + 0.5;
            for (int i = 0; i < q.length; i++) {
                double[] a = q[i], b = q[(i + 1) % q.length];
                if ((a[1] <= yc && b[1] > yc) || (b[1] <= yc && a[1] > yc)) {
                    double x = a[0] + (yc - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                    lo = Math.min(lo, x); hi = Math.max(hi, x);
                }
            }
            if (hi >= lo) fill.fill((int) Math.round(lo), y, Math.max((int) Math.round(hi), (int) Math.round(lo) + 1), y + 1, argb);
        }
    }

    private static void line(Fill fill, double[] a, double[] b, int argb) {
        int n = (int) Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) + 1;
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(a[0] + (b[0] - a[0]) * i / n), y = (int) Math.round(a[1] + (b[1] - a[1]) * i / n);
            fill.fill(x, y, x + 1, y + 1, argb);
        }
    }

    private static final int[] AXIS_COLOR = {0xE04040, 0x40D040, 0x4080F0};

    /** A panel sunk into the screen like a slot: a dark edge above and on the left, a light one below and on the right. */
    public static void sunk(Fill fill, int x, int y, int w, int h, int inside) {
        fill.fill(x, y, x + w - 1, y + 1, 0xFF373737);
        fill.fill(x, y, x + 1, y + h - 1, 0xFF373737);
        fill.fill(x + 1, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        fill.fill(x + w - 1, y + 1, x + w, y + h, 0xFFFFFFFF);
        fill.fill(x + w - 1, y, x + w, y + 1, 0xFF8B8B8B);
        fill.fill(x, y + h - 1, x + 1, y + h, 0xFF8B8B8B);
        fill.fill(x + 1, y + 1, x + w - 1, y + h - 1, inside);
    }

    /**
     * Draws the piece as it is, with its textures (the faces are those of the piece in the middle of a block space, {@code centre} is its middle in sixteenths),
     * and the plane of the cut at {@code pos} along {@code axis}.
     */
    public void draw(Fill fill, java.util.List<com.akicater.lib.client.QuadData> quads, double[] centre, int axis, int pos) {
        sunk(fill, rx, ry, rw, rh, 0xFF2E2E2E);
        com.akicater.lib.client.SoftRaster raster = new com.akicater.lib.client.SoftRaster(rw - 2, rh - 2);
        final int left = rx + 1, top = ry + 1;
        // from the block space of the faces (0..1) to the picture
        com.akicater.lib.client.SoftRaster.Projector block = (x, y, z, out) -> {
            double[] p = project(x * 16 - centre[0], y * 16 - centre[1], z * 16 - centre[2]);
            out[0] = (float) (p[0] - left); out[1] = (float) (p[1] - top); out[2] = (float) p[2];
        };
        for (com.akicater.lib.client.QuadData quad : quads) raster.quad(quad, block);
        // the plane of the cut: the section of the box across the chosen axis, in sixteenths from the middle
        com.akicater.lib.client.SoftRaster.Projector middle = (x, y, z, out) -> {
            double[] p = project(x, y, z);
            out[0] = (float) (p[0] - left); out[1] = (float) (p[1] - top); out[2] = (float) p[2];
        };
        float t = (float) (pos - dims[axis] / 2.0);
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        int[][] uv = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        float[] plane = new float[12];
        for (int i = 0; i < 4; i++) {
            plane[i * 3 + axis] = t;
            plane[i * 3 + a1] = uv[i][0] * dims[a1] / 2.0f;
            plane[i * 3 + a2] = uv[i][1] * dims[a2] / 2.0f;
        }
        raster.flat(plane, 0x70000000 | AXIS_COLOR[axis], middle);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            raster.line(plane[i * 3], plane[i * 3 + 1], plane[i * 3 + 2], plane[j * 3], plane[j * 3 + 1], plane[j * 3 + 2], 0xFF000000 | AXIS_COLOR[axis], middle);
        }
        raster.show(fill::fill, left, top);
    }

    // ---- picking

    private static boolean inQuad(double[][] q, double x, double y) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            double[] a = q[i], b = q[(i + 1) % 4];
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (cross > 0) pos = true; else if (cross < 0) neg = true;
        }
        return !(pos && neg);
    }

    /** The result of a click on the box: the axis of the face, and where along {@code axis} the click is (in sixteenths from the start; -1 if it is not on a side face). */
    public record Pick(int faceAxis, int along) {}

    public Pick pick(double mx, double my, int axis) {
        for (int a = 0; a < 3; a++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (!visible(a, sign) || !inQuad(face(a, sign), mx, my)) continue;
                int along = -1;
                if (a != axis) {
                    // the click is on a face that is parallel to the axis: solve for the coordinate along the axis
                    int m = 3 - a - axis;
                    double[] o = new double[3];
                    o[a] = sign * dims[a] / 2.0;
                    double[] p0 = project(o[0], o[1], o[2]);
                    double[] ea = new double[3], em = new double[3];
                    ea[axis] = 1; em[m] = 1;
                    double[] pa = project(o[0] + ea[0], o[1] + ea[1], o[2] + ea[2]), pm = project(o[0] + em[0], o[1] + em[1], o[2] + em[2]);
                    double ax = pa[0] - p0[0], ay = pa[1] - p0[1], mx_ = pm[0] - p0[0], my_ = pm[1] - p0[1];
                    double det = ax * my_ - ay * mx_;
                    if (Math.abs(det) > 1.0E-6) {
                        double ta = ((mx - p0[0]) * my_ - (my - p0[1]) * mx_) / det;
                        along = (int) Math.round(ta + dims[axis] / 2.0);
                    }
                }
                return new Pick(a, along);
            }
        }
        return null;
    }
}

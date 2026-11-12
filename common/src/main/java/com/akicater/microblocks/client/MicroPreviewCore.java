package com.akicater.microblocks.client;

import com.akicater.lib.client.Draw;
import com.akicater.lib.client.Overlay;
import com.akicater.lib.client.QuadData;
import com.akicater.microblocks.MicroBlockEntity;
import com.akicater.microblocks.MicroItem;
import com.akicater.microblocks.MicroMesh;
import com.akicater.microblocks.MicroPlacement;
import com.akicater.microblocks.Part;
import com.akicater.microblocks.PrefabData;
import com.akicater.microblocks.PrefabItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * While a microblock or a prefab is held and a block is looked at: the black outline of the box that would be placed (the same calculation as the placing itself);
 * for a prefab the real model, blue and half see-through, with the outlines of its parts. The library calls {@link #paint} from the hook of every loader for custom geometry.
 */
public final class MicroPreviewCore implements Overlay.Painter {

    private static final int OUTLINE = 0xFF000000;
    /** The blue of a prefab, in the hand (multiplied with the textures) and in the world (a little see-through). */
    public static final int PREFAB_TINT = 0xFFB4D8FF;
    private static final int GHOST = 0xB8B4D8FF;

    private static final java.util.Map<String, List<QuadData>> QUADS = new java.util.HashMap<>();
    private static int generation = -1;

    /** The faces of some parts, made once and kept while the sprites stay the same (this is asked for in every frame). */
    private static List<QuadData> quadsOf(List<Part> parts) {
        int now = com.akicater.lib.client.Sprites.generation();
        if (now != generation || QUADS.size() > 64) {
            QUADS.clear();
            generation = now;
        }
        StringBuilder key = new StringBuilder();
        for (Part p : parts) key.append(p.minX).append(',').append(p.minY).append(',').append(p.minZ).append(',').append(p.maxX).append(',').append(p.maxY).append(',').append(p.maxZ)
                .append('o').append(p.orient).append('s').append(p.tx).append(',').append(p.ty).append(',').append(p.tz).append('i').append(p.inner).append('m').append(java.util.Arrays.hashCode(p.mask)).append(com.akicater.lib.Compat.blockId(p.material)).append(';');
        return QUADS.computeIfAbsent(key.toString(), k -> {
            List<QuadData> raw = MicroGeometry.quads(MicroMesh.build(parts));
            if (parts.size() != 1 || parts.get(0).mask != null) return raw;
            Part p = parts.get(0);
            List<QuadData> out = new java.util.ArrayList<>(raw.size());
            for (QuadData q : raw) out.add(inset(q, 0.002f, (p.minX + p.maxX) / 32f, (p.minY + p.maxY) / 32f, (p.minZ + p.maxZ) / 32f));
            return out;
        });
    }

    /** A face moved a little into the box (towards its middle on every axis) so that it does not fight with the faces that are there already. */
    private static QuadData inset(QuadData q, float d, float cx, float cy, float cz) {
        float[] pos = q.pos().clone();
        float[] c = {cx, cy, cz};
        for (int i = 0; i < pos.length; i++) {
            float m = c[i % 3];
            pos[i] += pos[i] > m + 1.0E-6f ? -d : pos[i] < m - 1.0E-6f ? d : 0;
        }
        return new QuadData(q.face(), pos, q.uv(), q.sprite(), q.onBoundary(), q.block(), q.shade(), q.tint());
    }

    /** The faces of a whole box that lies across block spaces: six, from the anchor space (the faces reach out of it), inset. Kept while the sprites stay the same. */
    private static List<QuadData> boxQuads(MicroPlacement.Result target) {
        net.minecraft.world.phys.AABB w = target.whole();
        int ox = target.pos().getX() * 16, oy = target.pos().getY() * 16, oz = target.pos().getZ() * 16;
        int x0 = (int) Math.round(w.minX * 16) - ox, y0 = (int) Math.round(w.minY * 16) - oy, z0 = (int) Math.round(w.minZ * 16) - oz;
        int x1 = (int) Math.round(w.maxX * 16) - ox, y1 = (int) Math.round(w.maxY * 16) - oy, z1 = (int) Math.round(w.maxZ * 16) - oz;
        Part p = target.part();
        String key = "box" + x0 + "," + y0 + "," + z0 + "," + x1 + "," + y1 + "," + z1 + "o" + p.orient + "s" + p.tx + "," + p.ty + "," + p.tz + com.akicater.lib.Compat.blockId(p.material);
        int now = com.akicater.lib.client.Sprites.generation();
        if (now != generation || QUADS.size() > 64) {
            QUADS.clear();
            generation = now;
        }
        return QUADS.computeIfAbsent(key, k -> {
            List<QuadData> out = new java.util.ArrayList<>(6);
            for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
                int px = face.getStepX() > 0 ? x1 : x0, py = face.getStepY() > 0 ? y1 : y0, pz = face.getStepZ() > 0 ? z1 : z0;
                boolean ax = face.getAxis() == net.minecraft.core.Direction.Axis.X, ay = face.getAxis() == net.minecraft.core.Direction.Axis.Y, az = face.getAxis() == net.minecraft.core.Direction.Axis.Z;
                MicroMesh.Quad q = new MicroMesh.Quad(face, ax ? px : x0, ay ? py : y0, az ? pz : z0, ax ? px : x1, ay ? py : y1, az ? pz : z1, p.material, p.orient == 1 ? 0 : p.orient, p.tx, p.ty, p.tz, false, false);
                out.add(inset(MicroGeometry.quad(q), 0.002f, (x0 + x1) / 32f, (y0 + y1) / 32f, (z0 + z1) / 32f));
            }
            return out;
        });
    }

    /** Whether a face of a piece lies against another piece of the same box (then it is inside, and not drawn): the face and its other side are in the grid of sixteenths of the world. */
    private static boolean covered(QuadData quad, MicroPlacement.Result own, List<MicroPlacement.Result> all) {
        net.minecraft.core.Direction face = quad.face();
        int axis = face.getAxis().ordinal();
        int[] base = {own.pos().getX() * 16, own.pos().getY() * 16, own.pos().getZ() * 16};
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        float[] pos = quad.pos();
        for (int v = 0; v < 4; v++) for (int i = 0; i < 3; i++) {
            int g = Math.round(pos[v * 3 + i] * 16) + base[i];
            lo[i] = Math.min(lo[i], g);
            hi[i] = Math.max(hi[i], g);
        }
        int plane = face.getAxisDirection().getStep() > 0 ? hi[axis] : lo[axis];
        for (MicroPlacement.Result other : all) {
            if (other == own) continue;
            Part p = other.part();
            int[] ob = {other.pos().getX() * 16, other.pos().getY() * 16, other.pos().getZ() * 16};
            boolean touches = face.getAxisDirection().getStep() > 0 ? ob[axis] + p.min(axis) == plane : ob[axis] + p.max(axis) == plane;
            if (!touches) continue;
            boolean contains = true;
            for (int i = 0; i < 3 && contains; i++) if (i != axis) contains = ob[i] + p.min(i) <= lo[i] && ob[i] + p.max(i) >= hi[i];
            if (contains) return true;
        }
        return false;
    }

    /** Whether the face looks at the camera (the origin of the given offsets); the faces on the far side of a see-through box are not drawn. */
    static boolean facesCamera(QuadData q, double ox, double oy, double oz) {
        float[] p = q.pos();
        double cx = ox + (p[0] + p[3] + p[6] + p[9]) / 4, cy = oy + (p[1] + p[4] + p[7] + p[10]) / 4, cz = oz + (p[2] + p[5] + p[8] + p[11]) / 4;
        return q.face().getStepX() * cx + q.face().getStepY() * cy + q.face().getStepZ() * cz < 0;
    }

    private static boolean ctrlDown;

    #if MC_VER >= V1_21_9
    private static final net.minecraft.client.KeyMapping.Category CATEGORY = net.minecraft.client.KeyMapping.Category.GAMEPLAY;
    #else
    private static final String CATEGORY = "key.categories.gameplay";
    #endif

    /** The number that this version of the game has for a key, by the name of the key (the numbers are not the same in every version). */
    private static int code(String name) {
        return com.mojang.blaze3d.platform.InputConstants.getKey(name).getValue();
    }

    /** The keys of the mod, in the controls of the game: any key can be put on them. */
    public static final net.minecraft.client.KeyMapping WHEEL = new net.minecraft.client.KeyMapping("key.microblocks.wheel", code("key.keyboard.end"), CATEGORY);
    public static final net.minecraft.client.KeyMapping ROTATE = new net.minecraft.client.KeyMapping("key.microblocks.rotate", code("key.keyboard.r"), CATEGORY);
    private static final int LEFT_CTRL = code("key.keyboard.left.control"), RIGHT_CTRL = code("key.keyboard.right.control");

    private static boolean key(Minecraft mc, int code) {
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                #if MC_VER >= V26_3 code #elif MC_VER >= V1_21_9 mc.getWindow(), code #else mc.getWindow().getWindow(), code #endif);
    }

    /** Whether a control key is held (the tools and the glue ask). */
    public static boolean ctrl(Minecraft mc) {
        return key(mc, LEFT_CTRL) || key(mc, RIGHT_CTRL);
    }

    /**
     * Every tick of the client. The key of the turn (R) turns the microblock in the hand to the next way of lying (sneaking: back): the same as using it in the air,
     * which the server answers. Ctrl (the left or the right one) swaps the free placing and the snapping one while it is held. The key of the wheel (End) opens the
     * wheel of the settings.
     */
    public static void keys(Minecraft mc) {
        MicroNet.syncIfNew();
        boolean usable = mc.player != null && MicroNet.current() == null && mc.gameMode != null;
        boolean holding = usable && mc.player.getMainHandItem().getItem() instanceof MicroItem;
        while (ROTATE.consumeClick()) if (holding) mc.gameMode.useItem(mc.player, #if MC_VER < V1_19_2 mc.level, #endif InteractionHand.MAIN_HAND);
        boolean ctrl = (holding || usable && mc.player.getMainHandItem().is(net.minecraft.world.item.Items.SLIME_BALL)) && ctrl(mc);      // the glue: whole blocks
        if (ctrl != ctrlDown && mc.player != null) {
            ctrlDown = ctrl;
            MicroNet.change(com.akicater.microblocks.MicroSettings.KIND_CTRL, ctrl ? 1 : 0);
        }
        boolean open = false;
        while (WHEEL.consumeClick()) open = true;
        if (open && usable) MicroNet.open(new MicroWheelScreen());
    }

    /** The slow fade of the preview: between a third and nearly all of the opacity, a breath every two seconds. */
    private static int ghostColor() {
        if (!com.akicater.microblocks.MicroConfig.blink) return 0xA0FFFFFF;
        double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * Math.PI * 2;
        int alpha = (int) (0x50 + 0x58 * (0.5 + 0.5 * Math.sin(phase)));
        return alpha << 24 | 0xFFFFFF;
    }

    private static final int DETAIL = 0x24000000;      // the game draws its outline with 40% of black: this is a third of that

    /** The block of microblocks that is looked at: the edges of its cells (the steps of a carved part), faint, on top of the outline of the game (the boxes of the parts). */
    private static void detail(Minecraft mc, BlockHitResult hit, Overlay.Sink sink, Vec3 camera) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof com.akicater.microblocks.MicroBlock)) return;
        final java.util.List<float[]> lines = new java.util.ArrayList<>();
        final double ox = pos.getX() - camera.x, oy = pos.getY() - camera.y, oz = pos.getZ() - camera.z;
        com.akicater.microblocks.GridShape.detail(state.getShape(mc.level, pos), (x0, y0, z0, x1, y1, z1) ->
                lines.add(new float[]{(float) (ox + x0), (float) (oy + y0), (float) (oz + z0), (float) (ox + x1), (float) (oy + y1), (float) (oz + z1)}));
        if (lines.isEmpty() || lines.size() <= 12) return;      // a plain box: the outline of the game is all of it
        sink.geometry(Draw.lines(), (pose, consumer) -> { for (float[] l : lines) Draw.line(pose, consumer, DETAIL, l[0], l[1], l[2], l[3], l[4], l[5]); });
    }

    // ---- what a tool would take and what the glue would join: flat colour that is seen through the blocks in front of it

    /** The six faces of a box (in the world, less the camera) into the list, each as four corners. */
    private static void boxFaces(java.util.List<float[]> out, double x0, double y0, double z0, double x1, double y1, double z1) {
        float a = (float) x0, b = (float) y0, c = (float) z0, d = (float) x1, e = (float) y1, f = (float) z1;
        out.add(new float[]{a, b, c, d, b, c, d, b, f, a, b, f}); out.add(new float[]{a, e, f, d, e, f, d, e, c, a, e, c});
        out.add(new float[]{a, e, c, d, e, c, d, b, c, a, b, c}); out.add(new float[]{a, b, f, d, b, f, d, e, f, a, e, f});
        out.add(new float[]{a, b, c, a, b, f, a, e, f, a, e, c}); out.add(new float[]{d, e, c, d, e, f, d, b, f, d, b, c});
    }

    /** Draws the faces from both sides (the kind of drawing may leave out the back of a face). */
    private static void fill(Overlay.Sink sink, java.util.List<float[]> faces, int argb) {
        if (faces.isEmpty()) return;
        sink.geometry(Draw.seeThrough(com.akicater.microblocks.Microblocks.MOD_ID, "textures/misc/white.png"), (pose, consumer) -> {
            for (float[] q : faces) {
                Draw.fill(pose, consumer, argb, q);
                Draw.fill(pose, consumer, argb, new float[]{q[9], q[10], q[11], q[6], q[7], q[8], q[3], q[4], q[5], q[0], q[1], q[2]});
            }
        });
    }

    private static String toolKey = "";
    private static java.util.List<float[]> toolFaces = java.util.List.of();      // in the world, from the corner of the block that is looked at

    /** The cells that the tool in the hand would take away with a click now: their outside faces, red, seen through everything. */
    private static void toolCells(Minecraft mc, BlockHitResult hit, com.akicater.microblocks.MicroToolItem.Tool tool, Overlay.Sink sink, Vec3 camera) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        net.minecraft.core.Direction face = hit.getDirection();
        Vec3 inside = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ()).subtract(face.getStepX() * 0.001, face.getStepY() * 0.001, face.getStepZ() * 0.001);
        int[] c = {Math.max(0, Math.min(15, (int) Math.floor(inside.x * 16))), Math.max(0, Math.min(15, (int) Math.floor(inside.y * 16))), Math.max(0, Math.min(15, (int) Math.floor(inside.z * 16)))};
        Object there = mc.level.getBlockEntity(pos) instanceof MicroBlockEntity be ? be.parts() : mc.level.getBlockState(pos);
        String key = pos.asLong() + ":" + c[0] + "," + c[1] + "," + c[2] + ":" + face + ":" + tool + ":" + System.identityHashCode(there);
        if (!key.equals(toolKey)) {
            toolKey = key;
            toolFaces = cellsOf(mc, pos, c, face, tool);
        }
        final double ox = pos.getX() - camera.x, oy = pos.getY() - camera.y, oz = pos.getZ() - camera.z;
        java.util.List<float[]> moved = new java.util.ArrayList<>(toolFaces.size());
        for (float[] q : toolFaces) moved.add(new float[]{(float) (q[0] + ox), (float) (q[1] + oy), (float) (q[2] + oz), (float) (q[3] + ox), (float) (q[4] + oy), (float) (q[5] + oz), (float) (q[6] + ox), (float) (q[7] + oy), (float) (q[8] + oz), (float) (q[9] + ox), (float) (q[10] + oy), (float) (q[11] + oz)});
        fill(sink, moved, 0x40FF4030);
    }

    private static java.util.List<float[]> cellsOf(Minecraft mc, net.minecraft.core.BlockPos pos, int[] c, net.minecraft.core.Direction face, com.akicater.microblocks.MicroToolItem.Tool tool) {
        java.util.Map<net.minecraft.core.BlockPos, boolean[]> regions;
        if (tool == com.akicater.microblocks.MicroToolItem.Tool.SPLITTER) {
            // the whole part that is looked at
            boolean[] cells = new boolean[4096];
            if (mc.level.getBlockEntity(pos) instanceof MicroBlockEntity be) {
                Part top = null;
                for (Part p : be.parts()) if (p.has(c[0], c[1], c[2])) top = p;
                if (top != null) for (int x = top.minX; x < top.maxX; x++) for (int y = top.minY; y < top.maxY; y++) for (int z = top.minZ; z < top.maxZ; z++) if (top.has(x, y, z)) cells[x + 16 * (y + 16 * z)] = true;
            } else {
                java.util.Arrays.fill(cells, true);
            }
            regions = java.util.Map.of(pos, cells);
        } else {
            regions = com.akicater.microblocks.MicroTools.region(tool, new int[]{pos.getX() * 16 + c[0], pos.getY() * 16 + c[1], pos.getZ() * 16 + c[2]}, face);
        }
        java.util.List<float[]> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<net.minecraft.core.BlockPos, boolean[]> e : regions.entrySet()) {
            net.minecraft.core.BlockPos at = e.getKey();
            boolean[] cells = e.getValue();
            // only what is there to take: the cells of the parts, or all of an ordinary block that can be cut
            if (mc.level.getBlockEntity(at) instanceof MicroBlockEntity be) {
                for (int i = 0; i < 4096; i++) {
                    if (!cells[i]) continue;
                    boolean held = false;
                    for (Part p : be.parts()) if (p.has(i & 15, i >> 4 & 15, i >> 8)) { held = true; break; }
                    cells[i] = held;
                }
            } else if (!com.akicater.microblocks.MicroGlue.plain(mc.level, at)) {
                continue;
            }
            float bx = at.getX() - pos.getX(), by = at.getY() - pos.getY(), bz = at.getZ() - pos.getZ();
            for (int i = 0; i < 4096; i++) {
                if (!cells[i]) continue;
                int x = i & 15, y = i >> 4 & 15, z = i >> 8;
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                    if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16 && cells[nx + 16 * (ny + 16 * nz)]) continue;      // between two cells that go
                    float x0 = bx + x / 16f, y0 = by + y / 16f, z0 = bz + z / 16f, x1 = x0 + 1 / 16f, y1 = y0 + 1 / 16f, z1 = z0 + 1 / 16f;
                    out.add(switch (d) {
                        case DOWN -> new float[]{x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1};
                        case UP -> new float[]{x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0};
                        case NORTH -> new float[]{x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0};
                        case SOUTH -> new float[]{x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
                        case WEST -> new float[]{x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0};
                        default -> new float[]{x1, y1, z0, x1, y1, z1, x1, y0, z1, x1, y0, z0};
                    });
                }
            }
        }
        return out;
    }

    /** The first corner of the glue as this client knows it (the server keeps its own). */
    private static int[] glueCorner;

    /** A right click with the glue, on the client: the same steps as on the server, for what is shown. */
    public static void glueClicked(int[] cell, boolean sneaking) {
        glueCorner = sneaking || glueCorner != null ? null : cell;
    }

    /** What the glue would join with a click now: the box between the corners (the cell that is looked at before the first click), and every part that it reaches, green, seen through everything. */
    private static void glue(Minecraft mc, BlockHitResult hit, Overlay.Sink sink, Vec3 camera) {
        net.minecraft.core.BlockPos pos = hit.getBlockPos();
        if (!(mc.level.getBlockState(pos).getBlock() instanceof com.akicater.microblocks.MicroBlock) && !com.akicater.microblocks.MicroGlue.plain(mc.level, pos)) return;
        int[] now = com.akicater.microblocks.MicroGlue.cell(mc.player, pos, hit.getDirection());
        int[] first = glueCorner != null ? glueCorner : now;
        int[][] box = com.akicater.microblocks.MicroGlue.box(first, now, ctrl(mc));
        java.util.List<com.akicater.microblocks.MicroGlue.Target> targets = com.akicater.microblocks.MicroGlue.targets(mc.level, box[0], box[1]);
        final AABB region = new AABB(box[0][0] / 16.0, box[0][1] / 16.0, box[0][2] / 16.0, box[1][0] / 16.0, box[1][1] / 16.0, box[1][2] / 16.0).move(-camera.x, -camera.y, -camera.z).inflate(0.003);
        final int line = targets == null ? 0xFFFF4030 : 0xFF50E060;      // red: the box is too big
        sink.geometry(Draw.lines(), (pose, consumer) -> Draw.outline(pose, consumer, region, line));
        if (targets == null) return;
        java.util.List<float[]> faces = new java.util.ArrayList<>();
        for (com.akicater.microblocks.MicroGlue.Target t : targets) {
            Part p = t.part();
            double ox = t.pos().getX() - camera.x, oy = t.pos().getY() - camera.y, oz = t.pos().getZ() - camera.z;
            boxFaces(faces, ox + p.minX / 16.0 - 0.002, oy + p.minY / 16.0 - 0.002, oz + p.minZ / 16.0 - 0.002, ox + p.maxX / 16.0 + 0.002, oy + p.maxY / 16.0 + 0.002, oz + p.maxZ / 16.0 + 0.002);
        }
        fill(sink, faces, 0x3050E060);
    }

    @Override
    public void paint(PoseStack poseStack, Overlay.Sink sink, Vec3 camera) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        detail(mc, hit, sink, camera);
        if (mc.player.getMainHandItem().getItem() instanceof com.akicater.microblocks.MicroToolItem tool) toolCells(mc, hit, tool.tool, sink, camera);
        if (mc.player.getMainHandItem().is(net.minecraft.world.item.Items.SLIME_BALL) || mc.player.getOffhandItem().is(net.minecraft.world.item.Items.SLIME_BALL)) glue(mc, hit, sink, camera);
        ItemStack stack = ItemStack.EMPTY;
        for (InteractionHand hand : InteractionHand.values()) {
            Item item = mc.player.getItemInHand(hand).getItem();
            if (item instanceof MicroItem || (item instanceof PrefabItem prefab && prefab.prefab)) { stack = mc.player.getItemInHand(hand); break; }
        }
        if (stack.isEmpty()) return;

        if (stack.getItem() instanceof PrefabItem) {
            List<Part> parts = PrefabData.read(stack);
            com.akicater.microblocks.MicroStatue.Plan plan = com.akicater.microblocks.MicroStatue.plan(mc.level, hit.getBlockPos(), hit.getDirection(), hit.getLocation(), parts, com.akicater.microblocks.MicroSettings.of(mc.player));
            if (plan == null) return;
            // the statue as it will stand: each piece in its block space, one outline round the whole of it
            final int blink = ghostColor();
            sink.geometry(Draw.translucent(), (pose, consumer) -> {
                for (MicroPlacement.Result piece : plan.pieces()) {
                    final double ox = piece.pos().getX() - camera.x, oy = piece.pos().getY() - camera.y, oz = piece.pos().getZ() - camera.z;
                    for (QuadData quad : quadsOf(List.of(piece.part()))) if (facesCamera(quad, ox, oy, oz) && !covered(quad, piece, plan.pieces())) Draw.ghost(pose, consumer, quad, ox, oy, oz, (blink & 0xFF000000) | (GHOST & 0xFFFFFF));
                }
            });
            final AABB whole = plan.whole().move(-camera.x, -camera.y, -camera.z).inflate(0.002);
            sink.geometry(Draw.lines(), (pose, consumer) -> Draw.outline(pose, consumer, whole, OUTLINE));
            return;
        }

        MicroPlacement.Result target = MicroItem.target(mc.level, mc.player, stack, hit.getBlockPos(), hit.getDirection(), hit.getLocation());
        if (target == null) return;
        // the real faces of the box, with their textures, fading slowly, and the outline
        final int ghost = ghostColor();
        final boolean fill = com.akicater.microblocks.MicroSettings.of(mc.player).fill;
        final java.util.List<MicroPlacement.Result> pieces = MicroPlacement.pieces(mc.level, target, fill);
        sink.geometry(Draw.translucent(), (pose, consumer) -> {
            if (target.whole() != null && !fill) {
                // a box across block spaces: its own six faces, no walls inside
                final double ox = target.pos().getX() - camera.x, oy = target.pos().getY() - camera.y, oz = target.pos().getZ() - camera.z;
                for (QuadData quad : boxQuads(target)) if (facesCamera(quad, ox, oy, oz)) Draw.ghost(pose, consumer, quad, ox, oy, oz, ghost);
                return;
            }
            for (MicroPlacement.Result piece : pieces) {
                final double ox = piece.pos().getX() - camera.x, oy = piece.pos().getY() - camera.y, oz = piece.pos().getZ() - camera.z;
                for (QuadData quad : quadsOf(List.of(piece.part()))) if (facesCamera(quad, ox, oy, oz) && !covered(quad, piece, pieces)) Draw.ghost(pose, consumer, quad, ox, oy, oz, ghost);
            }
        });
        final java.util.List<AABB> boxes = new java.util.ArrayList<>();
        if (target.whole() != null) boxes.add(target.whole().move(-camera.x, -camera.y, -camera.z).inflate(0.002));      // a box across block spaces: one outline
        else boxes.add(target.part().box().move(target.pos().getX() - camera.x, target.pos().getY() - camera.y, target.pos().getZ() - camera.z).inflate(0.002));
        sink.geometry(Draw.lines(), (pose, consumer) -> { for (AABB box : boxes) Draw.outline(pose, consumer, box, OUTLINE); });
    }
}

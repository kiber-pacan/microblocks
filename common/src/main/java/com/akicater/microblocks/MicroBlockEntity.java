package com.akicater.microblocks;

import com.akicater.lib.Compat;
import com.akicater.lib.Nbt;

import net.minecraft.core.BlockPos;
#if MC_VER >= V1_21
import net.minecraft.core.HolderLookup;
#endif
import net.minecraft.nbt.CompoundTag;
#if MC_VER >= V1_21_6
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
#endif
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MicroBlockEntity extends BlockEntity {
    public static final int MAX_PARTS = 24;
    /** Time spent (for the stress test): reading the data on the main thread, and making the meshes on the chunk threads. */
    public static final java.util.concurrent.atomic.AtomicLong LOAD_NS = new java.util.concurrent.atomic.AtomicLong(), LOAD_COUNT = new java.util.concurrent.atomic.AtomicLong(), MESH_NS = new java.util.concurrent.atomic.AtomicLong(), MESH_COUNT = new java.util.concurrent.atomic.AtomicLong();

    /** The parts: a list that is never changed, only replaced (the chunk threads read it while the game changes it, and must never see it half done). */
    private volatile List<Part> parts = List.of();
    private List<Part> loading;       // the parts that are being read from the data; they become the parts all at once
    private volatile VoxelShape shape;
    private volatile VoxelShape collision;
    /** What a loader makes of the mesh for its chunk renderer (kept here, dropped with the mesh when the parts change). */
    public volatile Object renderCache;
    private volatile MicroMesh mesh;   // the merged faces for the chunk mesh; built when first asked for, dropped when the parts change

    public MicroBlockEntity(BlockPos pos, BlockState state) {
        super(Microblocks.BLOCK_ENTITY.get(), pos, state);
    }

    public List<Part> parts() {
        return parts;
    }

    public boolean isEmpty() {
        return parts.isEmpty();
    }

    public boolean canAdd(Part part) {
        return canAdd(part, false);
    }

    /** With {@code replace} a part may lie in the volume of others: it is drawn over them (the parts that come later win), and every part stays what it was. */
    public boolean canAdd(Part part, boolean replace) {
        if (parts.size() >= MAX_PARTS) return false;
        if (!replace) for (Part existing : parts) if (existing.overlaps(part)) return false;
        return true;
    }

    public boolean add(Part part, boolean replace) {
        if (!canAdd(part, replace)) return false;
        List<Part> next = new ArrayList<>(parts);
        next.add(part);
        parts = List.copyOf(next);
        changed();
        return true;
    }

    /** All the parts at once (the tools change many). */
    public void setParts(List<Part> next) {
        parts = List.copyOf(next);
        changed();
    }

    public boolean add(Part part) {
        return add(part, false);
    }

    public void remove(Part part) {
        List<Part> next = new ArrayList<>(parts);
        if (next.remove(part)) {
            parts = List.copyOf(next);
            changed();
        }
    }

    /** The greedy-meshed faces of the parts (read from the thread that builds chunk meshes). */
    public MicroMesh mesh() {
        MicroMesh result = mesh;
        if (result == null) {
            List<Part> snapshot = parts;
            long started = System.nanoTime();
            result = MicroMesh.build(snapshot, worldPosition);
            MicroTiming.end("the mesh of a block at " + worldPosition + " (" + snapshot.size() + " parts)", MicroConfig.debugTiming ? started : 0L);
            MESH_NS.addAndGet(System.nanoTime() - started);
            MESH_COUNT.incrementAndGet();
            if (parts == snapshot) mesh = result;      // not kept if the parts changed while it was made
        }
        return result;
    }

    public VoxelShape shape() {
        VoxelShape result = shape;
        if (result == null) {
            List<Part> snapshot = parts;
            boolean grouped = false;
            for (Part part : snapshot) grouped |= part.group != null && !part.group.fill();
            VoxelShape grid = grouped ? null : gridOf(snapshot);
            if (grid != null) {
                if (parts == snapshot) shape = grid;
                return grid;
            }
            VoxelShape all = Shapes.empty();
            java.util.Set<Integer> groups = new java.util.HashSet<>();
            for (Part part : snapshot) {
                Part.Group g = part.group;
                if (g != null && !g.fill()) {      // a box or a statue: the whole of it is what is pointed at
                    // a box across block spaces: the whole box is the shape here (so that the outline of it is whole), once
                    if (groups.add(g.id())) all = Shapes.or(all, Shapes.create((g.minX() - worldPosition.getX() * 16) / 16.0, (g.minY() - worldPosition.getY() * 16) / 16.0, (g.minZ() - worldPosition.getZ() * 16) / 16.0,
                            (g.maxX() - worldPosition.getX() * 16) / 16.0, (g.maxY() - worldPosition.getY() * 16) / 16.0, (g.maxZ() - worldPosition.getZ() * 16) / 16.0));
                } else {
                    all = Shapes.or(all, part.shape());
                }
            }
            result = all.isEmpty() ? Shapes.block() : all;
            if (parts == snapshot) shape = result;
        }
        return result;
    }

    /** One grid for all the parts when that is cheaper than joining their shapes (a part with holes, or many parts); null when the shapes are to be joined. */
    private static VoxelShape gridOf(List<Part> list) {
        boolean worth = list.size() > 3;
        for (Part p : list) worth |= p.mask != null;
        return worth ? GridShape.of(list) : null;
    }

    /** What is solid: every part as it is (the outline of a box across block spaces or of a statue is the whole of it, but what stops an entity is only what is there). */
    public VoxelShape collisionShape() {
        VoxelShape result = collision;
        if (result == null) {
            List<Part> snapshot = parts;
            VoxelShape all = gridOf(snapshot);
            if (all == null) {
                all = Shapes.empty();
                for (Part part : snapshot) all = Shapes.or(all, part.shape());
            }
            result = all.isEmpty() ? Shapes.block() : all;
            if (parts == snapshot) collision = result;      // asked for by every entity that moves near: made once
        }
        return result;
    }

    private void changed() {
        shape = null;
        collision = null;
        mesh = null;
        renderCache = null;
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
            level.getLightEngine().checkBlock(worldPosition);
        }
    }

    // ---- saving and syncing

    #if MC_VER >= V1_21_6
    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ValueOutput.ValueOutputList list = output.childrenList("parts");
        for (Part part : parts) {
            ValueOutput entry = list.addChild();
            entry.putIntArray("box", new int[]{part.minX, part.minY, part.minZ, part.maxX, part.maxY, part.maxZ});
            entry.putString("material", Compat.blockId(part.material));
            entry.putInt("orient", part.orient);
            if (part.inner != 0) entry.putInt("inner", part.inner);
            if (part.mask != null) entry.putIntArray("mask", Part.pack(part.mask));
            if (part.tx != 0 || part.ty != 0 || part.tz != 0) entry.putIntArray("shift", new int[]{part.tx, part.ty, part.tz});
            if (part.group != null) { entry.putInt("group", part.group.id()); entry.putIntArray("gbox", part.group.saved()); if (part.group.fill()) entry.putInt("gfill", 1); if (part.group.statue()) entry.putInt("gstatue", 1); }
            if (part.sub != 0) entry.putInt("sub", part.sub);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        clearForLoad();
        input.childrenListOrEmpty("parts").stream().forEach(entry -> addLoaded(entry.getStringOr("material", ""), entry.getIntArray("box").orElse(null), entry.getIntOr("orient", 0), entry.getIntArray("shift").orElse(null), entry.getIntOr("group", 0), entry.getIntArray("gbox").orElse(null), entry.getIntOr("inner", 0), entry.getIntOr("gfill", 0) != 0, entry.getIntArray("mask").orElse(null), entry.getIntOr("gstatue", 0) != 0, entry.getIntOr("sub", 0)));
        loaded();
    }
    #else
    private void writeParts(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Part part : parts) {
            CompoundTag entry = new CompoundTag();
            entry.putIntArray("box", new int[]{part.minX, part.minY, part.minZ, part.maxX, part.maxY, part.maxZ});
            entry.putString("material", Compat.blockId(part.material));
            entry.putInt("orient", part.orient);
            if (part.inner != 0) entry.putInt("inner", part.inner);
            if (part.mask != null) entry.putIntArray("mask", Part.pack(part.mask));
            if (part.tx != 0 || part.ty != 0 || part.tz != 0) entry.putIntArray("shift", new int[]{part.tx, part.ty, part.tz});
            if (part.group != null) { entry.putInt("group", part.group.id()); entry.putIntArray("gbox", part.group.saved()); if (part.group.fill()) entry.putInt("gfill", 1); if (part.group.statue()) entry.putInt("gstatue", 1); }
            if (part.sub != 0) entry.putInt("sub", part.sub);
            list.add(entry);
        }
        tag.put("parts", list);
    }

    private void readParts(CompoundTag tag) {
        clearForLoad();
        ListTag list = Nbt.compounds(tag, "parts");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = Nbt.compound(list, i);
            addLoaded(Nbt.str(entry, "material"), Nbt.ints(entry, "box"), Nbt.integer(entry, "orient", 0), Nbt.ints(entry, "shift"), Nbt.integer(entry, "group", 0), Nbt.ints(entry, "gbox"), Nbt.integer(entry, "inner", 0), Nbt.integer(entry, "gfill", 0) != 0, Nbt.ints(entry, "mask"), Nbt.integer(entry, "gstatue", 0) != 0, Nbt.integer(entry, "sub", 0));
        }
        loaded();
    }

    #if MC_VER >= V1_21
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        writeParts(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        readParts(tag);
    }
    #else
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writeParts(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        readParts(tag);
    }
    #endif
    #endif

    private void clearForLoad() {
        loading = new ArrayList<>();
    }

    private void addLoaded(String materialId, int[] box, int orient, int[] shift, int group, int[] gbox, int inner, boolean gfill, int[] maskInts, boolean gstatue, int sub) {
        if (loading == null || loading.size() >= MAX_PARTS) return;
        Block material = Compat.blockOf(materialId);
        if (material == null) return;
        if (box == null) box = new int[]{0, 0, 0, 16, 16, 16};
        Part.Group g = Part.Group.loaded(group, gbox, gfill, gstatue);
        boolean shifted = shift != null && shift.length == 3;
        if (box.length == 6) loading.add(new Part(box[0], box[1], box[2], box[3], box[4], box[5], material, orient, shifted ? shift[0] : 0, shifted ? shift[1] : 0, shifted ? shift[2] : 0, g, inner, Part.unpack(maskInts), sub));
    }

    /** The borders of the block space that a part reaches (a bit for every direction): only the neighbours there depend on it. */
    private static int touching(List<Part> list) {
        int bits = 0;
        for (Part p : list) {
            if (p.minY == 0) bits |= 1 << net.minecraft.core.Direction.DOWN.ordinal();
            if (p.maxY == 16) bits |= 1 << net.minecraft.core.Direction.UP.ordinal();
            if (p.minZ == 0) bits |= 1 << net.minecraft.core.Direction.NORTH.ordinal();
            if (p.maxZ == 16) bits |= 1 << net.minecraft.core.Direction.SOUTH.ordinal();
            if (p.minX == 0) bits |= 1 << net.minecraft.core.Direction.WEST.ordinal();
            if (p.maxX == 16) bits |= 1 << net.minecraft.core.Direction.EAST.ordinal();
        }
        return bits;
    }

    /** The faces on the borders of the blocks of microblocks next to this one depend on what is here: they are made again (client). */
    private void neighboursChanged(int touching) {
        if (level == null || !level.isClientSide()) return;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if ((touching >> d.ordinal() & 1) == 0) continue;      // no part reaches that border: the neighbour on that side sees nothing new
            BlockPos n = worldPosition.relative(d);
            if (level.getBlockEntity(n) instanceof MicroBlockEntity other) {
                other.renderCache = null;
                level.setBlocksDirty(n, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), other.getBlockState());
            }
        }
    }

    private void loaded() {
        long started = System.nanoTime();
        try {
            loaded0();
        } finally {
            MicroTiming.end("reading the data of a block at " + worldPosition, MicroConfig.debugTiming ? started : 0L);
            LOAD_NS.addAndGet(System.nanoTime() - started);
            LOAD_COUNT.incrementAndGet();
        }
    }

    private void loaded0() {
        List<Part> next = loading == null ? List.of() : List.copyOf(loading);
        loading = null;
        // the same parts again (the server sends them now and then): nothing changes, nothing is built again
        if (next.size() == parts.size()) {
            boolean same = true;
            for (int i = 0; same && i < next.size(); i++) same = next.get(i).sameAs(parts.get(i));
            if (same) return;
        }
        int touching = touching(parts) | touching(next);
        parts = next;
        shape = null;
        collision = null;
        mesh = null;
        renderCache = null;
        neighboursChanged(touching);
        // the chunk mesh has to be built again. The game ignores a change from a state to the same state, so the old state is air: without this a placed part stays invisible until a neighbour changes
        if (level != null && level.isClientSide()) level.setBlocksDirty(worldPosition, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), getBlockState());
    }

    #if MC_VER >= V1_21
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }
    #else
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
    #endif

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

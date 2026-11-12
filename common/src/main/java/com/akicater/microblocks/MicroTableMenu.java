package com.akicater.microblocks;

import com.akicater.lib.Compat;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The table: two modes. Cutting: a block or a microblock in the input is cut across one of its three axes at a chosen sixteenth into two pieces.
 * Prefabs: a blueprint (paper) and the ingredients (blocks and microblocks, of any size) give as many prefabs as the material allows: the volume of each
 * material that the blueprint needs is divided into the volume that is put in (a block is 16 x 16 x 16 cubic sixteenths), the lowest of those counts is the number.
 */
public class MicroTableMenu extends AbstractContainerMenu {
    public static final int MODE_CUT = 0, MODE_PREFAB = 1;
    public static final int BTN_MODE_CUT = 0, BTN_MODE_PREFAB = 1, BTN_AXIS = 2 /* 2, 3, 4 */, BTN_POS_MINUS = 5, BTN_POS_PLUS = 6, BTN_DO_CUT = 7, BTN_POS_SET = 16 /* 16 + the sixteenth */;

    // the slots of the table container
    public static final int IN = 0, OUT1 = 1, OUT2 = 2, PAPER = 3, ING_FIRST = 4, ING_LAST = 12, PREFAB_OUT = 13, SIZE = 14;

    private final ContainerLevelAccess access;
    private final Container table;
    private final DataSlot mode = DataSlot.standalone();
    private final DataSlot axis = DataSlot.standalone();
    private final DataSlot pos = DataSlot.standalone();
    private final int kind;
    private boolean updating;

    /** Client side (the server sends the numbers through the data slots) and test side: the table that cuts. */
    public MicroTableMenu(int id, Inventory inventory) {
        this(id, inventory, ContainerLevelAccess.NULL);
    }

    public MicroTableMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        this(id, inventory, access, MODE_CUT);
    }

    /** The client side of the table of blueprints. */
    public static MicroTableMenu blueprints(int id, Inventory inventory) {
        return new MicroTableMenu(id, inventory, ContainerLevelAccess.NULL, MODE_PREFAB);
    }

    /** {@code kind}: what the table does, cutting ({@link #MODE_CUT}, the iron table) or prefabs from blueprints ({@link #MODE_PREFAB}, the blue one); each is a block of its own. */
    public MicroTableMenu(int id, Inventory inventory, ContainerLevelAccess access, int kind) {
        super(kind == MODE_PREFAB ? Microblocks.BLUEPRINT_MENU.get() : Microblocks.TABLE_MENU.get(), id);
        this.kind = kind;
        this.mode.set(kind);
        this.access = access;
        this.table = new SimpleContainer(SIZE) {
            @Override
            public void setChanged() {
                super.setChanged();
                MicroTableMenu.this.slotsChanged(this);
            }
        };
        addDataSlot(mode);
        addDataSlot(axis);
        addDataSlot(pos);
        pos.set(8);

        // cutting
        addSlot(new TableSlot(IN, 18, 52, MODE_CUT, stack -> source(stack) != null));
        addSlot(new TableSlot(OUT1, 134, 42, MODE_CUT, stack -> false));
        addSlot(new TableSlot(OUT2, 134, 66, MODE_CUT, stack -> false));
        // prefabs
        addSlot(new TableSlot(PAPER, 18, 56, MODE_PREFAB, stack -> stack.getItem() instanceof PrefabItem p && !p.prefab));
        for (int i = 0; i < 9; i++) addSlot(new TableSlot(ING_FIRST + i, 62 + 18 * (i % 3), 38 + 18 * (i / 3), MODE_PREFAB, stack -> PrefabData.volumeOf(stack) > 0));
        addSlot(new ResultSlot(PREFAB_OUT, 134, 56));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 122 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 122 + 58));
    }

    private class TableSlot extends Slot {
        private final int onlyMode;
        private final Predicate<ItemStack> accepts;

        TableSlot(int index, int x, int y, int onlyMode, Predicate<ItemStack> accepts) {
            super(table, index, x, y);
            this.onlyMode = onlyMode;
            this.accepts = accepts;
        }

        @Override
        public boolean isActive() {
            return mode.get() == onlyMode;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && accepts.test(stack);
        }
    }

    private class ResultSlot extends TableSlot {
        ResultSlot(int index, int x, int y) {
            super(index, x, y, MODE_PREFAB, stack -> false);
        }

        /** The ingredients are used up for the prefabs that are taken. */
        @Override
        public void onTake(Player player, ItemStack stack) {
            consume(player, stack.getCount());
            super.onTake(player, stack);
        }
    }

    // ---- numbers the screen shows

    public int mode() { return mode.get(); }
    public int axis() { return axis.get(); }
    public int pos() { return pos.get(); }
    public Container table() { return table; }
    public void setMode(int value) { mode.set(value); }

    /** A microblock or a whole block as something to cut: its three sizes and its material; null for anything else. */
    public static Source source(ItemStack stack) {
        if (stack.getItem() instanceof MicroItem) return new Source(MicroItemData.dims(stack), MicroItemData.material(stack), MicroItemData.rotation(stack), MicroItemData.offset(stack) == null ? new int[3] : MicroItemData.offset(stack), MicroItemData.inner(stack));
        if (stack.getItem() instanceof BlockItem blockItem && MicroMaterials.isValid(blockItem.getBlock())) return new Source(new int[]{16, 16, 16}, blockItem.getBlock(), 1, new int[3], 0);      // a block lies as it is: a cut across x gives a plate that stands
        return null;
    }

    /** {@code rotation}: how the sizes lie on the axes of the world (0 is automatic, which lets the piece lie by its thin side). */
    public record Source(int[] dims, Block material, int rotation, int[] off, int inner) {}

    /** The size of the input along the chosen axis (16 for no input): the cut can be made at 1 .. size - 1. */
    public int cutSize() {
        Source source = source(table.getItem(IN));
        return source == null ? 16 : source.dims()[axis.get()];
    }

    private void clampPos() {
        int size = cutSize();
        pos.set(Math.max(1, Math.min(Math.max(1, size - 1), pos.get())));
    }

    /** How many prefabs the blueprint and the ingredients make now (what the output slot shows). */
    public int prefabCount() {
        return table.getItem(PREFAB_OUT).getCount();
    }

    // ---- buttons

    @Override
    public boolean clickMenuButton(Player player, int id) {
        switch (id) {
            case BTN_AXIS, BTN_AXIS + 1, BTN_AXIS + 2 -> { axis.set(id - BTN_AXIS); clampPos(); }
            case BTN_POS_MINUS -> { pos.set(pos.get() - 1); clampPos(); }
            case BTN_POS_PLUS -> { pos.set(pos.get() + 1); clampPos(); }
            case BTN_DO_CUT -> cut();
            default -> {
                if (id < BTN_POS_SET || id > BTN_POS_SET + 16) return false;
                pos.set(id - BTN_POS_SET);
                clampPos();
            }
        }
        return true;
    }

    /** The cut: the input (one of it) becomes two pieces, the first with {@code pos} sixteenths along the axis, the second with the rest. */
    public boolean cut() {
        ItemStack input = table.getItem(IN);
        Source source = source(input);
        if (source == null || !table.getItem(OUT1).isEmpty() || !table.getItem(OUT2).isEmpty()) return false;
        int a = axis.get();
        int size = source.dims()[a];
        if (size < 2) return false;
        int p = Math.max(1, Math.min(size - 1, pos.get()));
        int[] first = source.dims().clone(), second = source.dims().clone();
        first[a] = p;
        second[a] = size - p;
        // each piece keeps the part of the texture that it was: the second starts p sixteenths further in
        int[] offFirst = source.off().clone(), offSecond = source.off().clone();
        offSecond[a] += p;
        // the faces that the cut made are the inside of the block: they show the underside of its texture
        net.minecraft.core.Direction.Axis cutAxis = net.minecraft.core.Direction.Axis.values()[a];
        int innerFirst = source.inner() | 1 << net.minecraft.core.Direction.fromAxisAndDirection(cutAxis, net.minecraft.core.Direction.AxisDirection.POSITIVE).ordinal();
        int innerSecond = source.inner() | 1 << net.minecraft.core.Direction.fromAxisAndDirection(cutAxis, net.minecraft.core.Direction.AxisDirection.NEGATIVE).ordinal();
        updating = true;
        try {
            table.setItem(OUT1, MicroItemData.make(source.material(), first[0], first[1], first[2], source.rotation(), offFirst, innerFirst));
            table.setItem(OUT2, MicroItemData.make(source.material(), second[0], second[1], second[2], source.rotation(), offSecond, innerSecond));
            input.shrink(1);
        } finally {
            updating = false;
        }
        clampPos();
        updatePrefabs();
        return true;
    }

    // ---- prefabs

    @Override
    public void slotsChanged(Container container) {
        if (updating) return;
        clampPos();
        updatePrefabs();
        super.slotsChanged(container);
    }

    private Map<Block, Integer> available() {
        Map<Block, Integer> available = new HashMap<>();
        for (int i = ING_FIRST; i <= ING_LAST; i++) {
            ItemStack stack = table.getItem(i);
            int volume = PrefabData.volumeOf(stack);
            if (volume > 0) available.merge(PrefabData.materialOf(stack), volume * stack.getCount(), Integer::sum);
        }
        return available;
    }

    private void updatePrefabs() {
        ItemStack result = ItemStack.EMPTY;
        ItemStack paper = table.getItem(PAPER);
        if (paper.getItem() instanceof PrefabItem item && !item.prefab) {
            List<Part> parts = PrefabData.read(paper);
            Map<Block, Integer> need = PrefabData.volumes(parts);
            if (!need.isEmpty()) {
                Map<Block, Integer> have = available();
                int n = 64;
                for (Map.Entry<Block, Integer> e : need.entrySet()) n = Math.min(n, have.getOrDefault(e.getKey(), 0) / e.getValue());
                if (n > 0) {
                    result = new ItemStack(Microblocks.PREFAB.get(), n);
                    PrefabData.write(result, parts);
                }
            }
        }
        if (ItemStack.matches(table.getItem(PREFAB_OUT), result)) return;
        updating = true;
        try {
            table.setItem(PREFAB_OUT, result);
        } finally {
            updating = false;
        }
    }

    /**
     * Uses up the ingredients for {@code count} prefabs. For every material the volume needed is taken from the smallest pieces first; the last piece that is
     * started gives what it has left back, as planes of 1/2, 1/4, 1/8 and 1/16 of a block (less than a 16th of a block is lost).
     */
    private void consume(Player player, int count) {
        ItemStack paper = table.getItem(PAPER);
        if (count <= 0 || !(paper.getItem() instanceof PrefabItem)) return;
        updating = true;
        try {
            for (Map.Entry<Block, Integer> need : PrefabData.volumes(PrefabData.read(paper)).entrySet()) {
                long remaining = (long) need.getValue() * count;
                List<Integer> slots = new ArrayList<>();
                for (int i = ING_FIRST; i <= ING_LAST; i++) if (PrefabData.materialOf(table.getItem(i)) == need.getKey() && PrefabData.volumeOf(table.getItem(i)) > 0) slots.add(i);
                slots.sort(Comparator.comparingInt(i -> PrefabData.volumeOf(table.getItem(i))));
                for (int i : slots) {
                    ItemStack stack = table.getItem(i);
                    int volume = PrefabData.volumeOf(stack);
                    while (remaining > 0 && !stack.isEmpty()) {
                        stack.shrink(1);
                        remaining -= volume;
                    }
                    if (remaining <= 0) break;
                }
                if (remaining < 0) giveBack(player, need.getKey(), (int) -remaining);
            }
        } finally {
            updating = false;
        }
        updatePrefabs();
    }

    private void giveBack(Player player, Block material, int volume) {
        for (int thickness : new int[]{8, 4, 2, 1}) {
            int plane = 16 * 16 * thickness;
            while (volume >= plane) {
                volume -= plane;
                ItemStack stack = MicroItemData.make(material, 16, 16, thickness);
                if (!player.getInventory().add(stack)) Block.popResource(Compat.level(player), player.blockPosition(), stack);
            }
        }
    }

    // ---- the rest of a menu

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        final int playerStart = SIZE, playerEnd = slots.size();
        if (index < SIZE) {
            if (index == PREFAB_OUT) return ItemStack.EMPTY;                       // taken by clicking
            if (!moveItemStackTo(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else if (mode.get() == MODE_CUT) {
            if (!moveItemStackTo(stack, IN, IN + 1, false)) return ItemStack.EMPTY;
        } else {
            boolean moved = stack.getItem() instanceof PrefabItem p && !p.prefab
                    ? moveItemStackTo(stack, PAPER, PAPER + 1, false)
                    : moveItemStackTo(stack, ING_FIRST, ING_LAST + 1, false);
            if (!moved) return ItemStack.EMPTY;
        }
        slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, kind == MODE_PREFAB ? Microblocks.BLUEPRINT_TABLE.get() : Microblocks.TABLE.get());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (Compat.level(player).isClientSide()) return;
        for (int i = 0; i < PREFAB_OUT; i++) {
            ItemStack stack = table.removeItemNoUpdate(i);
            if (!stack.isEmpty() && !player.getInventory().add(stack)) Block.popResource(Compat.level(player), player.blockPosition(), stack);
        }
    }
}

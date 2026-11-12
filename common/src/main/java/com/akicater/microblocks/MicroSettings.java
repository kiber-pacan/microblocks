package com.akicater.microblocks;

import com.akicater.lib.Compat;

import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player has chosen for placing: how the box follows the click ({@link Mode}), whether a box may be put into others (replace), whether Ctrl is held
 * (it turns the free mode into the snapping one and any other mode into the free one). The client sends every change; the placing asks here.
 * One packet carries it all: a kind and a value.
 */
public final class MicroSettings {
    private MicroSettings() {}

    public static final #if MC_VER >= V1_21_11 net.minecraft.resources.Identifier #else net.minecraft.resources.ResourceLocation #endif ID = Compat.id(Microblocks.MOD_ID, "settings");

    /** FREE: any sixteenth, also across block spaces. SNAP: to the edges and the middle of the block space. VOXEL: any sixteenth inside the block space that was clicked. */
    public enum Mode { FREE, SNAP, VOXEL }

    public static final int KIND_CTRL = 0, KIND_MODE = 1, KIND_REPLACE = 2, KIND_ROTATION = 3, KIND_ROTMODE = 4, KIND_TOOL_X = 5, KIND_TOOL_Y = 6, KIND_TOOL_Z = 7, KIND_TOOL_GO = 8;

    public static final class State {
        public volatile boolean ctrl;
        public volatile int mode;
        /** What happens where a new box meets parts that are there: nothing is allowed (neither flag), it is drawn over them (replace), or it fills only the empty cells (fill). */
        public volatile boolean replace, fill;
        /** How a microblock without a chosen turn lies: see {@link MicroItemData#ROTATION_MODES}. */
        public volatile int rotMode;
        /** The block that a tool is to work on (the client says it in three numbers and then the go). */
        public volatile int toolX, toolY, toolZ;
        int toolTick = -1;      // the tick of the last work with a tool
        /** The first corner of the glue (a cell of the world, in sixteenths), null before it is chosen. */
        public volatile int[] glueA;

        public Mode effective() {
            Mode chosen = Mode.values()[Math.max(0, Math.min(2, mode))];
            if (!ctrl) return chosen;
            return chosen == Mode.FREE ? Mode.SNAP : Mode.FREE;
        }
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    public static State of(Player player) {
        return STATES.computeIfAbsent(player.getUUID(), id -> new State());
    }

    public static State of(UUID id) {
        return STATES.computeIfAbsent(id, i -> new State());
    }

    public static void apply(State state, int kind, int value) {
        switch (kind) {
            case KIND_CTRL -> state.ctrl = value != 0;
            case KIND_MODE -> state.mode = Math.max(0, Math.min(2, value));
            case KIND_REPLACE -> { state.replace = value == 1; state.fill = value == 2; }
            case KIND_ROTMODE -> state.rotMode = Math.max(0, Math.min(MicroItemData.ROTATION_MODES - 1, value));
            default -> {}
        }
    }

    private static void handle(Player player, int kind, int value) {
        if (kind >= KIND_TOOL_X && kind <= KIND_TOOL_GO) {
            State state = of(player);
            switch (kind) {
                case KIND_TOOL_X -> state.toolX = value;
                case KIND_TOOL_Y -> state.toolY = value;
                case KIND_TOOL_Z -> state.toolZ = value;
                default -> {
                    // go: the face and the cell that is looked at, packed; the block must be near, loaded, and the tool in the hand
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(state.toolX, state.toolY, state.toolZ);
                    net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
                    if (!(player instanceof ServerPlayer server) || !(stack.getItem() instanceof MicroToolItem tool)) return;
                    if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0 || !Compat.level(player).hasChunkAt(pos)) return;
                    net.minecraft.core.Direction face = net.minecraft.core.Direction.from3DDataValue(value & 7);
                    int cx = value >> 3 & 15, cy = value >> 7 & 15, cz = value >> 11 & 15;
                    MicroTools.work(server, stack, tool.tool, pos, face, new net.minecraft.world.phys.Vec3(pos.getX() + (cx + 0.5) / 16.0, pos.getY() + (cy + 0.5) / 16.0, pos.getZ() + (cz + 0.5) / 16.0));
                }
            }
            return;
        }
        if (kind == KIND_ROTATION) {
            // the way the held microblock lies (0 is automatic, 1 to 6 the turns)
            ItemStack stack = player.getMainHandItem();
            if (stack.getItem() instanceof MicroItem) MicroItemData.setRotation(stack, Math.max(0, Math.min(6, value)));
        } else {
            apply(of(player), kind, value);
        }
    }

    #if MC_VER >= V26_2
    /** From 26.2 the networking of Architectury takes only payloads. */
    public record Payload(int kind, int value) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ID);
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Payload> CODEC =
                net.minecraft.network.codec.StreamCodec.ofMember((payload, buf) -> { buf.writeInt(payload.kind()); buf.writeInt(payload.value()); }, buf -> new Payload(buf.readInt(), buf.readInt()));

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.c2s(), Payload.TYPE, Payload.CODEC, (payload, context) -> {
            final Player player = context.getPlayer();
            context.queue(() -> handle(player, payload.kind(), payload.value()));
        });
    }
    #else
    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.c2s(), ID, (buf, context) -> {
            final int kind = buf.readInt(), value = buf.readInt();
            final Player player = context.getPlayer();
            context.queue(() -> handle(player, kind, value));
        });
    }
    #endif
}

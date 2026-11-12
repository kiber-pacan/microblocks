package com.akicater.microblocks;

import com.akicater.lib.Compat;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
#if MC_VER >= V1_19_4
import net.minecraft.core.registries.Registries;
#else
import net.minecraft.core.Registry;
#endif
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.inventory.MenuType;
#if MC_VER >= V1_19_4
import net.minecraft.world.flag.FeatureFlags;
#endif
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.logging.Logger;

public final class Microblocks {
    public static final String MOD_ID = "microblocks";
    public static final Logger LOGGER = Logger.getLogger(MOD_ID);

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(MOD_ID, #if MC_VER >= V1_19_4 Registries.BLOCK #else Registry.BLOCK_REGISTRY #endif);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(MOD_ID, #if MC_VER >= V1_19_4 Registries.ITEM #else Registry.ITEM_REGISTRY #endif);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(MOD_ID, #if MC_VER >= V1_19_4 Registries.BLOCK_ENTITY_TYPE #else Registry.BLOCK_ENTITY_TYPE_REGISTRY #endif);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(MOD_ID, #if MC_VER >= V1_19_4 Registries.MENU #else Registry.MENU_REGISTRY #endif);
    #if MC_VER >= V1_20_1
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(MOD_ID, Registries.CREATIVE_MODE_TAB);
    #endif

    public static final RegistrySupplier<Block> BLOCK = BLOCKS.register("holder",
            () -> new MicroBlock(Block.Properties.of(#if MC_VER < V1_20_1 net.minecraft.world.level.material.Material.STONE #endif)#if MC_VER >= V1_21_3 .setId(ResourceKey.create(Registries.BLOCK, Compat.id(MOD_ID, "holder"))) #endif
                    .strength(1.0F).noOcclusion().dynamicShape()#if MC_VER >= V1_19_2 .noLootTable() #else .noDrops() #endif.sound(SoundType.STONE)));

    public static final RegistrySupplier<BlockEntityType<MicroBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register("holder",
            () -> com.akicater.lib.BlockEntities.create(MicroBlockEntity::new, BLOCK.get()));

    public static final RegistrySupplier<Item> MICRO = ITEMS.register("microblock", () -> new MicroItem(tabbed(itemProperties("microblock"))));

    public static final RegistrySupplier<Block> TABLE = BLOCKS.register("table",
            () -> new MicroTableBlock(Block.Properties.of(#if MC_VER < V1_20_1 net.minecraft.world.level.material.Material.WOOD #endif)#if MC_VER >= V1_21_3 .setId(ResourceKey.create(Registries.BLOCK, Compat.id(MOD_ID, "table"))) #endif.strength(3.0F).sound(SoundType.METAL), MicroTableMenu.MODE_CUT));
    public static final RegistrySupplier<Item> TABLE_ITEM = ITEMS.register("table",
            () -> new BlockItem(TABLE.get(), tabbed(itemProperties("table"))#if MC_VER >= V1_21_3 .useBlockDescriptionPrefix() #endif));
    public static final RegistrySupplier<Block> BLUEPRINT_TABLE = BLOCKS.register("blueprint_table",
            () -> new MicroTableBlock(Block.Properties.of(#if MC_VER < V1_20_1 net.minecraft.world.level.material.Material.WOOD #endif)#if MC_VER >= V1_21_3 .setId(ResourceKey.create(Registries.BLOCK, Compat.id(MOD_ID, "blueprint_table"))) #endif.strength(2.0F).sound(SoundType.WOOD), MicroTableMenu.MODE_PREFAB));
    public static final RegistrySupplier<Item> BLUEPRINT_TABLE_ITEM = ITEMS.register("blueprint_table",
            () -> new BlockItem(BLUEPRINT_TABLE.get(), tabbed(itemProperties("blueprint_table"))#if MC_VER >= V1_21_3 .useBlockDescriptionPrefix() #endif));
    public static final RegistrySupplier<Item> CHISEL_FINE = tool("chisel_fine", MicroToolItem.Tool.FINE);
    public static final RegistrySupplier<Item> CHISEL_FLAT = tool("chisel_flat", MicroToolItem.Tool.FLAT);
    public static final RegistrySupplier<Item> CHISEL_GOUGE = tool("chisel_gouge", MicroToolItem.Tool.GOUGE);
    public static final RegistrySupplier<Item> CHISEL_SPLITTER = tool("chisel_splitter", MicroToolItem.Tool.SPLITTER);
    public static final RegistrySupplier<Item> DRILL_THIN = tool("drill_thin", MicroToolItem.Tool.DRILL_THIN);
    public static final RegistrySupplier<Item> DRILL_WIDE = tool("drill_wide", MicroToolItem.Tool.DRILL_WIDE);
    public static final RegistrySupplier<Item> BLUEPRINT = ITEMS.register("blueprint", () -> new PrefabItem(false, itemProperties("blueprint").stacksTo(16)));
    public static final RegistrySupplier<Item> PREFAB = ITEMS.register("prefab", () -> new PrefabItem(true, itemProperties("prefab")));
    public static final RegistrySupplier<MenuType<MicroTableMenu>> TABLE_MENU = MENUS.register("table", () -> {
                return new MenuType<>(MicroTableMenu::new#if MC_VER >= V1_19_4 , FeatureFlags.VANILLA_SET#endif);
            });
    public static final RegistrySupplier<MenuType<MicroTableMenu>> BLUEPRINT_MENU = MENUS.register("blueprint_table", () -> {
                return new MenuType<>(MicroTableMenu::blueprints#if MC_VER >= V1_19_4 , FeatureFlags.VANILLA_SET#endif);
            });

    #if MC_VER >= V1_20_1
    public static final RegistrySupplier<CreativeModeTab> TAB = TABS.register("main", () -> CreativeTabRegistry.create(builder -> builder
            .title(com.akicater.lib.Compat.translatable("itemGroup.microblocks"))
            .icon(() -> MicroItemData.make(net.minecraft.world.level.block.Blocks.STONE_BRICKS, 16, 16, 4))
            .displayItems((parameters, output) -> fillTab(output::accept))));
    #elif MC_VER >= V1_19_4
    // before 1.20 a creative tab is made by the helper of Architectury itself
    public static final CreativeTabRegistry.TabSupplier TAB = CreativeTabRegistry.create(Compat.id(MOD_ID, "main"), builder -> builder
            .title(com.akicater.lib.Compat.translatable("itemGroup.microblocks"))
            .icon(() -> MicroItemData.make(net.minecraft.world.level.block.Blocks.STONE_BRICKS, 16, 16, 4))
            .displayItems((parameters, output) -> fillTab(output::accept)));
    #else
    // before 1.19.3 an item says in its properties which tab it is in, and fills the tab with its own stacks (MicroItem does)
    public static final CreativeModeTab TAB = CreativeTabRegistry.create(Compat.id(MOD_ID, "main"), () -> MicroItemData.make(net.minecraft.world.level.block.Blocks.STONE_BRICKS, 16, 16, 4));
    #endif

    /** The items of the creative tab: the table, the standard shapes of the sample materials (any other size is cut to measure on the table). */
    public static void fillTab(java.util.function.Consumer<net.minecraft.world.item.ItemStack> out) {
        out.accept(new net.minecraft.world.item.ItemStack(TABLE_ITEM.get()));
        out.accept(new net.minecraft.world.item.ItemStack(BLUEPRINT_TABLE_ITEM.get()));
        for (RegistrySupplier<Item> tool : java.util.List.of(CHISEL_FINE, CHISEL_FLAT, CHISEL_GOUGE, CHISEL_SPLITTER, DRILL_THIN, DRILL_WIDE)) out.accept(new net.minecraft.world.item.ItemStack(tool.get()));
        fillMicros(out);
    }

    /** The microblocks of the creative tab. */
    public static void fillMicros(java.util.function.Consumer<net.minecraft.world.item.ItemStack> out) {
        for (Block material : MicroMaterials.samples()) {
            for (String kind : new String[]{"plane", "stick", "cube"}) {
                for (int thickness : MicroItemData.STANDARD_SIZES) {
                    int[] size = MicroItemData.standard(kind, thickness);
                    out.accept(MicroItemData.make(material, size[0], size[1], size[2]));
                }
            }
        }
    }

    private static RegistrySupplier<Item> tool(String name, MicroToolItem.Tool tool) {
        return ITEMS.register(name, () -> new MicroToolItem(tool, tabbed(itemProperties(name)).durability(tool.durability)));
    }

    private Microblocks() {}

    /** The items that are in the creative tab say so in their properties before 1.19.3. */
    private static Item.Properties tabbed(Item.Properties properties) {
        #if MC_VER < V1_19_4
        return properties.tab(TAB);
        #else
        return properties;
        #endif
    }

    private static Item.Properties itemProperties(String name) {
        return new Item.Properties()#if MC_VER >= V1_21_3 .setId(ResourceKey.create(Registries.ITEM, Compat.id(MOD_ID, name))) #endif;
    }

    public static void initializeServer() {
        BLOCKS.register();
        ITEMS.register();
        BLOCK_ENTITIES.register();
        MENUS.register();
        #if MC_VER >= V1_20_1
        TABS.register();
        #endif
        MicroBreaking.register();
        MicroCommand.register();
        MicroBlueprints.register();
        MicroGlue.register();
        MicroSettings.register();
        MicroTools.register();
    }

    public static void initializeClient() {
        AutoTest.register();
        com.akicater.microblocks.client.MicroblocksClient.init();
    }
}

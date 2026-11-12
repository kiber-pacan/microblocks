package com.akicater.microblocks;

#if AUTOTEST == 1

import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiled only with -Pautotest=1 (see autotest.py). A small interpreter for the test file the script puts next to the
 * game (autotest.yml, one step per "- key: value" line), so a test is a file and not code:
 *
 *   - wait: title            the main menu (plus two seconds)
 *   - wait: world            the world is open
 *   - wait: 10s | 40t        a pause (seconds / ticks)
 *   - open-world: TestWorld  opens a saved world (confirmation screens are answered with yes)
 *   - peaceful-world: yes     no mobs and no entities in the world: the game rules that stop them (their names changed in 1.21.11) and a kill of what is there
 *   - command: weather thunder
 *   - screenshot: name
 *   - screen: com.x.SomeScreen    opens a screen class with a no-arg or (Screen) constructor
 *   - call: com.x.Some#method     a static method without arguments
 *   - micro-demo: yes             puts microblocks of every shape into the world next to the player and checks the rules (MicroDemo)
 *
 * Prints AUTOTEST PASSED after the last step, AUTOTEST FAILED <why> when a step does not finish in time. A crash is found by
 * the script in the log / crash reports.
 */
public final class AutoTest {
    private record Step(String key, String value) {}

    private static final List<Step> STEPS = new ArrayList<>();
    private static int ticks;
    private static int index;
    private static int stepTicks;
    private static boolean started;
    private static boolean titleSeen;
    private static boolean failed;
    private static volatile String demoResult;

    private AutoTest() {}

    public static void register() {
        Path file = Path.of("autotest.yml");
        try {
            for (String line : Files.readAllLines(file)) {
                line = line.trim();
                if (!line.startsWith("- ")) continue;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                STEPS.add(new Step(line.substring(2, colon).trim(), line.substring(colon + 1).trim()));
            }
        } catch (Exception e) {
            System.out.println("AUTOTEST FAILED no test file " + file.toAbsolutePath());
        }
        System.out.println("AUTOTEST registered, " + STEPS.size() + " steps");
        if (STEPS.isEmpty()) {
            // a test without steps would pass without having tested anything
            System.out.println("AUTOTEST FAILED the test file has no steps");
            failed = true;
        }
        ClientTickEvent.CLIENT_POST.register(AutoTest::tick);
    }

    private static net.minecraft.client.gui.screens.Screen screen(Minecraft mc) {
        return #if MC_VER >= V26_2 mc.gui.screen() #else mc.screen #endif;
    }

    private static void setScreen(Minecraft mc, net.minecraft.client.gui.screens.Screen screen) {
        mc #if MC_VER >= V26_2 .gui #endif .setScreen(screen);
    }

    private static void run(IntegratedServer server, String command) {
        #if MC_VER < V1_21
        // before 1.20.5 the data of an item is the NBT of its tag: "item[custom_data={...}]" is "item{...}"
        command = command.replaceAll("\\[custom_data=(.*)\\]$", "$1");
        final String fixed = command;
        #else
        final String fixed = command;
        #endif
        server.execute(() -> {
            #if MC_VER <= V1_18_2
            server.getCommands().performCommand(server.createCommandSourceStack(), fixed);
            #else
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), fixed);
            #endif
        });
    }

    private static void confirm(Minecraft mc) {
        var screen = screen(mc);
        if (screen == null) return;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractButton button && button.active) {
                String text = button.getMessage().getString().toLowerCase();
                if (text.contains("proceed") || text.contains("yes") || text.contains("play") || text.contains("load")
                        || text.contains("confirm") || text.contains("continue") || text.contains("backup")) {
                    System.out.println("AUTOTEST confirming: " + text);
                    #if MC_VER >= V1_21_9
                    button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                    #else
                    button.onPress();
                    #endif
                    return;
                }
            }
        }
    }

    // several tests in one start of the game: a "- test: name" step begins each of them, at a clean place of the world. A test that fails
    // is written down and the next one goes on; the run fails at the end when one of them did.
    private static String currentTest;
    private static int testNumber;
    private static final List<String> failedTests = new ArrayList<>();

    private static void finish(Minecraft mc, boolean ok, String why) {
        if (currentTest != null) {
            System.out.println("AUTOTEST TEST " + currentTest + (ok ? " PASSED" : " FAILED " + why));
            if (!ok) {
                failedTests.add(currentTest);
                for (int i = index + 1; i < STEPS.size(); i++) {
                    if (!STEPS.get(i).key().equals("test")) continue;
                    index = i;       // the next test: this one is over
                    stepTicks = 0;
                    started = false;
                    currentTest = null;
                    if (mc.level != null) setScreen(mc, null);
                    return;
                }
            }
            if (!failedTests.isEmpty()) {
                ok = false;
                why = "tests: " + String.join(", ", failedTests);
            }
        }
        System.out.println(ok ? "AUTOTEST PASSED" : "AUTOTEST FAILED " + why);
        mc.stop();
    }

    private static void next() {
        index++;
        stepTicks = 0;
        started = false;
    }

    private static void openWorld(Minecraft mc, String name) {
        #if MC_VER <= V1_18_2
        mc.loadLevel(name);
        #elif MC_VER <= V1_20_1
        mc.createWorldOpenFlows().loadLevel(mc.screen, name);
        #elif MC_VER == V1_20_4
        mc.createWorldOpenFlows().checkForBackupAndLoad(name, () -> {});
        #else
        mc.createWorldOpenFlows().openWorld(name, () -> {});
        #endif
    }

    private static int MicroGeometry_quads(java.util.List<Part> parts, net.minecraft.core.BlockPos at) {
        return com.akicater.microblocks.client.MicroGeometry.quads(MicroMesh.build(parts, at)).size();
    }

    private static final java.util.List<net.minecraft.core.BlockPos> stressList = new java.util.ArrayList<>();
    private static net.minecraft.core.BlockPos stressBase = net.minecraft.core.BlockPos.ZERO;
    private static final java.util.List<Integer> stressFps = new java.util.ArrayList<>();
    private static long stressLast;
    private static volatile long stressServerWorst;
    private static volatile boolean glueOk;

    private static net.minecraft.core.BlockPos toolBlock = net.minecraft.core.BlockPos.ZERO;

    private static void screenshot(Minecraft mc, String name) {
        #if MC_VER >= V26_2
        net.minecraft.client.Screenshot.grab(mc, false);
        #elif MC_VER >= V1_21_6
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, component -> {});
        #else
        net.minecraft.client.Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), component -> {});
        #endif
    }

    private static int fps(Minecraft mc) {
        #if MC_VER >= V1_19_4
        return mc.getFps();
        #else
        try { return Integer.parseInt(mc.fpsString.split(" ")[0]); } catch (RuntimeException e) { return 0; }
        #endif
    }

    private static long frameNs(Minecraft mc) {
        #if MC_VER >= V1_19_4
        return mc.getFrameTimeNs();
        #else
        return 0;
        #endif
    }

    private static int duration(String value) {
        try {
            if (value.endsWith("ticks")) return Integer.parseInt(value.substring(0, value.length() - 5).trim());
            if (value.endsWith("t")) return Integer.parseInt(value.substring(0, value.length() - 1).trim());
            if (value.endsWith("s")) return (int) (Double.parseDouble(value.substring(0, value.length() - 1).trim()) * 20);
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static volatile long beat;
    private static Thread watchdog;

    /** Says where the render thread is when it stands still for longer than 150 ms: the place of a freeze, by name. */
    private static void watch() {
        beat = System.nanoTime();
        if (watchdog != null) return;
        final Thread render = Thread.currentThread();
        watchdog = new Thread(() -> {
            long told = 0;
            while (true) {
                try { Thread.sleep(25); } catch (InterruptedException e) { return; }
                long b = beat;
                if (System.nanoTime() - b < 150_000_000L || b == told) continue;
                told = b;
                StringBuilder text = new StringBuilder("AUTOTEST stall of the render thread at:");
                StackTraceElement[] stack = render.getStackTrace();
                for (int i = 0; i < stack.length && i < 28; i++) text.append("\n    ").append(stack[i]);
                System.out.println(text);
            }
        }, "autotest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void tick(Minecraft mc) {
        watch();
        ticks++;
        if (failed) {
            mc.stop();
            return;
        }
        var screen = screen(mc);

        // NeoForge shows its warning list (a dev-only @OnlyIn notice of Architectury) before the title screen: close it
        if (!titleSeen && ticks % 20 == 0 && screen != null && screen.getClass().getSimpleName().equals("LoadingErrorScreen")) {
            setScreen(mc, new TitleScreen());
        }
        // a screen of another mod in front of the title (the "new update" question of Distant Horizons ...): out of the way
        if (ticks % 20 == 0 && screen != null && !(screen instanceof TitleScreen) && mc.level == null
                && screen.getClass().getName().contains("distanthorizons")) {
            System.out.println("AUTOTEST closing " + screen.getClass().getName());
            setScreen(mc, new TitleScreen());
        }
        if (screen instanceof TitleScreen) titleSeen = true;

        if (index >= STEPS.size()) {
            finish(mc, true, "");
            return;
        }

        Step step = STEPS.get(index);
        stepTicks++;
        IntegratedServer server = mc.getSingleplayerServer();
        boolean inWorld = mc.level != null && mc.player != null && server != null;

        // no step may take longer than 150 seconds
        if (stepTicks > 20 * 150) {
            finish(mc, false, "step " + (index + 1) + " (" + step.key() + ": " + step.value() + ") did not finish in 150 s, screen: " + screen);
            return;
        }

        switch (step.key()) {
            case "test" -> {
                if (stepTicks == 1) {
                    if (currentTest != null) System.out.println("AUTOTEST TEST " + currentTest + " PASSED");
                    currentTest = step.value();
                    testNumber++;
                    System.out.println("AUTOTEST TEST " + currentTest + " BEGIN");
                    if (!inWorld) { next(); return; }
                    // a place of its own, far from what the tests before built, with nothing in the hands and no screen open
                    if (screen != null) setScreen(mc, null);
                    run(server, "gamemode creative @p");
                    run(server, "clear @p");
                    run(server, "tp @p " + testNumber * 400 + ".0 -60 0.0 0 0");
                } else if (stepTicks > 60) {
                    next();      // three seconds for the chunks of the new place
                }
            }
            case "wait" -> {
                String value = step.value();
                if (value.equals("title")) {
                    if (inWorld || screen instanceof TitleScreen && stepTicks > 40) next();
                } else if (value.equals("world")) {
                    // a confirmation on the way into the world (experimental settings, missing data pack, backup): say yes
                    if (inWorld) next();
                    else if (stepTicks % 40 == 0 && screen != null && !(screen instanceof TitleScreen)) confirm(mc);
                } else if (stepTicks >= duration(value)) {
                    next();
                }
            }
            case "open-world" -> {
                if (inWorld) { next(); return; }      // a later test of the same start: the world is open
                System.out.println("AUTOTEST opening " + step.value());
                openWorld(mc, step.value());
                next();
            }
            case "command" -> {
                if (server == null) {
                    finish(mc, false, "command outside a world: " + step.value());
                    return;
                }
                System.out.println("AUTOTEST command " + step.value());
                // in a suite "~" is the place of the player (the clean place of this test), not the origin of the world
                run(server, currentTest != null && testNumber > 1 ? "execute at @p run " + step.value() : step.value());
                next();
            }
            case "screenshot" -> {
                String shot = currentTest == null ? step.value() : currentTest + "__" + step.value();
                screenshot(mc, shot);
                System.out.println("AUTOTEST screenshot " + shot);
                next();
            }
            case "peaceful-world" -> {
                if (server == null) {
                    finish(mc, false, "peaceful-world outside a world");
                    return;
                }
                // The game rules that stop mobs and other things from appearing. 1.21.11 renamed all of them (snake_case, and some
                // were split), the names are those of the game's own jars. A name a version does not know is an error in the chat only.
                String[] rules = new String[]{
                        #if MC_VER >= V1_21_11
                        "spawn_mobs", "spawn_monsters", "spawn_patrols", "spawn_phantoms", "spawn_wandering_trader", "spawn_wardens",
                        "advance_time", "advance_weather"
                        #else
                        "doMobSpawning", "doPatrolSpawning", "doTraderSpawning", "doInsomnia", "doWardenSpawning",
                        "doDaylightCycle", "doWeatherCycle"
                        #endif
                };
                for (String rule : rules) run(server, "gamerule " + rule + " false");
                // what the world was generated with (animals, villagers ...) is not stopped by a rule
                run(server, "kill @e[type=!minecraft:player]");
                System.out.println("AUTOTEST peaceful world");
                next();
            }
            case "screen" -> {
                try {
                    Class<?> type = Class.forName(step.value());
                    Object created;
                    try {
                        created = type.getConstructor(net.minecraft.client.gui.screens.Screen.class).newInstance((Object) null);
                    } catch (NoSuchMethodException e) {
                        created = type.getConstructor().newInstance();
                    }
                    setScreen(mc, (net.minecraft.client.gui.screens.Screen) created);
                    next();
                } catch (Exception e) {
                    finish(mc, false, "screen " + step.value() + ": " + e);
                }
            }
            case "call" -> {
                try {
                    String[] parts = step.value().split("#");
                    Class.forName(parts[0]).getMethod(parts[1]).invoke(null);
                    next();
                } catch (Exception e) {
                    finish(mc, false, "call " + step.value() + ": " + e);
                }
            }
            case "micro-demo" -> {
                if (server == null || mc.player == null) {
                    finish(mc, false, "micro-demo outside a world");
                    return;
                }
                if (!started) {
                    started = true;
                    demoResult = null;
                    final net.minecraft.core.BlockPos where = mc.player.blockPosition();
                    final String[] names = step.value().equals("all") ? MicroDemo.NAMES.toArray(new String[0]) : new String[]{step.value()};
                    server.execute(() -> {
                        String result = "ok";
                        for (String name : names) {
                            String one = MicroShowcase.NAMES.contains(name) ? MicroShowcase.run(server.overworld(), where, name) : MicroDemo.run(server.overworld(), where, name);
                            if (!one.equals("ok")) { result = one; break; }
                        }
                        demoResult = result;
                    });
                } else if (demoResult != null) {
                    if (demoResult.equals("ok")) {
                        System.out.println("AUTOTEST micro demo ok: " + step.value());
                        next();
                    } else {
                        finish(mc, false, "micro-demo: " + demoResult);
                    }
                }
            }
            case "micro-place" -> {
                if (server == null) {
                    finish(mc, false, "micro-place outside a world");
                    return;
                }
                if (!started) {
                    started = true;
                    demoResult = null;
                    final net.minecraft.core.BlockPos where = mc.player.blockPosition();
                    server.execute(() -> demoResult = MicroPlaceTest.run(server.overworld(), where));
                } else if (demoResult != null) {
                    if (demoResult.equals("ok")) next();
                    else finish(mc, false, "micro-place: " + demoResult);
                }
            }
            case "micro-live" -> {
                // a part put in the world the way a player does it (a block space that was empty and drawn already): it has to show up at once
                if (server == null) {
                    finish(mc, false, "micro-live outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    MicroPlacement.place(level, new MicroPlacement.Result(where, Part.of(new int[]{0, 0, 0}, new int[]{16, 3, 16}, net.minecraft.world.level.block.Blocks.BRICKS)));
                    MicroPlacement.place(level, new MicroPlacement.Result(where, Part.of(new int[]{5, 5, 7}, new int[]{11, 11, 10}, net.minecraft.world.level.block.Blocks.OAK_PLANKS)));
                    // a plank box across two block spaces, placed freely (Ctrl): one continuous texture
                    MicroPlacement.place(level, MicroPlacement.computeFree(level, where.below(), net.minecraft.core.Direction.UP,
                            new net.minecraft.world.phys.Vec3(where.getX() + 0.9, where.getY(), where.getZ() - 0.5), net.minecraft.world.level.block.Blocks.OAK_PLANKS, new int[]{16, 6, 8}));
                    // a log cut across x (4 thick), put down in three ways: the rings go where the cut piece has them
                    for (int i = 0; i < 3; i++) {
                        MicroPlacement.place(level, new MicroPlacement.Result(where.offset(-3 + 3 * i, 0, 2), Part.of(new int[]{0, 0, 0}, new int[]{4, 16, 16}, net.minecraft.world.level.block.Blocks.OAK_LOG, i == 0 ? 0 : i == 1 ? 2 : 3)));
                    }
                });
                next();
            }
            case "micro-multi" -> {
                // several parts in one block space, and the player looks at the first post
                if (server == null) {
                    finish(mc, false, "micro-multi outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    MicroPlacement.place(level, new MicroPlacement.Result(where, Part.of(new int[]{0, 0, 0}, new int[]{16, 3, 16}, net.minecraft.world.level.block.Blocks.BRICKS)));
                    MicroPlacement.place(level, new MicroPlacement.Result(where, Part.of(new int[]{2, 3, 2}, new int[]{6, 12, 6}, net.minecraft.world.level.block.Blocks.OAK_PLANKS)));
                    MicroPlacement.place(level, new MicroPlacement.Result(where, Part.of(new int[]{10, 3, 10}, new int[]{14, 12, 14}, net.minecraft.world.level.block.Blocks.STONE)));
                    run(server, "execute as @p at @s run tp @s ~ ~ ~ -5 20");
                });
                next();
            }
            case "micro-break" -> {
                // the player breaks what they look at (as a client does: the game predicts it, the server answers)
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                mc.gameMode.startDestroyBlock(where, net.minecraft.core.Direction.NORTH);
                next();
            }
            case "micro-corner" -> {
                // a stone plate across four block spaces, put down freely
                if (server == null) {
                    finish(mc, false, "micro-corner outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    MicroPlacement.place(level, MicroPlacement.computeFree(level, where.below(), net.minecraft.core.Direction.UP,
                            new net.minecraft.world.phys.Vec3(where.getX() + 3.0, where.getY(), where.getZ() + 3.0), net.minecraft.world.level.block.Blocks.STONE, new int[]{16, 6, 16}));
                    run(server, "execute as @p at @s run tp @s ~ ~ ~ -45 35");
                });
                next();
            }
            case "micro-halves" -> {
                // a grass block cut across y: the lower half, the upper half (each keeps its part of the texture, here put at the bottom of its space), and both together
                if (server == null) {
                    finish(mc, false, "micro-halves outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    net.minecraft.world.level.block.Block grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK;
                    MicroPlacement.place(level, new MicroPlacement.Result(where.offset(-2, 0, 0), Part.of(new int[]{0, 0, 0}, new int[]{16, 8, 16}, grass).withInner(1 << net.minecraft.core.Direction.UP.ordinal())));
                    MicroPlacement.place(level, new MicroPlacement.Result(where, Part.of(new int[]{0, 0, 0}, new int[]{16, 8, 16}, grass).withShift(0, 8, 0).withInner(1 << net.minecraft.core.Direction.DOWN.ordinal())));
                    MicroPlacement.place(level, new MicroPlacement.Result(where.offset(2, 0, 0), Part.of(new int[]{0, 0, 0}, new int[]{16, 8, 16}, grass).withInner(1 << net.minecraft.core.Direction.UP.ordinal())));
                    MicroPlacement.place(level, new MicroPlacement.Result(where.offset(2, 0, 0), Part.of(new int[]{0, 8, 0}, new int[]{16, 16, 16}, grass).withInner(1 << net.minecraft.core.Direction.DOWN.ordinal())));
                    run(server, "execute as @p at @s run tp @s ~ ~ ~ 0 20");
                });
                next();
            }
            case "micro-tools" -> {
                // the chisels and the drills on blocks of stone and glass; the player gets nothing in creative, so the survival kind of work is checked in the numbers
                if (server == null) {
                    finish(mc, false, "micro-tools outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    MicroToolItem.Tool[] tools = MicroToolItem.Tool.values();
                    for (int i = 0; i < tools.length; i++) {
                        net.minecraft.core.BlockPos block = where.offset(-3 + i, 0, 0);
                        boolean tinted = "grass".equals(step.value());      // the materials that take the colour of the biome
                        level.setBlock(block, (i % 2 == 0 ? (tinted ? net.minecraft.world.level.block.Blocks.GRASS_BLOCK : net.minecraft.world.level.block.Blocks.STONE) : (tinted ? net.minecraft.world.level.block.Blocks.OAK_LEAVES : net.minecraft.world.level.block.Blocks.OAK_PLANKS)).defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        MicroTools.carve(player, new ItemStack(Microblocks.CHISEL_FINE.get()), tools[i], block, net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(block.getX() + 0.5, block.getY() + 1, block.getZ() + 0.5));
                    }
                    run(server, "execute as @p at @s run tp @s ~ ~ ~ 0 25");
                });
                next();
            }
            case "micro-toolsetup" -> {
                // a block of stone and a flat chisel in the hand
                if (server == null) {
                    finish(mc, false, "micro-toolsetup outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    server.overworld().setBlock(where, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                    net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    player.getInventory().setItem(0, new ItemStack(Microblocks.DRILL_WIDE.get()));
                    #if MC_VER >= V1_21_5
                    player.getInventory().setSelectedSlot(0);
                    #else
                    player.getInventory().selected = 0;
                    #endif
                    toolBlock = where;
                    run(server, "tp @p " + (where.getX() + 0.5) + " " + (where.getY() + 1.6) + " " + (where.getZ() - 1.2) + " 0 45");      // two blocks away, looking down at it
                });
                next();
            }
            case "micro-toolclick" -> {
                // the click of the tool as the client makes it: it goes to the server in numbers, the server works the block
                com.akicater.microblocks.client.MicroToolClient.send(toolBlock, net.minecraft.core.Direction.UP, 8, 15, 8);
                next();
            }
            case "micro-toolcheck" -> {
                var be = mc.level.getBlockEntity(toolBlock);
                System.out.println("AUTOTEST toolcheck " + (be instanceof MicroBlockEntity m ? m.parts().size() + " parts, volume " + m.parts().stream().mapToInt(Part::volume).sum() + ", quads " + m.mesh().quads.size() + ", mask " + (m.parts().get(0).mask != null) + ", box " + m.parts().get(0).minX + "," + m.parts().get(0).minY + "," + m.parts().get(0).minZ + "-" + m.parts().get(0).maxX + "," + m.parts().get(0).maxY + "," + m.parts().get(0).maxZ : "no microblocks"));
                next();
            }
            case "micro-glue" -> {
                // two blocks of stone, one of them carved, joined with the glue into one prefab; it is taken apart into a prefab again, put down somewhere else, and must hold the same cells
                if (server == null) {
                    finish(mc, false, "micro-glue outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    net.minecraft.core.BlockPos a = where, b = where.offset(1, 0, 0);
                    level.setBlock(a, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                    level.setBlock(b, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                    MicroTools.carve(player, new ItemStack(Microblocks.CHISEL_FINE.get()), MicroToolItem.Tool.GOUGE, a, net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(a.getX() + 0.5, a.getY() + 1, a.getZ() + 0.5));
                    int before = ((MicroBlockEntity) level.getBlockEntity(a)).parts().get(0).volume() + 4096;
                    int joined = MicroGlue.join(level, player, new int[]{a.getX() * 16, a.getY() * 16, a.getZ() * 16}, new int[]{b.getX() * 16 + 16, b.getY() * 16 + 16, b.getZ() * 16 + 16});
                    Part.Group g = level.getBlockEntity(a) instanceof MicroBlockEntity be && !be.parts().isEmpty() ? be.parts().get(0).group : null;
                    boolean ok = joined == 2 && g != null && g.statue() && level.getBlockEntity(b) instanceof MicroBlockEntity bb && bb.parts().get(0).group != null && bb.parts().get(0).group.id() == g.id();
                    java.util.List<Part> prefab = ok ? MicroStatue.rebuild(level, g) : java.util.List.of();
                    int volume = 0;
                    boolean holes = false;
                    for (Part p : prefab) { volume += p.volume(); holes |= p.mask != null; }
                    for (Part p : prefab) System.out.println("AUTOTEST glue part " + p.minX + "," + p.minY + "," + p.minZ + " .. " + p.maxX + "," + p.maxY + "," + p.maxZ + " mask " + (p.mask != null) + " cells " + p.volume() + " shift " + p.tx + "," + p.ty + "," + p.tz);
                    // through an item and back, then put down two blocks higher
                    ItemStack item = new ItemStack(Microblocks.PREFAB.get());
                    PrefabData.write(item, prefab);
                    java.util.List<Part> read = PrefabData.read(item);
                    MicroSettings.State state = MicroSettings.of(player);
                    int mode = state.mode;
                    state.mode = 0;
                    net.minecraft.core.BlockPos up = a.above(3);
                    level.setBlock(up.below(), net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                    MicroStatue.Plan plan = MicroStatue.plan(level, up.below(), net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(up.getX() + 0.5, up.getY(), up.getZ() + 0.5), read, state);
                    state.mode = mode;
                    if (plan != null) for (MicroPlacement.Result piece : plan.pieces()) System.out.println("AUTOTEST glue piece at " + piece.pos() + " " + piece.part().minX + "," + piece.part().minY + "," + piece.part().minZ + " .. " + piece.part().maxX + "," + piece.part().maxY + "," + piece.part().maxZ + " cells " + piece.part().volume() + " there " + level.getBlockState(piece.pos()));
                    boolean placed = plan != null && MicroStatue.place(level, plan, false);
                    int again = 0;
                    if (placed) for (MicroPlacement.Result piece : plan.pieces()) again += piece.part().volume();
                    System.out.println("AUTOTEST glue joined " + joined + ", prefab parts " + prefab.size() + ", cells " + volume + " of " + before + ", holes kept " + holes + ", read back " + read.size() + " parts, plan " + (plan == null ? "none" : plan.pieces().size() + " pieces") + ", put down again " + placed + " with " + again + " cells");
                    glueOk = ok && volume == before && holes && placed && again == before;
                    run(server, "execute as @p at @s run tp @s ~ ~2 ~-2 0 25");
                });
                next();
            }
            case "micro-glue-check" -> {
                if (!glueOk) { finish(mc, false, "the glue did not keep the cells of what it joined"); return; }
                next();
            }
            case "micro-rebuild" -> {
                #if MC_VER >= V26_2
                mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
                #else
                mc.levelRenderer.allChanged();
                #endif
                next();
            }
            case "micro-border" -> {
                // two blocks of stone side by side and a gouge on the border between them: the hole reaches into both, and no wall may be left inside
                if (server == null) {
                    finish(mc, false, "micro-border outside a world");
                    return;
                }
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                server.execute(() -> {
                    net.minecraft.server.level.ServerLevel level = server.overworld();
                    net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) level.setBlock(where.offset(x, y, 0), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                    MicroTools.carve(player, new ItemStack(Microblocks.CHISEL_FINE.get()), MicroToolItem.Tool.GOUGE, where.above(), net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(where.getX() + 1.0, where.getY() + 2.0, where.getZ() + 0.5));
                    MicroTools.carve(player, new ItemStack(Microblocks.CHISEL_FINE.get()), MicroToolItem.Tool.DRILL_WIDE, where, net.minecraft.core.Direction.EAST, new net.minecraft.world.phys.Vec3(where.getX() + 1.0, where.getY() + 0.5, where.getZ() + 0.5));
                    run(server, "tp @p " + (where.getX() + 1.0) + " " + (where.getY() + 4.6) + " " + (where.getZ() - 0.5) + " 0 60");
                });
                next();
            }
            case "micro-bench" -> {
                // how long the work of a battered block takes: a block of stone with 40 spheres taken out of it, and the same with scattered cells
                java.util.Random random = new java.util.Random(5);
                boolean[] spheres = new boolean[4096], noise = new boolean[4096];
                java.util.Arrays.fill(spheres, true);
                for (int n = 0; n < 40; n++) {
                    int cx = random.nextInt(16), cy = random.nextInt(16), cz = random.nextInt(16);
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++)
                        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz) <= 5) spheres[x + 16 * (y + 16 * z)] = false;
                }
                for (int i = 0; i < 4096; i++) noise[i] = random.nextDouble() > 0.3;
                for (int scenario = 0; scenario < 2; scenario++) {
                    Part part = new Part(0, 0, 0, 16, 16, 16, net.minecraft.world.level.block.Blocks.STONE).withMask(Part.maskOf(scenario == 0 ? spheres : noise));
                    java.util.List<Part> parts = java.util.List.of(part);
                    net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(0, -60, 3);
                    for (int i = 0; i < 30; i++) { MicroMesh.build(parts, at); MicroGeometry_quads(parts, at); }
                    int rounds = 200;
                    long t0 = System.nanoTime();
                    MicroMesh mesh = null;
                    for (int i = 0; i < rounds; i++) mesh = MicroMesh.build(parts, at);
                    long t1 = System.nanoTime();
                    int quads = 0;
                    for (int i = 0; i < rounds; i++) quads = MicroGeometry_quads(parts, at);
                    long t2 = System.nanoTime();
                    long s0 = System.nanoTime();
                    int boxes = MicroPlacement.boxesOf(part).size();
                    long s1 = System.nanoTime();
                    Part fresh = part.withMask(part.mask);
                    long s2 = System.nanoTime();
                    net.minecraft.world.phys.shapes.VoxelShape shape = fresh.shape();
                    long s3 = System.nanoTime();
                    System.out.println("AUTOTEST bench " + (scenario == 0 ? "spheres" : "noise") + ": mesh " + (t1 - t0) / rounds / 1000 + " us, mesh+geometry " + (t2 - t1) / rounds / 1000 + " us, quads " + mesh.quads.size()
                            + ", boxes " + boxes + " (" + (s1 - s0) / 1000 + " us), shape " + (s3 - s2) / 1000 + " us");
                }
                if (server != null) {
                    final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        level.setBlock(where, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        java.util.Random r = new java.util.Random(9);
                        ItemStack tool = new ItemStack(Microblocks.CHISEL_FINE.get());
                        long t0 = System.nanoTime();
                        int uses = 150;
                        for (int i = 0; i < uses; i++) {
                            MicroToolItem.Tool kind = MicroToolItem.Tool.values()[r.nextInt(3)];      // fine, flat, gouge
                            MicroTools.carve(player, tool, kind, where, net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(where.getX() + r.nextDouble(), where.getY() + 1.0, where.getZ() + r.nextDouble()));
                        }
                        long t1 = System.nanoTime();
                        if (level.getBlockEntity(where) instanceof MicroBlockEntity be) {
                            Part p = be.parts().isEmpty() ? null : be.parts().get(0);
                            long w0 = System.nanoTime();
                            int rounds = 100;
                            for (int i = 0; i < rounds; i++) be.getUpdatePacket();
                            long w1 = System.nanoTime();
                            System.out.println("AUTOTEST bench server: " + uses + " uses " + (t1 - t0) / uses / 1000 + " us each, parts " + be.parts().size() + ", volume " + be.parts().stream().mapToInt(Part::volume).sum()
                                    + ", update packet " + (w1 - w0) / rounds / 1000 + " us");
                        }
                    });
                }
                next();
            }
            case "micro-stress" -> {
                // a scene of very many badly battered blocks put at random, overlapping and across each other: how long it takes to make and how the frames hold
                if (server == null) {
                    finish(mc, false, "micro-stress outside a world");
                    return;
                }
                if (stepTicks == 1) stressBase = mc.player.blockPosition().offset(0, 0, 6);      // fixed: the player moves afterwards
                final net.minecraft.core.BlockPos base = stressBase;
                if (stepTicks == 1) {
                    stressFps.clear();
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        java.util.Random r = new java.util.Random(77);
                        net.minecraft.world.level.block.Block[] materials = {net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.OAK_PLANKS, net.minecraft.world.level.block.Blocks.GLASS,
                                net.minecraft.world.level.block.Blocks.BRICKS, net.minecraft.world.level.block.Blocks.DIRT, net.minecraft.world.level.block.Blocks.SANDSTONE, net.minecraft.world.level.block.Blocks.GRASS_BLOCK};
                        ItemStack tool = new ItemStack(Microblocks.CHISEL_FINE.get());
                        long t0 = System.nanoTime();
                        int blocks = 0, uses = 0;
                        for (int i = 0; i < 220; i++) {
                            net.minecraft.core.BlockPos pos = base.offset(r.nextInt(14) - 7, r.nextInt(3), r.nextInt(14));
                            if (level.getBlockState(pos).isAir()) level.setBlock(pos, materials[r.nextInt(materials.length)].defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                            blocks++;
                            int hits = 250 + r.nextInt(250);      // many small holes: as many faces as a block can have
                            for (int h = 0; h < hits; h++) {
                                MicroToolItem.Tool kind = MicroToolItem.Tool.values()[r.nextInt(2)];      // fine and flat
                                net.minecraft.core.Direction face = net.minecraft.core.Direction.values()[r.nextInt(6)];
                                MicroTools.carve(player, tool, kind, pos, face, new net.minecraft.world.phys.Vec3(pos.getX() + r.nextDouble(), pos.getY() + r.nextDouble(), pos.getZ() + r.nextDouble()));
                                uses++;
                            }
                            // microblocks put in, across and over what is there, in every mode
                            net.minecraft.world.level.block.Block m = materials[r.nextInt(materials.length)];
                            int[] dims = {1 + r.nextInt(16), 1 + r.nextInt(16), 1 + r.nextInt(16)};
                            MicroPlacement.Result put = MicroPlacement.computeFree(level, pos, net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(pos.getX() + r.nextDouble(), pos.getY() + 1, pos.getZ() + r.nextDouble()), m, dims, true);
                            if (put != null) MicroPlacement.place(level, put, true, r.nextBoolean());
                        }
                        long t1 = System.nanoTime();
                        int parts = 0, volume = 0, holders = 0;
                        for (int x = -8; x < 8; x++) for (int y = 0; y < 5; y++) for (int z = -1; z < 16; z++) {
                            if (level.getBlockEntity(base.offset(x, y, z)) instanceof MicroBlockEntity be) { holders++; parts += be.parts().size(); for (Part q : be.parts()) volume += q.volume(); }
                        }
                        System.out.println("AUTOTEST stress scene: " + blocks + " blocks, " + uses + " tool uses, " + (t1 - t0) / 1000000 + " ms (" + (t1 - t0) / Math.max(1, uses) / 1000 + " us per use); " + holders + " holders, " + parts + " parts, volume " + volume);
                        run(server, "tp @p " + (base.getX() + 0.5) + " " + (base.getY() + 7.0) + " " + (base.getZ() - 10.0) + " 0 35");
                    });
                }
                // phase 1: the frames while nothing changes (from tick 40, after the chunks were made), phase 2: while blocks are worked every tick
                if (stepTicks >= 40) stressFps.add(fps(mc));
                if (stepTicks == 120) {
                    int sum = 0, low = Integer.MAX_VALUE;
                    for (int f : stressFps) { sum += f; low = Math.min(low, f); }
                    int quadCount = 0;
                    for (int x = -8; x < 8; x++) for (int y = 0; y < 5; y++) for (int z = -1; z < 16; z++)
                        if (mc.level.getBlockEntity(base.offset(x, y, z)) instanceof MicroBlockEntity be) quadCount += be.mesh().quads.size();
                    System.out.println("AUTOTEST stress faces: " + quadCount + " quads in the meshes");
                    com.akicater.microblocks.client.MicroBlockQuads provider = new com.akicater.microblocks.client.MicroBlockQuads();
                    long all = 0, worstOne = 0; int made = 0;
                    for (int x = -8; x < 8; x++) for (int y = 0; y < 5; y++) for (int z = -1; z < 16; z++) {
                        net.minecraft.core.BlockPos at = base.offset(x, y, z);
                        if (!(mc.level.getBlockEntity(at) instanceof MicroBlockEntity be)) continue;
                        be.renderCache = null;
                        long q0 = System.nanoTime();
                        provider.quads(mc.level, at, mc.level.getBlockState(at));
                        long took = System.nanoTime() - q0;
                        all += took; worstOne = Math.max(worstOne, took); made++;
                    }
                    System.out.println("AUTOTEST stress provider: " + made + " blocks, " + all / 1000000 + " ms in all, " + all / Math.max(1, made) / 1000 + " us each, worst " + worstOne / 1000 + " us");
                    System.out.println("AUTOTEST stress still: " + stressFps.size() + " samples, fps avg " + sum / Math.max(1, stressFps.size()) + ", lowest " + low);
                    stressFps.clear();
                }
                if (stepTicks == 121) { MicroBlockEntity.LOAD_NS.set(0); MicroBlockEntity.LOAD_COUNT.set(0); MicroBlockEntity.MESH_NS.set(0); MicroBlockEntity.MESH_COUNT.set(0); }
                if (stepTicks > 120 && stepTicks <= 260) {
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        java.util.Random r = new java.util.Random(stepTicks * 31L);
                        for (int i = 0; i < 6; i++) {
                            net.minecraft.core.BlockPos pos = base.offset(r.nextInt(14) - 7, r.nextInt(3), r.nextInt(14));
                            MicroTools.carve(player, new ItemStack(Microblocks.CHISEL_FINE.get()), MicroToolItem.Tool.values()[r.nextInt(6)], pos, net.minecraft.core.Direction.values()[r.nextInt(6)],
                                    new net.minecraft.world.phys.Vec3(pos.getX() + r.nextDouble(), pos.getY() + r.nextDouble(), pos.getZ() + r.nextDouble()));
                        }
                    });
                    stressFps.add(fps(mc));
                }
                if (stepTicks == 261) {
                    int sum = 0, low = Integer.MAX_VALUE;
                    for (int f : stressFps) { sum += f; low = Math.min(low, f); }
                    System.out.println("AUTOTEST stress work: main thread read " + MicroBlockEntity.LOAD_COUNT.get() + " updates in " + MicroBlockEntity.LOAD_NS.get() / 1000000 + " ms, chunk threads made " + MicroBlockEntity.MESH_COUNT.get() + " meshes in " + MicroBlockEntity.MESH_NS.get() / 1000000 + " ms");
                    System.out.println("AUTOTEST stress churn: " + stressFps.size() + " samples, fps avg " + sum / Math.max(1, stressFps.size()) + ", lowest " + low);
                    run(server, "tp @p " + (base.getX() + 0.5) + " " + (base.getY() + 4.0) + " " + (base.getZ() - 4.0) + " 0 30");
                    screenshot(mc, "stress");
                    next();
                }
            }
            case "micro-stress2" -> {
                // many battered blocks put down as statues at random (as a prefab of a battered block is), then broken one after another: the time of every break
                if (server == null) {
                    finish(mc, false, "micro-stress2 outside a world");
                    return;
                }
                if (stepTicks == 1) stressBase = mc.player.blockPosition().offset(0, 0, 6);
                final net.minecraft.core.BlockPos base = stressBase;
                if (stepTicks == 1) {
                    server.execute(() -> {
                        net.minecraft.server.level.ServerLevel level = server.overworld();
                        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        java.util.Random r = new java.util.Random(11);
                        // one battered block, as the parts of a prefab
                        net.minecraft.core.BlockPos source = base.offset(-12, 0, 0);
                        level.setBlock(source, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        ItemStack tool = new ItemStack(Microblocks.CHISEL_FINE.get());
                        for (int h = 0; h < 400; h++)
                            MicroTools.carve(player, tool, MicroToolItem.Tool.values()[r.nextInt(2)], source, net.minecraft.core.Direction.values()[r.nextInt(6)], new net.minecraft.world.phys.Vec3(source.getX() + r.nextDouble(), source.getY() + r.nextDouble(), source.getZ() + r.nextDouble()));
                        java.util.List<Part> prefab = new java.util.ArrayList<>(((MicroBlockEntity) level.getBlockEntity(source)).parts());
                        level.setBlock(source, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                        MicroSettings.State state = MicroSettings.of(player);
                        long t0 = System.nanoTime();
                        int placed = 0;
                        // "replace": the statues go into each other (the overlap of the wheel), many of them through every block space
                        final boolean overlap = step.value().equals("replace");
                        final boolean before = state.replace;
                        state.replace = overlap;
                        for (int i = 0; i < 200; i++) {
                            net.minecraft.core.BlockPos at = overlap ? base.offset(r.nextInt(5) - 2, r.nextInt(2), r.nextInt(5)) : base.offset(r.nextInt(16) - 8, r.nextInt(3), r.nextInt(16));
                            MicroStatue.Plan plan = MicroStatue.plan(level, at, net.minecraft.core.Direction.UP, new net.minecraft.world.phys.Vec3(at.getX() + r.nextDouble(), at.getY() + 1, at.getZ() + r.nextDouble()), prefab, state);
                            if (plan != null && MicroStatue.place(level, plan, true)) placed++;
                        }
                        state.replace = before;
                        long t1 = System.nanoTime();
                        System.out.println("AUTOTEST stress2 placed " + placed + " statues of a block with " + prefab.get(0).volume() + " cells each in " + (t1 - t0) / 1000000 + " ms");
                    });
                }
                // the client breaks them one a tick (creative: at once), and the longest frames are kept
                if (stepTicks == 80) {
                    stressList.clear();
                    for (int x = -10; x < 10; x++) for (int y = -1; y < 6; y++) for (int z = -2; z < 20; z++) if (mc.level.getBlockState(base.offset(x, y, z)).getBlock() instanceof MicroBlock) stressList.add(base.offset(x, y, z));
                    stressFps.clear();
                    System.out.println("AUTOTEST stress2 blocks to break: " + stressList.size());
                }
                if (step.value().equals("client")) {
                    // the way a player breaks: the client breaks the block it stands over (creative: at once) and tells the server. What is kept is the time between
                    // two ticks of the client less the 50 ms of a tick: a frame that froze the game shows up whole
                    if (stepTicks == 80) run(server, "gamemode creative @p");
                    if (stepTicks > 80 && !stressList.isEmpty()) {
                        long now = System.nanoTime();
                        net.minecraft.core.BlockPos pos = stressList.get(0);
                        if (stepTicks % 4 == 1) {
                            run(server, "tp @p " + (pos.getX() + 0.5) + " " + (pos.getY() + 2.0) + " " + (pos.getZ() + 0.5) + " 0 90");
                        } else if (stepTicks % 4 == 3) {
                            mc.gameMode.startDestroyBlock(pos, net.minecraft.core.Direction.UP);
                            stressList.remove(0);
                        }
                        if (stepTicks > 90) stressFps.add((int) Math.max(0, (now - stressLast) / 1000 - 50000));
                        stressLast = now;
                    }
                } else
                if (stepTicks > 80 && !stressList.isEmpty()) {
                    net.minecraft.core.BlockPos pos = stressList.remove(0);
                    server.execute(() -> {
                        long t0 = System.nanoTime();
                        server.getPlayerList().getPlayers().get(0).gameMode.destroyBlock(pos);      // survival: what is dropped is dropped
                        long took = System.nanoTime() - t0;
                        if (took > stressServerWorst) stressServerWorst = took;
                    });
                    stressFps.add((int) (frameNs(mc) / 1000));      // frame time in microseconds
                }
                if (stepTicks > 80 && stressList.isEmpty()) {
                    java.util.List<Integer> sorted = new java.util.ArrayList<>(stressFps);
                    java.util.Collections.sort(sorted);
                    int slow = 0;
                    StringBuilder top = new StringBuilder();
                    for (int i = 0; i < stressFps.size(); i++) if (stressFps.get(i) > 25000) { slow++; if (top.length() < 200) top.append(i).append(':').append(stressFps.get(i) / 1000).append("ms "); }
                    System.out.println("AUTOTEST stress2 slow frames (over 25 ms): " + slow + " at " + top);
                    System.out.println("AUTOTEST stress2 the longest break on the server: " + stressServerWorst / 1000 + " us");
                    System.out.println("AUTOTEST stress2 frames while breaking: " + sorted.size() + " samples, median " + sorted.get(sorted.size() / 2) / 1000.0 + " ms, worst " + sorted.get(sorted.size() - 1) / 1000.0 + " ms");
                    next();
                }
            }
            case "micro-debug" -> {
                MicroConfig.debugMesh = step.value().equals("on");
                next();
            }
            case "micro-hold" -> {
                // a microblock in the hand, for the preview of where it would go
                if (server == null) {
                    finish(mc, false, "micro-hold outside a world");
                    return;
                }
                server.execute(() -> {
                    net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                    player.getInventory().setItem(0, MicroItemData.make(net.minecraft.world.level.block.Blocks.OAK_PLANKS, 16, 6, 8));
                    #if MC_VER >= V1_21_5
                    player.getInventory().setSelectedSlot(0);
                    #else
                    player.getInventory().selected = 0;
                    #endif
                });
                next();
            }
            case "micro-pick" -> {
                // what the middle click would take
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                ItemStack picked = MicroPick.stack(mc.level, where);
                System.out.println("AUTOTEST pick " + (picked.isEmpty() ? "nothing" : com.akicater.lib.Compat.blockId(MicroItemData.material(picked)) + " " + java.util.Arrays.toString(MicroItemData.dims(picked))));
                next();
            }
            case "micro-watch" -> {
                // what the client has at the broken place, tick by tick: a gap with no parts is the flicker
                final net.minecraft.core.BlockPos where = mc.player.blockPosition().offset(0, 0, 3);
                var be = mc.level.getBlockEntity(where);
                System.out.println("AUTOTEST watch tick " + stepTicks + " block " + mc.level.getBlockState(where).getBlock() + " parts " + (be instanceof MicroBlockEntity m ? m.parts().size() : -1));
                if (stepTicks >= 12) next();
            }
            case "micro-table" -> {
                if (server == null) {
                    finish(mc, false, "micro-table outside a world");
                    return;
                }
                if (!started) {
                    started = true;
                    demoResult = null;
                    final net.minecraft.core.BlockPos where = mc.player.blockPosition();
                    server.execute(() -> demoResult = MicroTableTest.run(server.overworld(), where));
                } else if (demoResult != null) {
                    if (demoResult.equals("ok")) next();
                    else finish(mc, false, "micro-table: " + demoResult);
                }
            }
            case "micro-screen" -> {
                // opens the screen of the table (on this side only, not connected to a server menu) with something in it, for a screenshot
                if (step.value().equals("none")) {
                    setScreen(mc, null);
                    next();
                    return;
                }
                if (step.value().equals("wheel")) {
                    setScreen(mc, new com.akicater.microblocks.client.MicroWheelScreen());
                    next();
                    return;
                }
                if (step.value().equals("config")) {
                    setScreen(mc, new com.akicater.microblocks.client.MicroConfigScreen(null));
                    next();
                    return;
                }
                var menu = new MicroTableMenu(77, mc.player.getInventory());
                if (step.value().equals("cut")) {
                    menu.table().setItem(MicroTableMenu.IN, MicroItemData.make(net.minecraft.world.level.block.Blocks.OAK_PLANKS, 16, 16, 12));
                    menu.clickMenuButton(mc.player, MicroTableMenu.BTN_AXIS + 2);
                    for (int i = 0; i < 4; i++) menu.clickMenuButton(mc.player, MicroTableMenu.BTN_POS_MINUS);
                    menu.cut();
                    menu.table().setItem(MicroTableMenu.IN, MicroItemData.make(net.minecraft.world.level.block.Blocks.STONE_BRICKS, 16, 16, 16));
                } else {
                    menu.setMode(MicroTableMenu.MODE_PREFAB);
                    ItemStack paper = new ItemStack(Microblocks.BLUEPRINT.get());
                    PrefabData.write(paper, java.util.List.of(new Part(0, 0, 0, 16, 4, 16, net.minecraft.world.level.block.Blocks.STONE_BRICKS), new Part(7, 4, 7, 9, 16, 9, net.minecraft.world.level.block.Blocks.OAK_PLANKS)));
                    menu.table().setItem(MicroTableMenu.PAPER, paper);
                    menu.table().setItem(MicroTableMenu.ING_FIRST, new ItemStack(net.minecraft.world.level.block.Blocks.STONE_BRICKS.asItem(), 3));
                    menu.table().setItem(MicroTableMenu.ING_FIRST + 1, MicroItemData.make(net.minecraft.world.level.block.Blocks.OAK_PLANKS, 16, 16, 8));
                    menu.table().setItem(MicroTableMenu.ING_FIRST + 2, MicroItemData.make(net.minecraft.world.level.block.Blocks.OAK_PLANKS, 16, 16, 8));
                }
                setScreen(mc, new com.akicater.microblocks.client.MicroTableScreen(menu, mc.player.getInventory(), com.akicater.lib.Compat.translatable("block.microblocks.table")));
                next();
            }
            case "micro-view" -> {
                // the camera for a building: the player (a spectator) is teleported there
                final String view = MicroShowcase.NAMES.contains(step.value()) ? MicroShowcase.viewCommand(step.value()) : MicroDemo.viewCommand(step.value());
                run(server, view);
                System.out.println("AUTOTEST view " + view + " | player " + mc.player.position() + " mode " + mc.gameMode.getPlayerMode());
                next();
            }
            default -> finish(mc, false, "unknown step: " + step.key());
        }
    }
}

#else

/** The test hook is only compiled for autotest builds. */
public final class AutoTest {
    private AutoTest() {}

    public static void register() {}
}

#endif

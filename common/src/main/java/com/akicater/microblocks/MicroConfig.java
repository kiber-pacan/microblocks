package com.akicater.microblocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The settings of the mod (client side): a few lines of {@code key = value} in {@code config/microblocks.toml}. A missing or broken file gives the defaults, a
 * wrong value is ignored; nothing here can stop the game from starting.
 */
public final class MicroConfig {
    private MicroConfig() {}

    /** Draw the faces of the greedy mesh (every rectangle that the chunk mesh gets) around the microblocks near the player. */
    public static volatile boolean debugMesh = false;
    /** The preview of the box that would be placed fades in and out slowly. */
    public static volatile boolean blink = true;
    /** The mode that a player starts with: 0 free, 1 snap, 2 voxel (see {@link MicroSettings.Mode}). */
    public static volatile int defaultMode = 0;
    /** Whether a box may be put into others by default. */
    public static volatile boolean defaultReplace = false;
    /** Write every slow piece of work of the mod to the log (for finding a freeze). */
    public static volatile boolean debugTiming = false;

    private static Path file() {
        return dev.architectury.platform.Platform.getConfigFolder().resolve("microblocks.toml");
    }

    public static void load() {
        try {
            Path file = file();
            if (!Files.exists(file)) {
                save();
                return;
            }
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq < 0 || line.trim().startsWith("#")) continue;
                String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
                try {
                    switch (key) {
                        case "debugMesh" -> debugMesh = Boolean.parseBoolean(value);
                        case "blink" -> blink = Boolean.parseBoolean(value);
                        case "defaultMode" -> defaultMode = Math.max(0, Math.min(2, Integer.parseInt(value)));
                        case "defaultReplace" -> defaultReplace = Boolean.parseBoolean(value);
                        case "debugTiming" -> debugTiming = Boolean.parseBoolean(value);
                        default -> {}
                    }
                } catch (RuntimeException ignored) {
                    // a wrong value keeps the default
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("microblocks: the config could not be read: " + e);
        }
    }

    public static void save() {
        try {
            Files.write(file(), List.of(
                    "# Microblocks (client). The preview and the placing modes can also be changed in the game: End opens the wheel, the mod menu has this page.",
                    "# draw the rectangles of the greedy mesh around the microblocks that are near (for checking the mesh)",
                    "debugMesh = " + debugMesh,
                    "# the preview of the box that would be placed fades in and out slowly",
                    "blink = " + blink,
                    "# how a box follows the click: 0 free (any sixteenth, also across blocks), 1 snap (edges and middle), 2 voxel (any sixteenth inside one block)",
                    "defaultMode = " + defaultMode,
                    "# a box may be put into others: the last one is drawn, every one drops as what it was",
                    "defaultReplace = " + defaultReplace,
                    "# write every slow piece of work of the mod (over 8 ms) to the log, with its name: for finding a freeze",
                    "debugTiming = " + debugTiming));
        } catch (IOException | RuntimeException e) {
            System.out.println("microblocks: the config could not be written: " + e);
        }
    }
}

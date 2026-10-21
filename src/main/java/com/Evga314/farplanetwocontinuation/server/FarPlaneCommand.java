package com.Evga314.farplanetwocontinuation.server;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * {@code /FPhelp} lists the commands. {@code /farplane pregen detail start <radius> [<x> <z>]}, {@code status} and {@code stop}:
 * generates the detailed terrain of an area ahead of time, like Chunky does for chunks, but only the light copies used for distant
 * terrain. {@code /farplane pregen distant start <radius> [<x> <z>]}, {@code status} and {@code stop}: the same for the distant terrain.
 */
public final class FarPlaneCommand {
    private FarPlaneCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<@Nullable TileServer> server) {
        // Commands are case sensitive, so the lower case spelling works as well.
        dispatcher.register(Commands.literal("FPhelp").executes(context -> help(context.getSource())));
        dispatcher.register(Commands.literal("fphelp").executes(context -> help(context.getSource())));
        dispatcher.register(
            Commands.literal("farplane")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("pregen")
                        .executes(context -> usage(context.getSource()))
                        .then(
                            Commands.literal("detail")
                                .executes(context -> usage(context.getSource()))
                                .then(Commands.literal("status").executes(context -> status(context.getSource(), server.get())))
                                .then(Commands.literal("stop").executes(context -> stop(context.getSource(), server.get())))
                                .then(
                                    Commands.literal("start").executes(context -> usage(context.getSource())).then(
                                        Commands.argument("radius", IntegerArgumentType.integer(1, FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS))
                                            .executes(context -> start(context, server.get(), null))
                                            .then(
                                                Commands.argument("center", ColumnPosArgument.columnPos())
                                                    .executes(context -> start(context, server.get(), ColumnPosArgument.getColumnPos(context, "center")))
                                            )
                                    )
                                )
                        )
                        .then(
                            Commands.literal("distant")
                                .executes(context -> usage(context.getSource()))
                                .then(Commands.literal("status").executes(context -> statusDistant(context.getSource(), server.get())))
                                .then(Commands.literal("stop").executes(context -> stopDistant(context.getSource(), server.get())))
                                .then(
                                    Commands.literal("start").executes(context -> usage(context.getSource())).then(
                                        Commands.argument("radius", IntegerArgumentType.integer(64, FarPlaneConstants.MAX_LOD_DISTANCE))
                                            .executes(context -> startDistant(context, server.get(), null))
                                            .then(
                                                Commands.argument("center", ColumnPosArgument.columnPos())
                                                    .executes(context -> startDistant(context, server.get(), ColumnPosArgument.getColumnPos(context, "center")))
                                            )
                                    )
                                )
                        )
                )
        );
    }

    /** Every command of the mod with a short description, and the keys. Open to every player. */
    private static int help(CommandSourceStack source) {
        String[][] lines = {
            {"title", "FarPlaneTwoContinuation commands:"},
            {"fphelp", "/FPhelp - this list"},
            {"pregen", "/farplane pregen detail start <radius> - generate the detailed terrain within <radius> chunks (1-256) around you ahead of time"},
            {"distant", "/farplane pregen distant start <radius> - generate the distant terrain within <radius> blocks around you ahead of time, with all free CPU"},
            {"pregen_at", "... start <radius> <x> <z> - the same around the block column x z"},
            {"pregen_status", "/farplane pregen detail|distant status - progress and time left"},
            {"pregen_stop", "/farplane pregen detail|distant stop - stop; what is done stays saved"},
            {"operators", "The /farplane commands are for operators (in singleplayer: with cheats allowed)."},
            {"keys", "Keys: F7 - distant terrain on/off, F8 - settings, F3 + O - clear the LOD cache. They can be changed in Controls."},
        };
        for (String[] line : lines) {
            source.sendSuccess(() -> Component.translatableWithFallback(FarPlaneConstants.MOD_ID + ".help." + line[0], line[1]), false);
        }
        return 1;
    }

    private static int usage(CommandSourceStack source) {
        source.sendSuccess(() -> message("usage",
            "Usage: /farplane pregen detail start <radius in chunks, up to %s> [<x> <z>], /farplane pregen distant start <radius in blocks> [<x> <z>], /farplane pregen detail|distant status|stop",
            FarPlaneConstants.MAX_DETAIL_RADIUS_CHUNKS), false);
        return 1;
    }

    private static int statusDistant(CommandSourceStack source, @Nullable TileServer server) {
        if (server != null) {
            server.distantPregenStatus(source);
        }
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> context, @Nullable TileServer server, @Nullable ColumnPos center) {
        CommandSourceStack source = context.getSource();
        if (server == null) {
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(context, "radius");
        Vec3 position = source.getPosition();
        int centerX = center != null ? center.x() >> 4 : ((int) Math.floor(position.x)) >> 4;
        int centerZ = center != null ? center.z() >> 4 : ((int) Math.floor(position.z)) >> 4;
        return server.startPregen(source, source.getLevel(), centerX, centerZ, radius) ? 1 : 0;
    }

    private static int startDistant(CommandContext<CommandSourceStack> context, @Nullable TileServer server, @Nullable ColumnPos center) {
        CommandSourceStack source = context.getSource();
        if (server == null) {
            return 0;
        }
        int radius = IntegerArgumentType.getInteger(context, "radius");
        Vec3 position = source.getPosition();
        double centerX = center != null ? center.x() + 0.5 : position.x;
        double centerZ = center != null ? center.z() + 0.5 : position.z;
        return server.startDistantPregen(source, source.getLevel(), centerX, centerZ, radius) ? 1 : 0;
    }

    private static int stopDistant(CommandSourceStack source, @Nullable TileServer server) {
        if (server != null) {
            server.stopDistantPregen(source);
        }
        return 1;
    }

    private static int status(CommandSourceStack source, @Nullable TileServer server) {
        if (server != null) {
            server.pregenStatus(source);
        }
        return 1;
    }

    private static int stop(CommandSourceStack source, @Nullable TileServer server) {
        if (server != null) {
            server.stopPregen(source);
        }
        return 1;
    }

    /** Translated on clients with the mod, English everywhere else. */
    static Component message(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(FarPlaneConstants.MOD_ID + ".pregen." + key, fallback, args);
    }
}

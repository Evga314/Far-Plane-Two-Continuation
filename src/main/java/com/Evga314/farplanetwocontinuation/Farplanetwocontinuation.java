package com.Evga314.farplanetwocontinuation;

import com.Evga314.farplanetwocontinuation.config.ServerConfig;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.Evga314.farplanetwocontinuation.server.FarPlaneCommand;
import com.Evga314.farplanetwocontinuation.server.TileServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.jspecify.annotations.Nullable;

public class Farplanetwocontinuation implements ModInitializer {
    private static @Nullable TileServer tileServer;

    @Override
    public void onInitialize() {
        FarPlanePayloads.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> tileServer = new TileServer(server, ServerConfig.load()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (tileServer != null) {
                tileServer.close();
                tileServer = null;
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> FarPlaneCommand.register(dispatcher, () -> tileServer));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (tileServer != null) {
                tileServer.tick();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (tileServer != null) {
                tileServer.sendHello(handler.getPlayer());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (tileServer != null) {
                tileServer.onDisconnect(handler.getPlayer().getUUID());
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(FarPlanePayloads.TileRequest.TYPE, (payload, context) -> {
            if (tileServer != null) {
                tileServer.onRequest(context.player(), payload.requests());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(FarPlanePayloads.DetailRequest.TYPE, (payload, context) -> {
            if (tileServer != null) {
                tileServer.onDetailRequest(context.player(), payload.requests(), payload.generate(), payload.urgency() / 100.0F, payload.scanDepth());
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(FarPlanePayloads.ClearCache.TYPE, (payload, context) -> {
            if (tileServer != null) {
                tileServer.onClearCache(context.player());
            }
        });
    }
}

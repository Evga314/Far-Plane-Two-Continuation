package com.Evga314.farplanetwocontinuation.client;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.client.lod.LodManager;
import com.Evga314.farplanetwocontinuation.client.lod.LodRenderer;
import com.Evga314.farplanetwocontinuation.net.FarPlanePayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class FarplanetwocontinuationClient implements ClientModInitializer {
    private static LodManager manager;
    private static LodRenderer renderer;
    private static KeyMapping reloadKey;

    public static LodManager manager() {
        return manager;
    }

    public static LodRenderer renderer() {
        return renderer;
    }

    public static KeyMapping reloadKey() {
        return reloadKey;
    }

    @Override
    public void onInitializeClient() {
        manager = new LodManager(Minecraft.getInstance(), ClientConfig.load());
        renderer = new LodRenderer(manager);

        ClientPlayNetworking.registerGlobalReceiver(FarPlanePayloads.Hello.TYPE, (payload, context) -> manager.onHello(payload));
        ClientPlayNetworking.registerGlobalReceiver(FarPlanePayloads.TileDataPayload.TYPE, (payload, context) -> manager.onTileData(payload));
        ClientPlayNetworking.registerGlobalReceiver(FarPlanePayloads.DetailDataPayload.TYPE, (payload, context) -> manager.onDetailData(payload));
        ClientPlayNetworking.registerGlobalReceiver(FarPlanePayloads.CacheCleared.TYPE, (payload, context) -> manager.onCacheCleared(payload));
        ClientPlayNetworking.registerGlobalReceiver(FarPlanePayloads.DetailDirty.TYPE, (payload, context) -> manager.onDetailDirty(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, "main"));
        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key." + FarPlaneConstants.MOD_ID + ".toggle", InputConstants.KEY_F7, category)
        );
        KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key." + FarPlaneConstants.MOD_ID + ".settings", InputConstants.KEY_F8, category)
        );
        // Used together with F3, like vanilla's F3 + A, so it lives in the vanilla Debug category; see KeyboardHandlerMixin.
        reloadKey = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key." + FarPlaneConstants.MOD_ID + ".reload", InputConstants.KEY_O, KeyMapping.Category.DEBUG)
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                ClientConfig config = manager.config();
                config.enabled = !config.enabled;
                config.save();
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.translatable(
                        FarPlaneConstants.MOD_ID + (config.enabled ? ".toggle.on" : ".toggle.off")
                    ));
                }
            }
            while (reloadKey.consumeClick()) {
                // A press without F3 does nothing, the combination is handled in KeyboardHandlerMixin.
            }
            while (settingsKey.consumeClick()) {
                client.gui.setScreen(new LodConfigScreen(client.gui.screen()));
            }
            manager.tick();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            manager.close();
            renderer.close();
        });
    }
}

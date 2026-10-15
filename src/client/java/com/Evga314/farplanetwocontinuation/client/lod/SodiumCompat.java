package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Sodium replaces vanilla's chunk rendering, so vanilla's own render sections stay empty and cannot tell which chunks are on screen.
 * With Sodium installed its renderer is asked instead. Looked up once through reflection, so Sodium is never required; if its API
 * changes, LOD terrain simply stays under the blocks as before.
 */
final class SodiumCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final @Nullable MethodHandle INSTANCE;
    private static final @Nullable MethodHandle IS_SECTION_READY;

    static {
        MethodHandle instance = null;
        MethodHandle ready = null;
        if (FabricLoader.getInstance().isModLoaded("sodium")) {
            try {
                Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                instance = lookup.findStatic(renderer, "instanceNullable", MethodType.methodType(renderer));
                ready = lookup.findVirtual(renderer, "isSectionReady", MethodType.methodType(boolean.class, int.class, int.class, int.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("FarPlane could not find Sodium's renderer; LOD terrain may show through Sodium chunks", e);
                instance = null;
                ready = null;
            }
        }
        INSTANCE = instance;
        IS_SECTION_READY = ready;
    }

    private SodiumCompat() {
    }

    static boolean isActive() {
        return INSTANCE != null && IS_SECTION_READY != null;
    }

    /** The current Sodium world renderer, or null without a level. */
    static @Nullable Object renderer() {
        try {
            return INSTANCE == null ? null : INSTANCE.invoke();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Whether Sodium has built a section and will draw it when it is in view. */
    static boolean isSectionReady(Object renderer, int sectionX, int sectionY, int sectionZ) {
        try {
            return (boolean) IS_SECTION_READY.invoke(renderer, sectionX, sectionY, sectionZ);
        } catch (Throwable e) {
            return false;
        }
    }
}

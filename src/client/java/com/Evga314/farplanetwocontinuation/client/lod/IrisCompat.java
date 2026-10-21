package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.logging.LogUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Whether an Iris shader pack is drawing the frame, looked up by reflection the way Sodium is: Iris is never a dependency, and
 * when it is absent or its API is not found, no pack counts as active and the LOD terrain stays on.
 */
final class IrisCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final @Nullable MethodHandle GET_INSTANCE;
    private static final @Nullable MethodHandle SHADER_PACK_IN_USE;

    static {
        MethodHandle getInstance = null;
        MethodHandle inUse = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                getInstance = lookup.findStatic(api, "getInstance", MethodType.methodType(api));
                inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("FarPlane could not find Iris's API; shader packs will not turn the LOD terrain off", e);
                getInstance = null;
                inUse = null;
            }
        }
        GET_INSTANCE = getInstance;
        SHADER_PACK_IN_USE = inUse;
    }

    private IrisCompat() {
    }

    /** Whether a shader pack is in use right now; false when Iris is absent, its API was not found, or asking failed. */
    static boolean shadersActive() {
        if (GET_INSTANCE == null || SHADER_PACK_IN_USE == null) {
            return false;
        }
        try {
            Object api = GET_INSTANCE.invoke();
            return api != null && (boolean) SHADER_PACK_IN_USE.invoke(api);
        } catch (Throwable e) {
            return false;
        }
    }
}

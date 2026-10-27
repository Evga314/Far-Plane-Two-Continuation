package com.Evga314.farplanetwocontinuation.client.lod;

import com.Evga314.farplanetwocontinuation.client.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Which output draws the LOD terrain on this device, decided in one place: inside vanilla's main pass ({@link VanillaPassOutput}) where
 * the depth goes from 0 to 1, so the far plane can be at infinity without vanilla losing precision (computers, Vulkan), and the
 * setting asks for it; with an Iris shader pack that can, by the pack's programs (IrisOutput); else in a pass of its own
 * ({@link OwnPassOutput}), as on phones with OpenGL and with Iris without such a pack, which takes over vanilla's main pass.
 */
final class GpuCaps {
    private GpuCaps() {
    }

    /**
     * With an Iris shader pack whose DH programs draw the LOD terrain ({@link IrisOutput}); for now only with the new renderer, still
     * behind its debug toggle.
     */
    static boolean irisPass(ClientConfig config) {
        return config.newRenderer && DebugToggles.surface && IrisGlue.ready();
    }

    static boolean vanillaPass(ClientConfig config) {
        return config.newRenderer && !IrisCompat.shadersActive() && !DebugToggles.noMainPass
            && RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
    }
}

package com.Evga314.farplanetwocontinuation.client.lod;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The LOD terrain drawn by an Iris shader pack with the programs packs have for Distant Horizons (dh_terrain and the others): the pack
 * lights it, fogs it, shades it and puts it together with vanilla's blocks as it does DH's terrain. Iris hands its DH glue (DHCompat,
 * the DISTANT_HORIZONS define, the DH programs and framebuffers) only to DH; without DH the mod takes DH's place there, see the mixins
 * in mixin.iris. With DH installed they are not applied and nothing here is used.
 * <p>
 * The one place that knows of Iris, and nothing here names a class of it: the {@link Bridge} is made by name only when Iris is loaded,
 * and when anything of Iris it needs is not found, the mod logs it once and leaves the LOD terrain out with a pack, as before.
 */
public final class IrisGlue {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** What the mod asks of Iris, see IrisOutput. */
    interface Bridge {
        /** A shader pack is in use and its DH programs are ready to draw with. */
        boolean ready();

        /** Draws the rough tiles of the frame into the pack's buffers, right after vanilla's opaque terrain; see IrisOutput. */
        void drawTerrain(FrameDrawList list);

        /** Vanilla's opaque things are drawn: the depth of the LOD terrain is cleared if it was not drawn this frame. */
        void endSolid();

        /** After the pack's deferred passes, before vanilla's translucent things: the water of the frame's rough tiles, if drawn. */
        void drawWater(@Nullable FrameDrawList list);

        /** Builds the pack again, for the DISTANT_HORIZONS define to come or go. */
        void reload();
    }

    private static final @Nullable Bridge BRIDGE = createBridge();

    /** Whether the mod wants packs to draw the LOD terrain, set by LodManager; the define and the programs follow it. */
    private static volatile boolean wanted;
    /** Whether the pack loaded last got the DISTANT_HORIZONS define, see StandardMacrosMixin: its DH programs need it. */
    private static volatile boolean defined;
    /** Whether tiles are built for the pack (see PackMesher), set by LodManager. */
    static volatile boolean packMeshes;
    /** Whether the pack's tiles get soft borders, see ClientConfig.shaderSoftBorders; set by LodManager. */
    static volatile boolean softBorders;

    // The planes and reach of the LOD terrain as Iris gives them to the pack (dhNearPlane, dhFarPlane, dhRenderDistance and through
    // dhProjection), set by LodManager. Steady from frame to frame: Iris writes the uniforms before the tiles of the frame are culled.
    static final float NEAR_PLANE = 8.0F;
    private static volatile float nearPlane = NEAR_PLANE;
    private static volatile float farPlane = 4096.0F;
    private static volatile int renderDistance = 2048;

    private IrisGlue() {
    }

    private static @Nullable Bridge createBridge() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("iris") || loader.isModLoaded("distanthorizons")) {
            return null;
        }
        try {
            return (Bridge) Class.forName("com.Evga314.farplanetwocontinuation.client.lod.IrisOutput").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOGGER.warn("FarPlane could not connect to Iris; shader packs will not draw the LOD terrain", e);
            return null;
        }
    }

    /** Iris is there and the mod can take DH's place in it. */
    public static boolean available() {
        return BRIDGE != null;
    }

    /** A shader pack draws the LOD terrain this frame, see GpuCaps.irisPass. */
    static boolean ready() {
        return BRIDGE != null && BRIDGE.ready();
    }

    /** For the define and the programs: the mod takes DH's place in Iris. */
    public static boolean wanted() {
        return BRIDGE != null && wanted;
    }

    /** For the DH programs: the mod wants them and the pack was loaded with the define. */
    public static boolean programsWanted() {
        return wanted() && defined;
    }

    /** Called by StandardMacrosMixin as a pack's defines are made. */
    public static void defined(boolean value) {
        defined = value;
    }

    /** Called once a tick; a change builds the pack again, with or without the define. */
    static void want(boolean want) {
        if (BRIDGE == null || wanted == want) {
            return;
        }
        wanted = want;
        BRIDGE.reload();
    }

    static void planes(float near, float far, int distance) {
        nearPlane = near;
        farPlane = far;
        renderDistance = distance;
    }

    static void drawTerrain(FrameDrawList list) {
        if (BRIDGE != null) {
            BRIDGE.drawTerrain(list);
        }
    }

    /** See Bridge.drawWater; with null only the depth for dhDepthTex1 is brought up to date. */
    public static void drawWater(@Nullable FrameDrawList list) {
        if (BRIDGE != null) {
            BRIDGE.drawWater(list);
        }
    }

    public static void endSolid() {
        if (BRIDGE != null) {
            BRIDGE.endSolid();
        }
    }

    public static float nearPlane() {
        return nearPlane;
    }

    public static float farPlane() {
        return farPlane;
    }

    /** In blocks, as DH gives it. */
    public static int renderDistance() {
        return renderDistance;
    }

    /** The projection of the LOD terrain: vanilla's view with the planes above, depth from -1 to 1, as DH's. */
    public static Matrix4f projection(Matrix4fc vanilla, Matrix4f out) {
        return out.setPerspective(vanilla.perspectiveFov(), vanilla.m11() / vanilla.m00(), nearPlane, farPlane);
    }
}
